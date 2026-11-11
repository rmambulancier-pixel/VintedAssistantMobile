package fr.vintedassistant.mobile.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Séries du tableau de bord : chiffre d'affaires par jour et par mois, bijoux les plus rentables. */
object Charts {
    data class Day(val date: LocalDate, val revenueCents: Long, val sales: Int)

    /** Les [days] derniers jours, aujourd'hui compris, du plus ancien au plus récent (jours sans vente à zéro). */
    fun daily(sales: List<Sale>, days: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Day> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val byDay = sales.filter { it.counted && it.dateKnown }
            .groupBy { Instant.ofEpochMilli(it.soldAt).atZone(zone).toLocalDate() }
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val list = byDay[d].orEmpty()
            Day(d, list.sumOf { it.salePriceCents }, list.size)
        }
    }

    data class Month(val month: YearMonth, val label: String, val revenueCents: Long, val netCents: Long, val sales: Int)

    /** Les [months] derniers mois, le mois en cours compris, du plus ancien au plus récent. */
    fun monthly(sales: List<Sale>, rateBp: Int, months: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Month> {
        val current = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        val byMonth = sales.filter { it.counted && it.dateKnown }
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.soldAt).atZone(zone).toLocalDate()) }
        return (months - 1 downTo 0).map { back ->
            val m = current.minusMonths(back.toLong())
            val list = byMonth[m].orEmpty()
            Month(m, m.month.getDisplayName(TextStyle.SHORT, Locale.FRANCE).trimEnd('.'), list.sumOf { it.salePriceCents }, list.sumOf { Finance.netCents(it, rateBp) }, list.size)
        }
    }

    data class Top(val title: String, val image: String?, val sold: Int, val revenueCents: Long, val netCents: Long, val averageDays: Double?)

    /**
     * Bijoux classés par bénéfice net total. Les ventes sont regroupées par bijou acheté (toutes annonces
     * confondues) quand la fiche est rattachée à un achat, sinon par titre.
     */
    fun topProducts(stock: StockOverview?, sales: List<Sale>, rateBp: Int, max: Int = 6): List<Top> {
        val productOf = HashMap<Long, StockProduct>()
        stock?.products?.forEach { p -> p.articles.forEach { productOf[it.id] = p } }
        return sales.filter { it.counted }
            .groupBy { s -> s.articleId?.let { productOf[it]?.key } ?: ("t:" + StockLedger.normalize(s.title)) }
            .map { (_, list) ->
                val p = list.firstNotNullOfOrNull { s -> s.articleId?.let { productOf[it] } }
                val delays = list.filter { it.dateKnown }.mapNotNull { s -> s.listedAt?.let { (s.soldAt - it).coerceAtLeast(0).toDouble() / DAY_MS } }
                Top(
                    title = list.maxByOrNull { it.soldAt }!!.title,
                    image = p?.articles?.firstNotNullOfOrNull { it.photos.firstOrNull() } ?: p?.imageFile,
                    sold = list.size,
                    revenueCents = list.sumOf { it.salePriceCents },
                    netCents = list.sumOf { Finance.netCents(it, rateBp) },
                    averageDays = delays.takeIf { it.isNotEmpty() }?.average(),
                )
            }
            .sortedByDescending { it.netCents }
            .take(max)
    }
}
