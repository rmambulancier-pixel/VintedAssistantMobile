package fr.vintedassistant.mobile.domain

/**
 * Type de bijou déduit du titre, quand la catégorie n'est pas connue (l'export et le dressing Vinted ne la
 * donnent pas). Le premier mot reconnu l'emporte : « Collier … croix » est un collier, « Croix pendentif … »
 * un pendentif. Null si rien n'est reconnu.
 */
object JewelryCategory {
    private val TYPES = listOf(
        "Bracelets" to listOf("bracelet", "jonc", "gourmette de poignet"),
        "Colliers" to listOf("collier", "chaine", "chapelet", "ras de cou", "sautoir"),
        "Pendentifs" to listOf("pendentif", "medaille"),
        "Bagues" to listOf("bague", "anneau", "chevaliere"),
        "Boucles d'oreilles" to listOf("boucle", "creole", "puces d'oreilles"),
        "Bijoux de cheville" to listOf("cheville"),
        "Broches" to listOf("broche"),
        "Montres" to listOf("montre"),
    )

    fun guess(title: String): String? {
        val t = StockLedger.normalize(title)
        return TYPES.mapNotNull { (label, words) ->
            words.mapNotNull { w -> Regex("\\b" + Regex.escape(StockLedger.normalize(w))).find(t)?.range?.first }.minOrNull()?.let { it to label }
        }.minByOrNull { it.first }?.second
    }

    /** Catégorie à afficher : celle saisie, sinon celle déduite du titre, sinon « Sans catégorie ». */
    /** Une catégorie saisie comme « Tendance Colliers » est ramenée au type reconnu (« Colliers »). */
    fun of(category: String, title: String): String =
        if (category.isBlank()) guess(title) ?: "Sans catégorie" else guess(category) ?: category
}
