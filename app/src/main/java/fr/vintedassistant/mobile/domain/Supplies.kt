package fr.vintedassistant.mobile.domain

import java.text.Normalizer

/** Famille de fourniture : ce qui sert à vendre sans être revendu (pochettes, étiquettes, fond photo…). */
enum class SupplyKind(val label: String) {
    PACKAGING("Emballage et envoi"),
    PHOTO("Photo et présentation"),
    TOOLS("Matériel"),
    OTHER("Autre");

    companion object {
        fun of(name: String): SupplyKind = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * Achat de fournitures : une dépense de l'activité qui n'entre pas dans le stock à vendre.
 * [perParcel] : il en part une dans chaque colis (pochette à bulles, carte de remerciement…), ce qui permet
 * d'estimer ce qu'il en reste à partir des ventes.
 */
data class Supply(
    val id: Long,
    val boughtAt: Long,
    val label: String,
    val kind: SupplyKind,
    val supplier: String,
    val quantity: Int,
    val amountCents: Long,
    val perParcel: Boolean,
    val note: String = "",
) {
    /** Prix d'une pièce, frais de port compris dans le montant payé. */
    val unitCents: Long get() = if (quantity > 0) (amountCents + quantity / 2) / quantity else amountCents
}

object Supplies {
    /** Seuil d'alerte : il reste de quoi faire quelques colis. */
    const val LOW = 5

    /** Stock estimé d'une fourniture qui part dans chaque colis. */
    data class Stock(val label: String, val bought: Int, val used: Int, val since: Long, val unitCents: Long) {
        val remaining: Int get() = (bought - used).coerceAtLeast(0)
        val low: Boolean get() = remaining <= LOW
    }

    private fun key(label: String): String =
        Normalizer.normalize(label.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * Pour chaque fourniture « une par colis » (regroupée par nom) : achetées, utilisées depuis le premier achat
     * (une par vente comptée et datée, annulations exclues), et ce qu'il reste. Estimation : une vente sans colis
     * (remise en main propre) n'en consomme pas en vrai.
     */
    fun stocks(supplies: List<Supply>, sales: List<Sale>): List<Stock> =
        supplies.filter { it.perParcel && it.quantity > 0 }.groupBy { key(it.label) }.values.map { group ->
            val since = group.minOf { it.boughtAt }
            val bought = group.sumOf { it.quantity }
            val used = sales.count { it.counted && it.dateKnown && it.soldAt >= since }.coerceAtMost(bought)
            val unit = (group.sumOf { it.amountCents } + bought / 2) / bought
            Stock(group.maxBy { it.boughtAt }.label, bought, used, since, unit)
        }.sortedBy { it.remaining }

    /** Dépenses de fournitures entre deux instants (début inclus, fin exclue). */
    fun spentCents(supplies: List<Supply>, from: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE): Long =
        supplies.filter { it.boughtAt in from until until }.sumOf { it.amountCents }

    /** Phrase d'alerte quand une fourniture de colis s'épuise, null sinon. */
    fun alert(stocks: List<Stock>): String? {
        val low = stocks.filter { it.low }
        if (low.isEmpty()) return null
        return low.joinToString(" ; ") { s ->
            if (s.remaining == 0) "${s.label} : plus rien en stock d'après les ventes"
            else "${s.label} : environ ${s.remaining} restante${if (s.remaining > 1) "s" else ""}"
        }
    }
}
