package fr.vintedassistant.mobile.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Contrôle de cohérence des données, lisible par l'utilisateur et transmissible pour un diagnostic.
 * Rien n'est corrigé ici : le rapport dit seulement ce qui mérite d'être vérifié.
 */
object DataAudit {

    data class Finding(val title: String, val lines: List<String>)

    fun findings(articles: List<Article>, sales: List<Sale>, stock: StockOverview?): List<Finding> {
        val out = mutableListOf<Finding>()
        val byItem = articles.filter { VintedLinks.itemId(it.vintedUrl) != null }.groupBy { VintedLinks.itemId(it.vintedUrl)!! }
        byItem.filter { it.value.size > 1 }.takeIf { it.isNotEmpty() }?.let { dup ->
            out += Finding(
                "Vrais doublons : plusieurs fiches pour la même annonce Vinted",
                dup.map { (id, list) -> "annonce $id : fiches ${list.joinToString { "#${it.id} « ${it.title.take(50)} » (${it.status.label})" }}" },
            )
        }
        val soldWithoutSale = articles.filter { it.status == ArticleStatus.SOLD && sales.none { s -> s.articleId == it.id } }
        if (soldWithoutSale.isNotEmpty()) {
            out += Finding("Fiches « Vendu » sans vente enregistrée", soldWithoutSale.map { "#${it.id} « ${it.title.take(60)} »" })
        }
        val multiSales = sales.filter { it.articleId != null }.groupBy { it.articleId!! }.filter { it.value.size > 1 }
        if (multiSales.isNotEmpty()) {
            out += Finding("Fiches avec plusieurs ventes", multiSales.map { (id, list) -> "fiche #$id : ${list.size} ventes" })
        }
        val undated = sales.filter { !it.dateKnown }
        if (undated.isNotEmpty()) {
            out += Finding("Ventes sans date (comptées depuis le début, pas dans le mois)", undated.map { "« ${it.title.take(60)} » ${Money.formatEuros(it.salePriceCents)}" })
        }
        val onlineNoLink = articles.filter { (it.status == ArticleStatus.ONLINE || it.status == ArticleStatus.RESERVED) && it.vintedUrl.isBlank() }
        if (onlineNoLink.isNotEmpty()) {
            out += Finding("Fiches « En ligne » sans lien vers l'annonce Vinted", onlineNoLink.map { "#${it.id} « ${it.title.take(60)} »" })
        }
        val noCost = articles.filter { it.costCents == 0L && it.status != ArticleStatus.WITHDRAWN }
        if (noCost.isNotEmpty()) {
            out += Finding("Fiches sans coût d'achat (marges surestimées)", noCost.map { "#${it.id} « ${it.title.take(60)} » (${it.status.label})" })
        }
        stock?.products?.filter { it.overLinked }?.takeIf { it.isNotEmpty() }?.let { list ->
            out += Finding("Achats avec plus de fiches que de pièces", list.map { "« ${it.name.take(60)} » : ${it.articles.size} fiches pour ${it.bought} pièce(s)" })
        }
        return out
    }

    fun report(
        appVersion: String,
        now: Long,
        articles: List<Article>,
        sales: List<Sale>,
        stock: StockOverview?,
        zone: ZoneId = ZoneId.of("Europe/Paris"),
    ): String = buildString {
        val date = Instant.ofEpochMilli(now).atZone(zone)
        appendLine("Vinted Assistant $appVersion : rapport du ${date.toLocalDate()} à ${date.toLocalTime().withNano(0)}")
        appendLine("Aucun pseudo d'acheteur, aucun message, aucune adresse dans ce fichier.")
        appendLine()
        appendLine("FICHES (${articles.size})")
        ArticleStatus.entries.forEach { s -> articles.count { it.status == s }.takeIf { it > 0 }?.let { appendLine("- ${s.label} : $it") } }
        appendLine()
        val dated = sales.filter { it.dateKnown }
        appendLine("VENTES (${sales.size}) : ${Money.formatEuros(sales.sumOf { it.salePriceCents })}")
        appendLine("- datées : ${dated.size}, sans date : ${sales.size - dated.size}")
        dated.groupBy { Instant.ofEpochMilli(it.soldAt).atZone(zone).toLocalDate().withDayOfMonth(1) }.toSortedMap().forEach { (month, list) ->
            appendLine("- ${month.year}-${month.monthValue.toString().padStart(2, '0')} : ${list.size} vente(s), ${Money.formatEuros(list.sumOf { it.salePriceCents })}")
        }
        if (stock != null && stock.bought > 0) {
            appendLine()
            appendLine("STOCK (pièces achetées : ${stock.bought})")
            appendLine("- en cours de livraison : ${stock.inTransit}")
            appendLine("- reçues, pas encore en vente : ${stock.notListed}")
            appendLine("- en brouillon : ${stock.drafts}")
            appendLine("- en ligne ou réservées : ${stock.forSale}")
            appendLine("- vendues : ${stock.sold}")
            appendLine("- fiches sans achat rattaché : ${stock.unlinked.size}")
        }
        val findings = findings(articles, sales, stock)
        appendLine()
        appendLine(if (findings.isEmpty()) "POINTS À VÉRIFIER : aucun" else "POINTS À VÉRIFIER (${findings.size})")
        findings.forEach { f ->
            appendLine()
            appendLine("${f.title} (${f.lines.size})")
            f.lines.forEach { appendLine("  - $it") }
        }
    }
}
