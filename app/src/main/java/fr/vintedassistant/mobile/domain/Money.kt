package fr.vintedassistant.mobile.domain

import java.math.BigDecimal

/**
 * Montants manipulés en centimes (Long) : aucun arrondi flottant sur l'argent.
 */
object Money {
    /** Plafond de saisie : 1 000 000 €. */
    const val MAX_CENTS: Long = 100_000_000L

    private val AMOUNT = Regex("""\d+(\.\d{0,2})?""")

    /**
     * Convertit une saisie utilisateur en centimes.
     * Accepte la virgule ou le point, les espaces et le symbole €.
     * Renvoie null si la saisie est vide, négative, a plus de deux décimales ou dépasse le plafond.
     */
    fun parseEuros(input: String): Long? {
        val cleaned = input.trim()
            .removeSuffix("€")
            .replace(" ", "")
            .replace(" ", "")
            .replace(" ", "")
            .replace(',', '.')
        if (cleaned.isEmpty() || !AMOUNT.matches(cleaned)) return null
        val cents = BigDecimal(cleaned).movePointRight(2)
        // Comparaison avant conversion : toLong() déborderait en silence sur un nombre géant.
        return if (cents > BigDecimal.valueOf(MAX_CENTS)) null else cents.toLong()
    }

    /** Montant pour un champ de saisie : « 12,5 » devient « 12,50 », « 12,00 » devient « 12 ». */
    fun formatPlain(cents: Long): String {
        val euros = cents / 100
        val rest = cents % 100
        return if (rest == 0L) euros.toString() else euros.toString() + "," + rest.toString().padStart(2, '0')
    }

    /**
     * Affiche des centimes à la française : « 1 234,56 € »
     * (espace fine insécable pour les milliers, espace insécable avant €).
     */
    fun formatEuros(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val euros = (abs / 100).toString()
        val decimals = (abs % 100).toString().padStart(2, '0')
        val grouped = euros.reversed().chunked(3).joinToString(" ").reversed()
        return (if (negative) "-" else "") + grouped + "," + decimals + " €"
    }
}
