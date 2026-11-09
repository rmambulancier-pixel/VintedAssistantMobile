package fr.vintedassistant.mobile.ui.vinted

import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.StockLedger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Valeurs à cocher dans les détails Vinted d'une fiche (relevés le 8 novembre 2026 : « Pour qui ? »,
 * Marque, État, Couleur, Matériau). Chaque champ reçoit une liste de valeurs candidates, de la plus précise
 * à la plus générale ; le script coche la première qui existe vraiment dans le menu.
 */
object DetailsGuess {
    private val COLORS = listOf(
        "dore" to "Doré", "or " to "Doré", "argente" to "Argenté", "argent" to "Argenté", "noir" to "Noir",
        "rose" to "Rose", "rouge" to "Rouge", "vert" to "Vert", "emeraude" to "Vert", "bleu" to "Bleu",
        "blanc" to "Blanc", "transparent" to "Blanc", "violet" to "Violet", "fuchsia" to "Rose", "multicolore" to "Multicolore",
    )

    fun fields(a: Article): JSONArray {
        val text = " " + StockLedger.normalize(a.title + " " + a.description.take(300)) + " "
        val men = Regex("\\bhomme").containsMatchIn(text)
        val unisex = text.contains("unisexe")
        val who = when {
            unisex -> listOf("Unisexe", "Mixte", if (men) "Hommes" else "Femmes")
            men -> listOf("Hommes", "Homme")
            else -> listOf("Femmes", "Femme")
        }
        val brand = a.brand.ifBlank { "Sans marque" }
        val condition = a.condition.ifBlank { "Neuf sans étiquette" }
        val colors = (if (a.color.isNotBlank()) listOf(a.color) else emptyList()) +
            COLORS.filter { text.contains(" " + it.first) || text.contains(it.first) }.map { it.second }
        val materials = buildList {
            if (text.contains("acier")) { add("Acier inoxydable"); add("Acier") }
            if (text.contains("plaque or") || text.contains("plaque")) add("Plaqué or")
            if (text.contains("cuivre")) add("Cuivre")
            add("Métal")
        }
        return JSONArray()
            .put(field("Pour qui", "pour qui", who, max = 1, search = false))
            .put(field("Marque", "marque", listOf(brand, "Sans marque").distinct(), max = 1, search = true, exact = true))
            .put(field("État", "etat", listOf(condition), max = 1, search = false, loose = true))
            .put(field("Couleur", "couleur", colors.distinct().ifEmpty { listOf("Multicolore") }, max = 2, search = false))
            .put(field("Matériau", "materiau|matiere", materials.distinct(), max = 1, search = true))
    }

    private fun field(name: String, label: String, values: List<String>, max: Int, search: Boolean, loose: Boolean = false, exact: Boolean = false) =
        JSONObject().put("name", name).put("label", label).put("values", JSONArray(values))
            .put("max", max).put("search", search).put("loose", loose).put("exact", exact)

    fun hasGaps(result: JSONObject): Boolean {
        val f = result.optJSONArray("fields") ?: return true
        return (0 until f.length()).any { f.getJSONObject(it).optString("status") !in setOf("rempli", "déjà rempli", "absent") }
    }

    fun summary(result: JSONObject?): String {
        if (result == null) return "Catégorie choisie. Détails : pas de réponse de la page, complète-les à la main."
        val f = result.optJSONArray("fields") ?: JSONArray()
        val done = mutableListOf<String>()
        val todo = mutableListOf<String>()
        for (i in 0 until f.length()) {
            val o = f.getJSONObject(i)
            when (o.optString("status")) {
                "rempli", "déjà rempli" -> done += o.optString("field")
                "absent" -> Unit
                else -> todo += o.optString("field")
            }
        }
        return buildString {
            append("Tout est prêt sauf la publication.")
            if (done.isNotEmpty()) append(" Rempli : ${done.joinToString(", ")}.")
            if (todo.isNotEmpty()) append(" À compléter à la main : ${todo.joinToString(", ")}.")
            append(" Vérifie l'annonce, puis touche « Ajouter » toi-même.")
        }
    }
}
