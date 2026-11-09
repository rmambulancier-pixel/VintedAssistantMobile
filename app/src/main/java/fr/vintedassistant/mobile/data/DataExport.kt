package fr.vintedassistant.mobile.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import fr.vintedassistant.mobile.domain.DataAudit
import fr.vintedassistant.mobile.domain.StockLedger
import fr.vintedassistant.mobile.domain.VintedLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export des données de l'application dans un ZIP partageable : `rapport.txt` (lisible, avec les points
 * à vérifier) et `donnees.json` (fiches, ventes, achats). Sans photos, sans pseudos d'acheteurs ni
 * contenu des notifications. Le fichier est écrit dans le cache privé et partagé via le menu Android.
 */
class DataExport(
    private val context: Context,
    private val db: VintedBoostDatabase,
    private val articles: ArticleRepository,
    private val purchases: PurchaseRepository,
) {
    suspend fun build(appVersion: String, now: Long = System.currentTimeMillis()): File {
        val articleList = articles.articles.first()
        val sales = articles.sales.first()
        val orders = purchases.orders.first()
        val stock = StockLedger.build(orders, articleList)
        val notices = db.vintedNotificationDao().getAll()
        val report = DataAudit.report(appVersion, now, articleList, sales, stock)

        val json = JSONObject()
            .put("format", "vinted-assistant-export")
            .put("version", 1)
            .put("app", appVersion)
            .put("exportedAt", now)
            .put("articles", JSONArray().apply {
                articleList.forEach { a ->
                    put(JSONObject()
                        .put("id", a.id).put("title", a.title).put("status", a.status.label)
                        .put("priceCents", a.priceCents).put("costCents", a.costCents).put("floorPriceCents", a.floorPriceCents ?: JSONObject.NULL)
                        .put("brand", a.brand).put("size", a.size).put("category", a.category)
                        .put("condition", a.condition).put("color", a.color)
                        .put("vintedItemId", VintedLinks.itemId(a.vintedUrl) ?: JSONObject.NULL)
                        .put("createdAt", a.createdAt)
                        .put("firstPublishedAt", a.firstPublishedAt ?: JSONObject.NULL)
                        .put("lastPublishedAt", a.lastPublishedAt ?: JSONObject.NULL)
                        .put("favorites", a.favoritesCount ?: JSONObject.NULL).put("views", a.viewsCount ?: JSONObject.NULL)
                        .put("purchaseItemId", a.purchaseItemId ?: JSONObject.NULL)
                        .put("photos", a.photos.size)
                        .put("descriptionLength", a.description.length).put("keywords", a.keywords))
                }
            })
            .put("sales", JSONArray().apply {
                sales.forEach { s ->
                    put(JSONObject()
                        .put("id", s.id).put("articleId", s.articleId ?: JSONObject.NULL).put("title", s.title)
                        .put("priceCents", s.salePriceCents).put("costCents", s.costCents).put("feesCents", s.feesCents)
                        .put("soldAt", s.soldAt).put("dateKnown", s.dateKnown).put("listedAt", s.listedAt ?: JSONObject.NULL)
                        .put("shipping", s.shippingStatus.label).put("hasLabel", s.labelPath != null))
                }
            })
            .put("purchaseOrders", JSONArray().apply {
                orders.forEach { o ->
                    put(JSONObject()
                        .put("id", o.id).put("supplier", o.supplier).put("ref", o.orderRef).put("orderedAt", o.orderedAt)
                        .put("itemsTotalCents", o.itemsTotalCents).put("shippingCents", o.shippingCents).put("feesCents", o.feesCents)
                        .put("received", o.received).put("deliveryEta", o.deliveryEta)
                        .put("items", JSONArray().apply {
                            o.items.forEach { i ->
                                put(JSONObject()
                                    .put("id", i.id).put("name", i.name).put("sku", i.sku).put("quantity", i.quantity)
                                    .put("unitPriceCents", i.unitPriceCents).put("landedUnitCents", i.landedUnitCents)
                                    .put("linked", i.linkedCount).put("missing", i.missing))
                            }
                        }))
                }
            })
            .put("notifications", JSONObject().apply {
                notices.groupBy { it.kind }.forEach { (kind, list) -> put(kind, list.size) }
            })

        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // un seul export à la fois
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date(now))
            val file = File(dir, "vinted-assistant-$stamp.zip")
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("rapport.txt"))
                zip.write(report.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("donnees.json"))
                zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            file
        }
    }

    /** Rattachements contenus dans un export de l'application (fichier ZIP choisi par l'utilisateur). */
    fun readLinks(zip: java.io.InputStream): List<PurchaseRepository.LinkBackup> {
        var json: String? = null
        java.util.zip.ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.substringAfterLast('/') == "donnees.json") {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) { val n = z.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    json = out.toString("UTF-8")
                    break
                }
            }
        }
        val root = JSONObject(json ?: throw IllegalArgumentException("Pas un export Vinted Assistant"))
        val items = HashMap<Long, Pair<String, JSONObject>>()
        val orders = root.optJSONArray("purchaseOrders") ?: JSONArray()
        for (i in 0 until orders.length()) {
            val o = orders.getJSONObject(i)
            val list = o.optJSONArray("items") ?: continue
            for (j in 0 until list.length()) list.getJSONObject(j).let { items[it.getLong("id")] = o.optString("ref") to it }
        }
        val out = mutableListOf<PurchaseRepository.LinkBackup>()
        val articles = root.optJSONArray("articles") ?: JSONArray()
        for (i in 0 until articles.length()) {
            val a = articles.getJSONObject(i)
            if (a.isNull("purchaseItemId")) continue
            val (ref, item) = items[a.getLong("purchaseItemId")] ?: continue
            out += PurchaseRepository.LinkBackup(a.getLong("id"), ref, item.optString("sku"), item.optString("name"))
        }
        return out
    }

    /** Menu de partage Android (Claude, e-mail, Drive…). */
    fun shareIntent(file: File, mime: String = "application/zip"): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Envoyer les données",
        )
    }
}
