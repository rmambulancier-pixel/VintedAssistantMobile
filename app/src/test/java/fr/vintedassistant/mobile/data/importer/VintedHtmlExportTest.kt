package fr.vintedassistant.mobile.data.importer

import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.DataAudit
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.ShippingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format observé dans l'export RGPD de Vinted (novembre 2026), contenu inventé. */
class VintedHtmlExportTest {

    private fun listing(id: Long, title: String, price: String, created: String, views: Int = 10, favs: Int = 0) = """
  <div class="cell" itemscope>
    <div class="cell-header" itemprop="title">
      $title
    </div>
    <div class="cell-description">
      Articles mis en ligne:
      <span itemprop="description">
        Superbe bijou neuf
Envoi rapide sous 24h

bijou strass chic
      </span>
    </div>
    <div class="cell-description">Marque: <span itemprop="brand">
        Vintage
      </span></div>
    <div class="cell-description">Taille: <span itemprop="size">
        
      </span></div>
    <div class="cell-description">Condition: <span itemprop="status">Neuf sans étiquette</span></div>
    <div class="cell-description">Couleur: <span itemprop="color">Doré</span></div>
    <div class="cell-description">Vues: <span itemprop="views_count">
        $views
      </span></div>
    <div class="cell-description">« J'aime » reçus: <span itemprop="favourite_count">$favs</span></div>
    <div class="cell-description">Date de mise en ligne: <span itemprop="created_at">
        $created
      </span></div>
    <div class="cell-description">Prix: <span itemprop="order_value">
        $price
      </span></div>
    <div class="cell-images" itemscope>
      <div class="cell-images__item"><a href="photos/$id/01_a.webp"><img src="photos/$id/01_a.webp" itemprop="item_photo"></a></div>
      <div class="cell-images__item"><a href="photos/$id/02_b.webp"><img src="photos/$id/02_b.webp" itemprop="item_photo"></a></div>
    </div>
  </div>
"""

    private fun order(date: String, status: String, amount: String, vararg items: Pair<String, String>) = """
    <div class="cell" itemscope>
      <div class="cell-description">Commandé: <span itemprop="order_purchased">
          $date
        </span></div>
      <div class="cell-description">Status: <span itemprop="status">$status</span></div>
      <div class="cell-description">Vendeur: <span itemprop="seller">Aucune donnée</span></div>
      <div class="cell-description">Montant de la commande: <span itemprop="order_value">$amount</span></div>
      <ul itemprop="items" class="cell-table">
${items.joinToString("\n") { (t, p) -> "<li itemscope><span itemprop=\"item_title\">$t</span>: <span itemprop=\"item_price\">$p</span></li>" }}
      </ul>
    </div>
    <div class="spacer"></div>
"""

    private val listingsHtml = "<html><body><div class=\"cell\"><div class=\"cell-header\">Articles</div></div>" +
        listing(101, "Bracelet cordon rouge croix", "8.0 EUR", "2026-10-28 10:00:00 +0100") +
        listing(102, "Bracelet cordon rouge croix", "8.0 EUR", "2026-10-30 10:00:00 +0100") +
        listing(103, "Collier tennis argent", "9.0 EUR", "2026-09-09 11:48:20 +0200", views = 297, favs = 19) +
        "</body></html>"

    private val ordersHtml = "<html><body>" +
        order("2022-12-08 02:58:32 +0100", "Commande finalisée", "1.8 EUR", "Livre jeunesse" to "0.0 EUR") +
        order("2026-09-16 18:15:35 +0200", "Commande finalisée", "8.1 EUR", "Collier tennis argent" to "8.1 EUR") +
        order("2026-10-29 09:00:00 +0100", "Commande finalisée", "12.9 EUR", "Bracelet cordon rouge croix" to "6.4 EUR", "Inconnu ailleurs" to "6.5 EUR") +
        order("2026-11-05 09:00:00 +0100", "Commande annulée", "8.0 EUR", "Bracelet cordon rouge croix" to "8.0 EUR") +
        "</body></html>"

    @Test
    fun readsListings() {
        val l = VintedHtmlExport.listings(listingsHtml)
        assertEquals(3, l.size)
        val tennis = l.first { it.itemId == 103L }
        assertEquals("Collier tennis argent", tennis.title)
        assertEquals(900L, tennis.priceCents)
        assertEquals(297, tennis.views)
        assertEquals(19, tennis.favorites)
        assertEquals("Vintage", tennis.brand)
        assertEquals("", tennis.size)
        assertEquals("Neuf sans étiquette", tennis.condition)
        assertEquals("https://www.vinted.fr/items/103", tennis.url)
        assertEquals(listOf("listings/photos/103/01_a.webp", "listings/photos/103/02_b.webp"), tennis.photoPaths)
        assertEquals("Superbe bijou neuf\nEnvoi rapide sous 24h\n\nbijou strass chic", tennis.description)
        assertEquals(java.time.OffsetDateTime.parse("2026-09-09T11:48:20+02:00").toInstant().toEpochMilli(), tennis.createdAt)
    }

    @Test
    fun readsOrders() {
        val o = VintedHtmlExport.orders(ordersHtml)
        assertEquals(4, o.size)
        assertEquals(1290L, o[2].amountCents)
        assertEquals(listOf("Bracelet cordon rouge croix", "Inconnu ailleurs"), o[2].items.map { it.title })
        assertTrue(o[3].cancelled)
    }

    @Test
    fun ordersMarkTheOldestMatchingListingsAsSold() {
        val r = ExportSales.soldListings(VintedHtmlExport.listings(listingsHtml), VintedHtmlExport.orders(ordersHtml))
        // Commande de 2022 (avant la première annonce) et commande annulée ignorées.
        assertEquals(2, r.ignored)
        val byId = r.datings.associateBy { it.articleId }
        assertEquals(setOf(101L, 103L), byId.keys) // la 102 a été mise en ligne après la commande : toujours en vente
        assertEquals(640L, byId[101L]!!.priceCents)
        assertEquals(810L, byId[103L]!!.priceCents)
        assertTrue(byId.values.all { it.byTitle })
        // Article inconnu du lot : reste non rattaché, sans deviner.
        assertEquals(1, r.unmatched.size)
        assertEquals(650L, r.unmatched.single().amountCents)
    }

    @Test
    fun untitledOrderIsMatchedOnlyWithoutAmbiguity() {
        val at = 2_000_000L
        val slots = listOf(
            ExportSales.Slot(1, "Croix dorée", 500, 1_000),
            ExportSales.Slot(2, "Croix dorée", 500, 1_500),
            ExportSales.Slot(3, "Bracelet trèfle", 700, 1_000),
            ExportSales.Slot(4, "Collier", 700, 1_000),
        )
        val sure = ExportSales.match(listOf(ExportOrder(at, "Commande finalisée", 500, emptyList())), slots, 1_000)
        assertEquals(1L, sure.datings.single().articleId) // deux fiches identiques : la plus ancienne
        val ambiguous = ExportSales.match(listOf(ExportOrder(at, "Commande finalisée", 700, emptyList())), slots, 1_000)
        assertTrue(ambiguous.datings.isEmpty())
        assertEquals(1, ambiguous.unmatched.size)
    }

    @Test
    fun auditFindsRealDuplicatesAndSoldWithoutSale() {
        fun a(id: Long, item: Long?, status: ArticleStatus) =
            Article(id, "Fiche $id", 0, 900, status, id, vintedUrl = item?.let { "https://www.vinted.fr/items/$it" }.orEmpty())
        val articles = listOf(a(1, 55, ArticleStatus.ONLINE), a(2, 55, ArticleStatus.ONLINE), a(3, 56, ArticleStatus.SOLD))
        val sales = listOf(Sale(1, 2, "x", "", 900, 0, 0, 0, null, "", ShippingStatus.DELIVERED, null, "", dateKnown = false))
        val titles = DataAudit.findings(articles, sales, null).map { it.title }
        assertTrue(titles.any { it.startsWith("Vrais doublons") })
        assertTrue(titles.any { it.startsWith("Fiches « Vendu » sans vente") })
        assertTrue(titles.any { it.startsWith("Ventes sans date") })
        val report = DataAudit.report("0.10.0", 0, articles, sales, null)
        assertTrue(report.contains("VENTES (1)"))
        assertNull(articles.firstOrNull { it.id == 9L })
    }
}
