package fr.vintedassistant.mobile.data.importer

import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.VintedLinks
import org.json.JSONArray
import org.json.JSONObject

/** Annonce relevée dans une page Vinted ouverte par l'utilisateur (dressing ou page d'annonce). */
data class ImportedListing(
    val itemId: Long,
    val url: String,
    val title: String,
    val priceCents: Long?,
    val description: String = "",
    val brand: String = "",
    val size: String = "",
    val condition: String = "",
    val color: String = "",
    val category: String = "",
    /** Texte « Ajouté » de Vinted (« Il y a une semaine »), pour estimer la date de mise en ligne. */
    val addedText: String = "",
    val favorites: Int? = null,
    val views: Int? = null,
    val imageUrls: List<String> = emptyList(),
    /** Vrai si la page de l'annonce elle-même a été lue (fiche complète). */
    val detailed: Boolean = false,
)

/**
 * Lecture du JSON renvoyé par les scripts de page. Tout est validé ici : liens Vinted uniquement,
 * images sur les serveurs d'images Vinted uniquement, textes bornés.
 */
object PageImport {
    private const val MAX_IMAGES = 20

    /** Images autorisées : https, serveurs Vinted (ex. images1.vinted.net). */
    fun isAllowedImage(url: String): Boolean = try {
        val uri = java.net.URI(url)
        uri.scheme == "https" && Regex("""(^|\.)vinted\.(net|[a-z.]{2,6})$""").containsMatchIn(uri.host.orEmpty().lowercase())
    } catch (e: Exception) {
        false
    }

    /** Page dressing : {"items":[{"url","title","price","favorites","views","image"}]}. */
    fun parseGrid(json: String): List<ImportedListing> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        val result = LinkedHashMap<Long, ImportedListing>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val url = o.optString("url").substringBefore('?')
            val id = VintedLinks.itemId(url) ?: continue
            if (result.containsKey(id)) continue
            val title = cleanTitle(o.optString("title")) ?: VintedLinks.titleFromUrl(url) ?: continue
            result[id] = ImportedListing(
                itemId = id,
                url = url,
                title = title,
                priceCents = price(o.optString("price")),
                favorites = count(o.optString("favorites")),
                views = count(o.optString("views")),
                imageUrls = listOf(o.optString("image")).filter(::isAllowedImage),
            )
        }
        return result.values.toList()
    }

    /** Page d'annonce : données structurées (JSON-LD, balises de partage) et détails affichés. */
    fun parseItem(json: String): ImportedListing? {
        val o = JSONObject(json)
        val url = o.optString("url").substringBefore('?')
        val id = VintedLinks.itemId(url) ?: return null
        val title = cleanTitle(o.optString("title")) ?: VintedLinks.titleFromUrl(url) ?: return null
        val images = mutableListOf<String>()
        o.optJSONArray("images")?.let { arr -> for (i in 0 until arr.length()) images += arr.optString(i) }
        return ImportedListing(
            itemId = id,
            url = url,
            title = title,
            priceCents = price(o.optString("price")),
            description = o.optString("description").trim().take(2000),
            brand = o.optString("brand").trim().take(120),
            size = o.optString("size").trim().take(120),
            condition = o.optString("condition").trim().take(120),
            color = o.optString("color").trim().take(120),
            category = o.optString("category").trim().take(120),
            addedText = o.optString("added").trim().take(60),
            favorites = count(o.optString("favorites")),
            views = count(o.optString("views")),
            imageUrls = images.map { it.trim() }.filter(::isAllowedImage).distinct().take(MAX_IMAGES),
            detailed = true,
        )
    }

    /** « 9,00 € », « 9.00 », « EUR 9 » → centimes. */
    internal fun price(raw: String): Long? {
        val n = Regex("""\d{1,6}(?:[.,]\d{1,2})?""").find(raw.replace(" ", " ").replace(" ", ""))?.value ?: return null
        return Money.parseEuros(n)
    }

    /** « 4 favoris », « 22 vues », « 4 » → nombre. */
    internal fun count(raw: String): Int? = Regex("""\d+""").find(raw)?.value?.toIntOrNull()

    private fun cleanTitle(raw: String): String? {
        // Les textes alternatifs d'image contiennent parfois « titre, marque : X, taille : Y » : on garde le titre.
        val t = raw.substringBefore(", marque").substringBefore(", état").substringBefore(" | Vinted").trim()
        return t.takeIf { it.length in 2..200 }
    }
}

/** Utilitaire pour les tests : assemble un JSON de dressing. */
internal fun gridJson(vararg items: JSONObject): String = JSONObject().put("items", JSONArray(items.toList())).toString()
