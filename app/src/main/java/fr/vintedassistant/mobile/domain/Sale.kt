package fr.vintedassistant.mobile.domain

import java.math.BigDecimal
import java.math.RoundingMode

enum class ShippingStatus(val label: String) {
    TO_SHIP("À expédier"),
    SHIPPED("Expédiée"),
    DELIVERED("Livrée"),
    CANCELLED("Annulée");

    companion object {
        fun fromLabel(label: String): ShippingStatus = entries.firstOrNull { it.label == label } ?: TO_SHIP
    }
}

/**
 * Vente enregistrée. Le titre, la catégorie et le coût sont copiés au moment de la vente :
 * l'historique reste juste même si l'article est modifié ou supprimé ensuite.
 */
data class Sale(
    val id: Long,
    val articleId: Long?,
    val title: String,
    val category: String,
    val salePriceCents: Long,
    val costCents: Long,
    val feesCents: Long,
    val soldAt: Long,
    val listedAt: Long?,
    val buyer: String = "",
    val shippingStatus: ShippingStatus = ShippingStatus.TO_SHIP,
    val labelPath: String? = null,
    val notes: String = "",
) {
    val counted: Boolean get() = shippingStatus != ShippingStatus.CANCELLED
}

/** Calculs financiers. Taux exprimés en points de base : 1230 = 12,30 %. */
object Finance {
    const val MAX_RATE_BP = 10_000

    fun contributionsCents(saleCents: Long, rateBp: Int): Long =
        BigDecimal.valueOf(saleCents)
            .multiply(BigDecimal.valueOf(rateBp.toLong()))
            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
            .toLong()

    /** Bénéfice net = prix de vente − coût d'achat − frais − cotisations. */
    fun netCents(sale: Sale, rateBp: Int): Long =
        sale.salePriceCents - sale.costCents - sale.feesCents - contributionsCents(sale.salePriceCents, rateBp)

    /** « 12,3 » ou « 12,30 % » donne 1230. Null si invalide ou au-delà de 100 %. */
    fun parseRatePercent(text: String): Int? {
        val bp = Money.parseEuros(text.trim().removeSuffix("%").trim()) ?: return null
        return if (bp > MAX_RATE_BP) null else bp.toInt()
    }

    fun formatRate(bp: Int): String = Money.formatPlain(bp.toLong()) + " %"
}

sealed interface SaleValidation {
    data class Valid(val salePriceCents: Long, val feesCents: Long, val buyer: String) : SaleValidation
    data class Invalid(val message: String) : SaleValidation
}

fun validateSale(priceText: String, feesText: String, buyer: String): SaleValidation {
    val price = Money.parseEuros(priceText)
        ?: return SaleValidation.Invalid("Prix de vente invalide (exemple : 22).")
    val fees = if (feesText.isBlank()) 0L else Money.parseEuros(feesText)
        ?: return SaleValidation.Invalid("Frais invalides (exemple : 1,50).")
    return SaleValidation.Valid(price, fees, buyer.trim().take(Article.TITLE_MAX_LENGTH))
}
