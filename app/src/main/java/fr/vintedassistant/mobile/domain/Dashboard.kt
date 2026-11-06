package fr.vintedassistant.mobile.domain

import java.time.Instant
import java.time.ZoneId

const val DAY_MS: Long = 24L * 60 * 60 * 1000

data class StagnantArticle(
    val article: Article,
    val daysOnline: Int,
    val suggestedPriceCents: Long?,
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
    val month = counted.filter { it.soldAt >= monthStart }
    val rate = settings.contributionRateBp

    fun daysToSell(s: Sale): Double? = s.listedAt?.let { (s.soldAt - it).coerceAtLeast(0L).toDouble() / DAY_MS }

    val stagnant = stock
        .filter { it.status == ArticleStatus.ONLINE }
        .mapNotNull { a ->
            val since = a.lastVisibleAt ?: return@mapNotNull null
            val days = ((now - since) / DAY_MS).toInt()
            if (days < settings.stagnantDays) null
            else StagnantArticle(a, days, PriceAdvisor.suggestDrop(a.priceCents, a.floorPriceCents, settings.priceDropPercent))
        }
        .sortedByDescending { it.daysOnline }

    val categories = counted
        .groupBy { it.category.ifBlank { "Sans catégorie" } }
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
    )
}
