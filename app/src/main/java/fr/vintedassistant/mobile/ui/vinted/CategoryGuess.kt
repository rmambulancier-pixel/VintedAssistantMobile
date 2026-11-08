package fr.vintedassistant.mobile.ui.vinted

import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.StockLedger
import org.json.JSONObject

/**
 * Catégorie Vinted probable d'une fiche de bijou : type (le premier mot reconnu dans le titre ou la
 * catégorie déjà saisie) et genre (« homme » dans le texte, sinon femmes). Sert de requête au champ
 * « Trouver une catégorie » de Vinted ; le script choisit ensuite parmi les résultats réels.
 */
data class CategoryGuess(val query: String, val leaves: List<String>, val gender: String, val avoid: List<String>) {
    companion object {
        private val TYPES = listOf(
            listOf("bracelet", "jonc") to ("bracelet" to listOf("bracelet")),
            listOf("collier", "chaine", "chapelet", "ras de cou", "pendentif", "gourmette") to ("collier" to listOf("collier", "pendentif")),
            listOf("bague", "anneau") to ("bague" to listOf("bague")),
            listOf("boucle", "creole") to ("boucles d'oreilles" to listOf("boucle")),
            listOf("broche") to ("broche" to listOf("broche")),
            listOf("montre") to ("montre" to listOf("montre")),
            listOf("cheville") to ("bijoux de cheville" to listOf("cheville")),
        )

        fun of(a: Article): CategoryGuess {
            val text = StockLedger.normalize(a.category + " " + a.title)
            val type = TYPES.mapNotNull { (words, target) ->
                words.mapNotNull { w -> Regex("\\b" + Regex.escape(w)).find(text)?.range?.first }.minOrNull()?.let { it to target }
            }.minByOrNull { it.first }?.second ?: ("bijoux" to listOf("bijou"))
            val men = Regex("\\bhomme").containsMatchIn(text)
            val gender = if (men) "hommes" else "femmes"
            return CategoryGuess(type.first, type.second, gender, listOf("enfant", "fille", "garcon", if (men) "femmes" else "hommes"))
        }

        fun summary(result: JSONObject?): String {
            if (result == null) return "Catégorie : pas de réponse de la page, choisis-la à la main."
            val chosen = result.optString("chosen")
            return when {
                result.optBoolean("ok") -> "Catégorie choisie" + (if (chosen.isNotBlank()) " : $chosen" else "") + ". Vérifie, puis touche « Ajouter » toi-même."
                else -> "Catégorie non choisie (${result.optString("error", "raison inconnue")}). Choisis-la à la main ; le détail s'affiche pour me le transmettre."
            }
        }
    }
}
