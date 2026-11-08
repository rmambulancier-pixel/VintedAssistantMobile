package fr.vintedassistant.mobile.domain

import java.time.Instant
import java.time.ZoneId

const val DAY_MS: Long = 24L * 60 * 60 * 1000

data class StagnantArticle(
    val article: Article,
    val daysOnline: Int,
    val suggestedPriceCents: Long?,
    /** Prix médian de vente des modèles proches, quand au moins deux ventes existent. */
    val marketPriceCents: Long? = null,
    val marketSales: Int = 0,
)

data class CategoryStats(
    val category: String,
    val salesCount: Int,
    val revenueCents: Long,
    val averageNetCents: Long,
    val averageDaysToSell: Double?,
)

data class DashboardStats(
    val stockCount: Int,
    val draftCount: Int,
    val onlineCount: Int,
    val stockCostCents: Long,
    val potentialMarginCents: Long,
    val monthSalesCount: Int,
    val monthRevenueCents: Long,
    val monthNetCents: Long,
    val totalSalesCount: Int,
    val totalRevenueCents: Long,
    val totalNetCents: Long,
    val toShipCount: Int,
    val averageDaysToSell: Double?,
    val stagnant: List<StagnantArticle>,
    val categories: List<CategoryStats>,
    /** Ventes sans date connue (importées de Vinted) : comptées dans le total, pas dans le mois. */
    val undatedSalesCount: Int = 0,
    /** Articles (en stock ou vendus) dont le coût d'achat n'est pas renseigné. */
    val missingCostCount: Int = 0,
)

data class DashboardSettings(
    val contributionRateBp: Int,
    val stagnantDays: Int,
    val priceDropPercent: Int,
)

object PriceAdvisor {
    /**
     * Baisse de prix suggérée : pourcentage appliqué, arrondi à 0,50 € inférieur,
     * jamais sous le prix plancher. Null si aucune baisse n'est possible.
     */
    fun suggestDrop(priceCents: Long, floorCents: Long?, percent: Int): Long? {
        if (percent <= 0 || priceCents <= 0) return null
        val raw = priceCents * (100 - percent.coerceAtMost(90)) / 100
        val rounded = raw - raw % 50
        val bounded = maxOf(rounded, floorCents ?: 0L)
        return if (bounded >= priceCents) null else bounded
    }

    data class Market(val medianCents: Long, val sales: Int)

    /**
     * Prix auquel des modèles proches se sont réellement vendus : même type de bijou,
     * titre semblable (au moins 60 % de mots communs). Il faut au moins deux ventes.
     */
    fun market(article: Article, sales: List<Sale>): Market? {
        val category = JewelryCategory.of(article.category, article.title)
        val prices = sales
            .filter { it.counted && it.salePriceCents > 0 }
            .filter { JewelryCategory.of(it.category, it.title) == category }
            .filter { StockLedger.similarity(it.title, article.title) >= 0.6 }
            .map { it.salePriceCents }
            .sorted()
        if (prices.size < 2) return null
        val n = prices.size
        val median = if (n % 2 == 1) prices[n / 2] else (prices[n / 2 - 1] + prices[n / 2]) / 2
        return Market(median, n)
    }

    /**
     * Prix conseillé pour un article qui stagne : le prix du marché s'il est plus bas que le prix actuel,
     * sinon la baisse en pourcentage. Arrondi à 0,50 € inférieur, jamais sous le plancher.
     */
    fun suggest(article: Article, sales: List<Sale>, percent: Int): Long? {
        val market = market(article, sales)
        if (market != null && market.medianCents < article.priceCents) {
            val rounded = market.medianCents - market.medianCents % 50
            val bounded = maxOf(rounded, article.floorPriceCents ?: 0L)
            if (bounded < article.priceCents) return bounded
        }
        return suggestDrop(article.priceCents, article.floorPriceCents, percent)
    }
}

fun computeDashboard(
    articles: List<Article>,
    sales: List<Sale>,
    settings: DashboardSettings,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): DashboardStats {
    val stock = articles.filter { it.status.inStock }
    val counted = sales.filter { it.counted }
    val monthStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val month = counted.filter { it.dateKnown && it.soldAt >= monthStart }
    val rate = settings.contributionRateBp

    fun daysToSell(s: Sale): Double? = if (!s.dateKnown) null else s.listedAt?.let { (s.soldAt - it).coerceAtLeast(0L).toDouble() / DAY_MS }

    val stagnant = stock
        .filter { it.status == ArticleStatus.ONLINE }
        .mapNotNull { a ->
            val since = a.lastVisibleAt ?: return@mapNotNull null
            val days = ((now - since) / DAY_MS).toInt()
            if (days < settings.stagnantDays) null
            else {
                val market = PriceAdvisor.market(a, sales)
                StagnantArticle(a, days, PriceAdvisor.suggest(a, sales, settings.priceDropPercent), market?.medianCents, market?.sales ?: 0)
            }
        }
        .sortedByDescending { it.daysOnline }

    val categories = counted
        .groupBy { JewelryCategory.of(it.category, it.title) }
        .map { (category, list) ->
            val delays = list.mapNotNull(::daysToSell)
            CategoryStats(
                category = category,
                salesCount = list.size,
                revenueCents = list.sumOf { it.salePriceCents },
                averageNetCents = list.sumOf { Finance.netCents(it, rate) } / list.size,
                averageDaysToSell = if (delays.isEmpty()) null else delays.average(),
            )
        }
        .sortedWith(compareByDescending<CategoryStats> { it.salesCount }.thenByDescending { it.averageNetCents })

    val delays = counted.mapNotNull(::daysToSell)
    return DashboardStats(
        stockCount = stock.size,
        draftCount = stock.count { it.status == ArticleStatus.DRAFT },
        onlineCount = stock.count { it.status == ArticleStatus.ONLINE },
        stockCostCents = stock.sumOf { it.costCents },
        potentialMarginCents = stock.sumOf { it.marginCents },
        monthSalesCount = month.size,
        monthRevenueCents = month.sumOf { it.salePriceCents },
        monthNetCents = month.sumOf { Finance.netCents(it, rate) },
        totalSalesCount = counted.size,
        totalRevenueCents = counted.sumOf { it.salePriceCents },
        totalNetCents = counted.sumOf { Finance.netCents(it, rate) },
        toShipCount = sales.count { it.shippingStatus == ShippingStatus.TO_SHIP },
        averageDaysToSell = if (delays.isEmpty()) null else delays.average(),
        stagnant = stagnant,
        categories = categories,
        undatedSalesCount = counted.count { !it.dateKnown },
        missingCostCount = articles.count { it.costCents == 0L && it.status != ArticleStatus.WITHDRAWN },
    )
}
