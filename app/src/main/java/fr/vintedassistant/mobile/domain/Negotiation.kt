package fr.vintedassistant.mobile.domain

enum class OfferDecision { ACCEPT, COUNTER }

data class NegotiationAdvice(
    val decision: OfferDecision,
    /** Montant à accepter ou à proposer. */
    val amountCents: Long,
    /** Prix plancher réellement utilisé pour le calcul. */
    val floorUsedCents: Long,
    /** Explication pour le vendeur. */
    val explanation: String,
    /** Message prêt à copier dans la conversation Vinted. */
    val messageToBuyer: String,
)

object Negotiator {
    /** Sans prix plancher saisi, on protège 85 % du prix affiché. */
    const val DEFAULT_FLOOR_PERCENT = 85

    fun effectiveFloor(listedCents: Long, floorCents: Long?): Long =
        floorCents ?: (listedCents * DEFAULT_FLOOR_PERCENT / 100)

    /**
     * Conseil face à une offre :
     * - au moins le prix plancher : accepter ;
     * - en dessous : contre-proposer le milieu entre l'offre et le prix affiché,
     *   arrondi à l'euro supérieur, jamais sous le plancher ni au-dessus du prix affiché.
     */
    fun advise(listedCents: Long, floorCents: Long?, offerCents: Long): NegotiationAdvice {
        val floor = effectiveFloor(listedCents, floorCents).coerceAtMost(listedCents)
        if (offerCents >= floor) {
            return NegotiationAdvice(
                decision = OfferDecision.ACCEPT,
                amountCents = offerCents.coerceAtMost(listedCents),
                floorUsedCents = floor,
                explanation = "L'offre atteint ton prix plancher (${Money.formatEuros(floor)}) : tu peux l'accepter.",
                messageToBuyer = "Bonjour ! C'est d'accord pour ${Money.formatEuros(offerCents.coerceAtMost(listedCents))}, " +
                    "j'accepte ton offre. Je l'envoie rapidement. Merci !",
            )
        }
        val midpoint = (offerCents + listedCents) / 2
        val roundedUp = ((midpoint + 99) / 100) * 100
        val counter = roundedUp.coerceIn(floor, listedCents)
        return NegotiationAdvice(
            decision = OfferDecision.COUNTER,
            amountCents = counter,
            floorUsedCents = floor,
            explanation = "L'offre est sous ton prix plancher (${Money.formatEuros(floor)}). " +
                "Contre-proposition conseillée : ${Money.formatEuros(counter)}.",
            messageToBuyer = "Bonjour ! Merci pour ton offre. Je ne peux pas descendre à " +
                "${Money.formatEuros(offerCents)}, mais je peux te le laisser à ${Money.formatEuros(counter)}. " +
                "Dis-moi si ça te va !",
        )
    }
}
