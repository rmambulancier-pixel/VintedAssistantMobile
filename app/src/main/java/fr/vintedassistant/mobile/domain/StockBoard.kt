package fr.vintedassistant.mobile.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tableau de bord du stock, pièce par pièce, en quatre blocs qui ne se recouvrent pas :
 * - **chez moi** : pièces physiquement en main (à mettre en vente, en préparation, en vente, vendues à emballer) ;
 * - **en réassort** : commandées chez le fournisseur, pas encore arrivées ;
 * - **en route vers l'acheteur** : vendues et expédiées, pas encore livrées ;
 * - **terminées** : vendues et livrées (ou vente importée sans suivi).
 * Plus les pièces jamais reçues et les annonces retirées, à part.
 */
data class StockBoard(
    val toList: Int,
    val drafts: Int,
    val online: Int,
    val reserved: Int,
    val toShip: Int,
    val restocking: Int,
    val shipping: Int,
    val done: Int,
    val withdrawn: Int,
    val neverReceived: Int,
    val inHandCostCents: Long,
    val onSaleValueCents: Long,
    val restockCostCents: Long,
    val orders: List<Arrival>,
) {
    /** Pièces en main : tout ce qui est physiquement chez l'utilisateur. */
    val inHand: Int get() = toList + drafts + online + reserved + toShip
    val sold: Int get() = toShip + shipping + done
    val total: Int get() = inHand + restocking + shipping + done + withdrawn

    /** Commande fournisseur attendue. */
    data class Arrival(val orderRef: String, val orderedAt: Long, val pieces: Int, val costCents: Long, val eta: LocalDate?, val etaText: String)

    companion object {
        fun build(stock: StockOverview, sales: List<Sale>, today: LocalDate = LocalDate.now()): StockBoard {
            val saleOf = sales.filter { it.articleId != null && it.shippingStatus != ShippingStatus.CANCELLED }
                .groupBy { it.articleId!! }.mapValues { (_, l) -> l.maxByOrNull { it.soldAt }!! }
            val all = stock.products.flatMap { it.articles } + stock.unlinked
            var toShip = 0; var shipping = 0; var done = 0
            all.filter { it.status == ArticleStatus.SOLD }.forEach { a ->
                when (saleOf[a.id]?.shippingStatus) {
                    ShippingStatus.TO_SHIP -> toShip++
                    ShippingStatus.SHIPPED -> shipping++
                    else -> done++
                }
            }
            val inHandArticles = all.filter { it.status.inStock }
            val lots = stock.products.flatMap { it.lots }
            val arrivals = lots.filter { !it.received && it.remaining > 0 }.groupBy { it.orderId }.map { (_, l) ->
                val first = l.first()
                val eta = etaDate(first.deliveryEta, today)
                Arrival(first.orderRef, first.orderedAt, l.sumOf { it.remaining }, l.sumOf { it.remaining * it.landedUnitCents }, eta, first.deliveryEta)
            }.sortedWith(compareBy<Arrival> { it.eta ?: LocalDate.MAX }.thenBy { it.orderedAt })
            return StockBoard(
                toList = stock.notListed,
                drafts = all.count { it.status == ArticleStatus.DRAFT },
                online = all.count { it.status == ArticleStatus.ONLINE },
                reserved = all.count { it.status == ArticleStatus.RESERVED },
                toShip = toShip,
                restocking = stock.inTransit,
                shipping = shipping,
                done = done,
                withdrawn = all.count { it.status == ArticleStatus.WITHDRAWN },
                neverReceived = lots.sumOf { it.missing },
                inHandCostCents = stock.notListedValueCents + inHandArticles.sumOf { it.costCents },
                onSaleValueCents = all.filter { it.status == ArticleStatus.ONLINE || it.status == ArticleStatus.RESERVED }.sumOf { it.priceCents },
                restockCostCents = stock.inTransitValueCents,
                orders = arrivals,
            )
        }

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /** Date annoncée par le fournisseur (« Thur, Dec 03 »), rapportée à l'année la plus plausible. */
        fun etaDate(eta: String, today: LocalDate): LocalDate? {
            val m = Regex("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})").find(eta) ?: return null
            val month = MONTHS.indexOf(m.groupValues[1].lowercase()) + 1
            var date = runCatching { LocalDate.of(today.year, month, m.groupValues[2].toInt()) }.getOrNull() ?: return null
            if (date.isBefore(today.minusMonths(6))) date = date.plusYears(1)
            return date
        }

        private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)

        /** « arrivée prévue jeudi 3 décembre (dans 23 jours) », ou « attendue depuis le … ». */
        fun etaLabel(a: Arrival, today: LocalDate): String {
            val eta = a.eta ?: return if (a.etaText.isBlank()) "date d'arrivée inconnue" else "arrivée prévue ${a.etaText}"
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, eta)
            return when {
                days > 1 -> "arrivée prévue ${eta.format(DAY)} (dans $days jours)"
                days == 1L -> "arrivée prévue demain"
                days == 0L -> "arrivée prévue aujourd'hui"
                else -> "attendue depuis le ${eta.format(DAY)}"
            }
        }
    }
}
