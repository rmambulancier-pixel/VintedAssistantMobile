package fr.vintedassistant.mobile.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Contenu du widget d'écran d'accueil : l'essentiel à voir d'un coup d'œil, sans ouvrir l'application.
 * 1. Le mois : chiffre d'affaires, objectif, ventes, bénéfice.
 * 2. Les colis : combien à déposer, et pour quand (en rouge si c'est aujourd'hui ou en retard).
 * 3. Le stock : en ligne, à mettre en vente, réassort attendu.
 */
data class WidgetSummary(
    val monthLabel: String,
    val revenue: String,
    val goalPercent: Int?,
    val goalText: String,
    val monthDetail: String,
    val parcels: String,
    val parcelsUrgent: Boolean,
    val parcelsCount: Int,
    val stock: String,
) {
    companion object {
        private val DAY = DateTimeFormatter.ofPattern("EEE d", Locale.FRANCE)

        fun build(
            monthRevenueCents: Long,
            monthNetCents: Long,
            monthSales: Int,
            goalEuros: Int,
            dues: List<ShippingDeadline.Due>,
            toShipUndated: Int,
            board: StockBoard,
            today: LocalDate,
        ): WidgetSummary {
            val goalCents = goalEuros * 100L
            val pct = if (goalCents > 0) (monthRevenueCents * 100 / goalCents).toInt() else null
            val goalText = when {
                pct == null -> ""
                monthRevenueCents >= goalCents -> "objectif atteint"
                else -> "encore ${Money.formatEuros(goalCents - monthRevenueCents)}"
            }
            val count = dues.size + toShipUndated
            val first = dues.minByOrNull { it.lastDay }
            val parcels = when {
                count == 0 -> "Aucun colis à déposer"
                first == null -> "$count colis à déposer"
                first.late -> "$count colis à déposer • délai dépassé"
                first.lastDay == today -> "$count colis à déposer aujourd'hui"
                else -> "$count colis à déposer • avant ${first.lastDay.format(DAY)}"
            }
            val toList = board.toList + board.drafts
            val next = board.orders.firstOrNull()?.eta
            val stock = listOfNotNull(
                "${board.online + board.reserved} en ligne",
                toList.takeIf { it > 0 }?.let { "$it à mettre en vente" },
                board.restocking.takeIf { it > 0 }?.let { n ->
                    "$n en réassort" + (next?.let { " (${it.dayOfMonth} ${it.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.FRANCE).trimEnd('.')})" } ?: "")
                },
            ).joinToString(" • ")
            return WidgetSummary(
                monthLabel = "Ventes de " + today.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale.FRANCE),
                revenue = Money.formatEuros(monthRevenueCents),
                goalPercent = pct,
                goalText = goalText,
                monthDetail = (if (monthSales > 1) "$monthSales ventes" else "$monthSales vente") + " • ${Money.formatEuros(monthNetCents)} net",
                parcels = parcels,
                parcelsUrgent = dues.any { it.urgent },
                parcelsCount = count,
                stock = stock,
            )
        }
    }
}
