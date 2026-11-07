package fr.vintedassistant.mobile.data.importer

import fr.vintedassistant.mobile.domain.Money
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Annonce lue dans « listings/index.html » de l'export Vinted (en ligne ou vendue : l'export ne le dit pas). */
data class ExportListing(
    /** Numéro de l'annonce Vinted, tiré du dossier de ses photos (« photos/9939132376/… »). */
    val itemId: Long?,
    val title: String,
    val description: String,
    val brand: String,
    val size: String,
    val condition: String,
    val color: String,
    val views: Int?,
    val favorites: Int?,
    /** Date réelle de mise en ligne. */
    val createdAt: Long?,
    val priceCents: Long?,
    /** Chemins des photos dans l'archive (« listings/photos/<id>/<fichier>.webp »). */
    val photoPaths: List<String>,
) {
    val url: String get() = itemId?.let { "https://www.vinted.fr/items/$it" }.orEmpty()
}

/** Article d'une commande : titre et prix, quand Vinted les fournit. */
data class ExportOrderItem(val title: String, val priceCents: Long?)

/**
 * Commande lue dans « orders/index.html ». Vinted n'indique ni l'acheteur ni le vendeur :
 * pour une boutique, ce sont les ventes ; les commandes antérieures à la première annonce sont ignorées.
 */
data class ExportOrder(
    val orderedAt: Long?,
    val status: String,
    /** Montant de la commande (ce que l'acheteur a payé pour les articles, réductions déduites). */
    val amountCents: Long?,
    val items: List<ExportOrderItem>,
) {
    val cancelled: Boolean get() = Regex("annul|rembours|cancel|refund", RegexOption.IGNORE_CASE).containsMatchIn(status)
}

/**
 * Lecture des pages HTML de l'export RGPD de Vinted (format observé en novembre 2026) :
 * chaque fiche est un bloc `<div class="cell" itemscope>` dont les valeurs portent un attribut `itemprop`.
 */
object VintedHtmlExport {
    private val CELL = "<div class=\"cell\" itemscope"
    private val PROP = Regex("""<(span|div)\b[^>]*\bitemprop="([a-z_]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val PHOTO = Regex("""(?:src|href)="(photos/(\d+)/[^"]+)"""")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xx")

    fun listings(html: String, folder: String = "listings"): List<ExportListing> = cells(html).mapNotNull { cell ->
        val p = props(cell)
        val title = p["title"]?.firstOrNull()?.let(::oneLine).orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val photos = PHOTO.findAll(cell).map { it.groupValues[1] }.distinct().toList()
        ExportListing(
            itemId = PHOTO.find(cell)?.groupValues?.get(2)?.toLongOrNull(),
            title = title,
            description = p["description"]?.firstOrNull()?.let(::multiLine).orEmpty(),
            brand = p["brand"]?.firstOrNull()?.let(::oneLine).orEmpty(),
            size = p["size"]?.firstOrNull()?.let(::oneLine).orEmpty(),
            condition = p["status"]?.firstOrNull()?.let(::oneLine).orEmpty(),
            color = p["color"]?.firstOrNull()?.let(::oneLine).orEmpty(),
            views = p["views_count"]?.firstOrNull()?.let(::oneLine)?.toIntOrNull(),
            favorites = p["favourite_count"]?.firstOrNull()?.let(::oneLine)?.toIntOrNull(),
            createdAt = p["created_at"]?.firstOrNull()?.let { date(oneLine(it)) },
            priceCents = p["order_value"]?.firstOrNull()?.let { money(oneLine(it)) },
            photoPaths = photos.map { "$folder/$it" },
        )
    }

    fun orders(html: String): List<ExportOrder> = cells(html).mapNotNull { cell ->
        val p = props(cell)
        val at = p["order_purchased"]?.firstOrNull()?.let { date(oneLine(it)) }
        val amount = p["order_value"]?.firstOrNull()?.let { money(oneLine(it)) }
        if (at == null && amount == null) return@mapNotNull null
        val titles = p["item_title"].orEmpty().map(::oneLine)
        val prices = p["item_price"].orEmpty().map { money(oneLine(it)) }
        ExportOrder(
            orderedAt = at,
            status = p["status"]?.firstOrNull()?.let(::oneLine).orEmpty(),
            amountCents = amount,
            items = titles.mapIndexedNotNull { i, t -> if (t.isBlank()) null else ExportOrderItem(t, prices.getOrNull(i)) },
        )
    }

    private fun cells(html: String): List<String> = html.split(CELL).drop(1)

    private fun props(cell: String): Map<String, List<String>> =
        PROP.findAll(cell).groupBy({ it.groupValues[2] }, { it.groupValues[3] })

    private fun decode(s: String): String = s.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")

    private fun oneLine(s: String): String = decode(s).replace(Regex("\\s+"), " ").trim()
        .takeUnless { it.equals("Aucune donnée", ignoreCase = true) || it.startsWith("Aucune donnée ") }.orEmpty()

    private fun multiLine(s: String): String = decode(s).lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n").trim()

    internal fun date(s: String): Long? = try {
        OffsetDateTime.parse(s.trim(), DATE).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    internal fun money(s: String): Long? {
        val n = Regex("""\d{1,6}(?:[.,]\d{1,2})?""").find(s)?.value ?: return null
        return Money.parseEuros(n)
    }
}
