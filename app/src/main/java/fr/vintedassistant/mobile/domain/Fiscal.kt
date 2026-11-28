package fr.vintedassistant.mobile.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Repères fiscaux, sans conseil : seuils de déclaration DAC7 de Vinted (30 ventes ou 2 000 € sur l'année civile,
 * l'un ou l'autre) et registres exportables (livre des recettes, registre des achats), tirés des ventes et des
 * commandes fournisseur déjà dans l'application.
 */
object Fiscal {
    const val DAC7_SALES = 30
    const val DAC7_CENTS = 200_000L
    private val ZONE = ZoneId.of("Europe/Paris")
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    data class Year(val year: Int, val sales: Int, val revenueCents: Long, val undated: Int) {
        val reached: Boolean get() = sales >= DAC7_SALES || revenueCents > DAC7_CENTS
        val salesPercent: Int get() = (sales * 100 / DAC7_SALES).coerceAtMost(999)
        val revenuePercent: Int get() = (revenueCents * 100 / DAC7_CENTS).toInt().coerceAtMost(999)
    }

    private fun yearOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZONE).year

    /** Ventes de l'année civile (annulées exclues). Les ventes sans date connue sont comptées à part. */
    fun year(sales: List<Sale>, year: Int): Year {
        val list = sales.filter { it.counted && yearOf(it.soldAt) == year }
        return Year(year, list.size, list.sumOf { it.salePriceCents }, list.count { !it.dateKnown })
    }

    fun text(y: Year): String = when {
        y.reached -> "Seuil DAC7 ${y.year} atteint (${y.sales} ventes, ${Money.formatEuros(y.revenueCents)}) : " +
            "Vinted demande un formulaire à remplir sous 60 jours."
        else -> "DAC7 ${y.year} : ${y.sales}/$DAC7_SALES ventes, ${Money.formatEuros(y.revenueCents)} sur 2 000 €."
    }

    private fun csv(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
    private fun euros(cents: Long) = (cents / 100).toString() + "," + (kotlin.math.abs(cents) % 100).toString().padStart(2, '0')

    /**
     * Livre des recettes de l'année : date, référence, nature, montant encaissé, mode d'encaissement.
     * Montant = prix de vente enregistré (offre acceptée comprise quand elle a été saisie).
     */
    fun receiptsCsv(sales: List<Sale>, year: Int): String = buildString {
        append('﻿')
        appendLine("Date;Référence;Nature de la vente;Montant (€);Mode d'encaissement;Remarque")
        sales.filter { it.counted && yearOf(it.soldAt) == year }.sortedBy { it.soldAt }.forEach { s ->
            val date = Instant.ofEpochMilli(s.soldAt).atZone(ZONE).toLocalDate().format(DATE)
            append(listOf(
                csv(date), csv("V-${s.id}"), csv(s.title), euros(s.salePriceCents), csv("Vinted (porte-monnaie)"),
                csv(if (s.dateKnown) "" else "date approximative (vente importée)"),
            ).joinToString(";")).append('\n')
        }
    }

    /**
     * Ligne du registre des achats. [nature] : « Marchandises » (revendues) ou « Fournitures » (emballages,
     * matériel) ; [detail] : ce qui a été acheté quand ce n'est pas une commande de marchandises.
     */
    data class Purchase(
        val date: LocalDate, val supplier: String, val ref: String, val itemsCents: Long, val shippingCents: Long,
        val feesCents: Long, val pieces: Int, val nature: String = "Marchandises", val detail: String = "",
    )

    /** Un achat de fournitures, prêt pour le registre (montant payé, port compris). */
    fun supplyLine(s: Supply): Purchase = Purchase(
        Instant.ofEpochMilli(s.boughtAt).atZone(ZONE).toLocalDate(), s.supplier, "", s.amountCents, 0, 0, s.quantity,
        "Fournitures", s.label + " (" + s.kind.label.lowercase() + ")",
    )

    /** Registre des achats de l'année : date, fournisseur, nature, détail, n° de commande, quantité, montants. */
    fun purchasesCsv(orders: List<Purchase>, year: Int): String = buildString {
        append('\uFEFF')
        appendLine("Date;Fournisseur;Nature;Détail;N° de commande;Quantité;Prix (€);Port (€);Frais (€);Total (€)")
        orders.filter { it.date.year == year }.sortedBy { it.date }.forEach { o ->
            append(listOf(
                csv(o.date.format(DATE)), csv(o.supplier), csv(o.nature), csv(o.detail), csv(o.ref), o.pieces.toString(),
                euros(o.itemsCents), euros(o.shippingCents), euros(o.feesCents), euros(o.itemsCents + o.shippingCents + o.feesCents),
            ).joinToString(";")).append('\n')
        }
    }
}
