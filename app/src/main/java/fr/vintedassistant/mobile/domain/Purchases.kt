package fr.vintedassistant.mobile.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Ligne de commande fournisseur : prix unitaire payé et quantité. */
data class PurchaseLine(val unitPriceCents: Long, val quantity: Int)

/** Produit acheté, avec son coût de revient unitaire (prix + part des frais) et le nombre déjà rattaché à des fiches. */
data class PurchaseItem(
    val id: Long,
    val orderId: Long,
    val name: String,
    val imageFile: String?,
    val unitPriceCents: Long,
    val quantity: Int,
    val landedUnitCents: Long,
    val linkedCount: Int,
    /** Adresse de la photo chez le fournisseur : sert à reconnaître le même bijou d'une commande à l'autre. */
    val imageUrl: String = "",
    /** Code produit du fournisseur, vide s'il n'a pas été lu. */
    val sku: String = "",
    /** Pièces jamais reçues : elles ne comptent pas dans le stock. */
    val missing: Int = 0,
) {
    /** Pièces réellement reçues (ou attendues tant que la commande est en livraison). */
    val delivered: Int get() = (quantity - missing).coerceAtLeast(0)
    val remaining: Int get() = (delivered - linkedCount).coerceAtLeast(0)
}

data class PurchaseOrder(
    val id: Long,
    val supplier: String,
    val orderRef: String,
    val orderedAt: Long,
    val itemsTotalCents: Long,
    val shippingCents: Long,
    val feesCents: Long,
    val items: List<PurchaseItem>,
    /** Faux tant que la commande est en cours de livraison : ses pièces ne sont pas encore vendables. */
    val received: Boolean = true,
    val deliveryEta: String = "",
) {
    val totalCents: Long get() = itemsTotalCents + shippingCents + feesCents
}

object LandedCost {
    /**
     * Coût de revient unitaire de chaque ligne : prix unitaire + part des frais (port, service, taxes)
     * répartie au prorata de la valeur de la ligne. Si toutes les lignes valent 0, répartition par pièce.
     */
    fun allocate(lines: List<PurchaseLine>, extraCents: Long): List<Long> {
        val units = lines.sumOf { it.quantity.coerceAtLeast(1).toLong() }
        val value = lines.sumOf { it.unitPriceCents * it.quantity.coerceAtLeast(1) }
        return lines.map { line ->
            val qty = line.quantity.coerceAtLeast(1)
            val shareOfLine = when {
                extraCents == 0L -> BigDecimal.ZERO
                value > 0 -> BigDecimal.valueOf(extraCents).multiply(BigDecimal.valueOf(line.unitPriceCents * qty))
                    .divide(BigDecimal.valueOf(value), 6, RoundingMode.HALF_UP)
                else -> BigDecimal.valueOf(extraCents).multiply(BigDecimal.valueOf(qty.toLong()))
                    .divide(BigDecimal.valueOf(units), 6, RoundingMode.HALF_UP)
            }
            val perUnit = shareOfLine.divide(BigDecimal.valueOf(qty.toLong()), 0, RoundingMode.HALF_UP).toLong()
            // Une remise peut baisser le coût, jamais le rendre négatif.
            (line.unitPriceCents + perUnit).coerceAtLeast(0)
        }
    }
}
