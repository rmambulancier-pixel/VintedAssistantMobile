package fr.vintedassistant.mobile.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Conseiller de réassort : pour chaque bijou acheté, ce qu'il a rapporté et à quelle vitesse il est parti.
 * Un bijou est « à racheter » quand il s'est vendu au moins deux fois et qu'il n'en reste presque plus.
 */
object Restock {
    data class Advice(
        val key: String,
        val title: String,
        val imageFile: String?,
        val sold: Int,
        /** Pièces encore disponibles : en vente, en brouillon, sans fiche ou en livraison. */
        val remaining: Int,
        val inTransit: Int,
        val averagePriceCents: Long,
        val averageCostCents: Long,
        /** Délai moyen entre la mise en ligne et la vente, null si inconnu. */
        val averageDays: Double?,
    ) {
        val marginCents: Long get() = averagePriceCents - averageCostCents
        /** Combien de fois la mise est récupérée : 7 € vendu pour 1 € d'achat donne 7. */
        val multiple: Double get() = if (averageCostCents > 0) averagePriceCents.toDouble() / averageCostCents else 0.0
        val totalProfitCents: Long get() = marginCents * sold
    }

    fun advise(stock: StockOverview, sales: List<Sale>): List<Advice> {
        val byArticle = sales.filter { it.counted && it.articleId != null }.groupBy { it.articleId!! }
        return stock.products.mapNotNull { p ->
            val own = p.articles.flatMap { byArticle[it.id].orEmpty() }
            if (own.size < 2) return@mapNotNull null
            val remaining = p.notListed + p.inTransit + p.drafts + p.forSale
            if (remaining > 1) return@mapNotNull null
            val delays = own.filter { it.dateKnown }.mapNotNull { s -> s.listedAt?.let { (s.soldAt - it).coerceAtLeast(0).toDouble() / DAY_MS } }
            Advice(
                key = p.key,
                title = p.template?.title?.takeIf { it.isNotBlank() } ?: p.name,
                imageFile = p.template?.photos?.firstOrNull() ?: p.imageFile,
                sold = own.size,
                remaining = remaining,
                inTransit = p.inTransit,
                averagePriceCents = own.sumOf { it.salePriceCents } / own.size,
                averageCostCents = own.sumOf { it.costCents } / own.size,
                averageDays = if (delays.isEmpty()) null else delays.average(),
            )
        }.sortedWith(compareByDescending<Advice> { it.totalProfitCents }.thenBy { it.averageDays ?: Double.MAX_VALUE })
    }

    data class Projection(val soFarCents: Long, val projectedCents: Long, val lastMonthCents: Long)

    /** Chiffre d'affaires du mois prolongé au rythme actuel, comparé au mois précédent. */
    fun projection(sales: List<Sale>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Projection {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.withDayOfMonth(1)
        fun millis(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val dated = sales.filter { it.counted && it.dateKnown }
        val month = dated.filter { it.soldAt >= millis(start) }.sumOf { it.salePriceCents }
        val last = dated.filter { it.soldAt >= millis(start.minusMonths(1)) && it.soldAt < millis(start) }.sumOf { it.salePriceCents }
        val projected = month * today.lengthOfMonth() / today.dayOfMonth
        return Projection(month, projected, last)
    }
}
