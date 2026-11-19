package fr.vintedassistant.mobile.data

import fr.vintedassistant.mobile.domain.StockLedger
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fichier de corrections préparé à partir d'un export (dates de commande, fiches à supprimer,
 * rattachements à poser ou déplacer). Chaque étape vérifie le titre de la fiche visée : si la fiche
 * ne correspond plus, l'étape est écartée. Rien n'est appliqué sans confirmation de l'utilisateur.
 */
class Corrections(
    private val articles: ArticleRepository,
    private val purchases: PurchaseRepository,
) {
    class Step(val label: String, val problem: String?, internal val action: suspend () -> Unit) {
        val ok: Boolean get() = problem == null
    }

    /** Lit le fichier et prépare les étapes, dans l'ordre du fichier, en simulant leur effet sur le stock. */
    suspend fun plan(json: String): List<Step> {
        val root = JSONObject(json)
        require(root.optString("format") == "vinted-assistant-corrections") { "Ce n'est pas un fichier de corrections" }
        val orders = purchases.orders.first()
        val items = orders.flatMap { o -> o.items.map { o to it } }
        val used = items.associate { (_, i) -> i.id to i.linkedCount }.toMutableMap()
        val linkOf = HashMap<Long, Long?>()
        val steps = mutableListOf<Step>()

        root.optJSONArray("orderDates")?.let { list ->
            for (k in 0 until list.length()) {
                val e = list.getJSONObject(k)
                val ref = e.getString("ref")
                val day = runCatching { LocalDate.parse(e.getString("date")) }.getOrNull()
                val order = orders.firstOrNull { it.orderRef == ref }
                val label = "Commande $ref : date au ${day ?: e.optString("date")}"
                steps += when {
                    order == null -> Step(label, "commande introuvable") {}
                    day == null -> Step(label, "date illisible") {}
                    else -> Step(label, null) {
                        purchases.setOrderedAt(order.id, day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                }
            }
        }

        suspend fun currentLink(articleId: Long): Long? =
            if (linkOf.containsKey(articleId)) linkOf[articleId] else articles.getArticle(articleId)?.purchaseItemId

        root.optJSONArray("deleteArticles")?.let { list ->
            for (k in 0 until list.length()) {
                val e = list.getJSONObject(k)
                val id = e.getLong("id")
                val expected = e.optString("title")
                val a = articles.getArticle(id)
                val label = "Supprimer la fiche #$id « ${expected.take(60)} »"
                steps += when {
                    a == null -> Step(label, "fiche introuvable") {}
                    !titleMatches(a.title, expected) -> Step(label, "la fiche #$id s'appelle « ${a.title.take(50)} »") {}
                    e.has("status") && a.status.label != e.getString("status") -> Step(label, "statut « ${a.status.label} » au lieu de « ${e.getString("status")} »") {}
                    else -> {
                        currentLink(id)?.let { used[it] = (used[it] ?: 1) - 1 }
                        linkOf[id] = null
                        Step(label, null) { articles.delete(id, allowSold = true) }
                    }
                }
            }
        }

        root.optJSONArray("links")?.let { list ->
            for (k in 0 until list.length()) {
                val e = list.getJSONObject(k)
                val id = e.getLong("articleId")
                val expected = e.optString("title")
                val ref = e.getString("orderRef")
                val sku = e.getString("sku")
                val a = articles.getArticle(id)
                val target = items.firstOrNull { (o, i) -> o.orderRef == ref && i.sku == sku }
                val label = "Fiche #$id « ${expected.take(50)} » → ${target?.second?.name?.let(::variant) ?: sku}, commande $ref"
                val step = when {
                    a == null -> Step(label, "fiche introuvable") {}
                    !titleMatches(a.title, expected) -> Step(label, "la fiche #$id s'appelle « ${a.title.take(50)} »") {}
                    target == null -> Step(label, "produit $sku introuvable dans la commande $ref") {}
                    currentLink(id) == target.second.id -> Step(label, "déjà fait") {}
                    (used[target.second.id] ?: 0) >= target.second.quantity -> Step(label, "plus de pièce libre pour ce produit") {}
                    else -> {
                        currentLink(id)?.let { used[it] = (used[it] ?: 1) - 1 }
                        used[target.second.id] = (used[target.second.id] ?: 0) + 1
                        linkOf[id] = target.second.id
                        val itemId = target.second.id
                        Step(label, null) { purchases.link(id, itemId) }
                    }
                }
                steps += step
            }
        }
        // Ventes à déplacer vers la bonne fiche (vente rattachée à une autre fiche du même bijou).
        root.optJSONArray("moveSales")?.let { list ->
            for (k in 0 until list.length()) {
                val e = list.getJSONObject(k)
                val saleId = e.getLong("saleId")
                val to = e.getLong("toArticleId")
                val sale = articles.getSale(saleId)
                val target = articles.getArticle(to)
                val label = "Vente du ${e.optString("date")} « ${e.optString("title").take(50)} » → fiche #$to"
                steps += when {
                    sale == null -> Step(label, "vente introuvable") {}
                    !titleMatches(sale.title, e.optString("title")) -> Step(label, "la vente s'appelle « ${sale.title.take(50)} »") {}
                    sale.articleId == to -> Step(label, "déjà fait") {}
                    target == null -> Step(label, "fiche #$to introuvable") {}
                    !titleMatches(target.title, e.optString("toTitle")) -> Step(label, "la fiche #$to s'appelle « ${target.title.take(50)} »") {}
                    else -> Step(label, null) { articles.moveSale(saleId, to) }
                }
            }
        }

        // Ventes en double à supprimer (vérifiées par titre et prix).
        root.optJSONArray("deleteSales")?.let { list ->
            for (k in 0 until list.length()) {
                val e = list.getJSONObject(k)
                val saleId = e.getLong("id")
                val sale = articles.getSale(saleId)
                val label = "Supprimer la vente en double « ${e.optString("title").take(50)} » (${e.optString("reason")})"
                steps += when {
                    sale == null -> Step(label, "déjà supprimée") {}
                    !titleMatches(sale.title, e.optString("title")) -> Step(label, "la vente s'appelle « ${sale.title.take(50)} »") {}
                    e.has("priceCents") && sale.salePriceCents != e.getLong("priceCents") -> Step(label, "prix différent (${sale.salePriceCents / 100.0} €)") {}
                    else -> Step(label, null) { articles.deleteSale(saleId) }
                }
            }
        }
        return steps
    }

    /** Applique les étapes valides dans l'ordre. Renvoie le nombre appliqué. */
    suspend fun apply(steps: List<Step>): Int {
        var n = 0
        for (s in steps) if (s.ok) { s.action(); n++ }
        return n
    }

    private fun titleMatches(actual: String, expected: String): Boolean =
        expected.isBlank() || StockLedger.normalize(actual).startsWith(StockLedger.normalize(expected))

    private fun variant(name: String): String =
        Regex("""\(([^()]*(?:\([^()]*\))?[^()]*)\)\s*$""").find(name)?.groupValues?.get(1)?.let { "[$it]" } ?: name.take(40)
}
