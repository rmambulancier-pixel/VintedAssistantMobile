package fr.vintedassistant.mobile.data.importer

import fr.vintedassistant.mobile.domain.VintedLinks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageImportTest {

    @Test
    fun gridKeepsOnlyVintedItemsWithoutDuplicates() {
        val json = gridJson(
            JSONObject().put("url", "https://www.vinted.fr/items/111-collier-strass-argent?referrer=x")
                .put("title", "").put("price", "9,00 €").put("favorites", "0 favoris").put("views", "2 vues")
                .put("image", "https://images1.vinted.net/t/abc/f800/1.jpeg"),
            JSONObject().put("url", "https://www.vinted.fr/items/111-collier-strass-argent").put("price", "9,00 €"),
            JSONObject().put("url", "https://www.vinted.fr/items/222-bracelet-dore").put("title", "Bracelet doré, marque : Zara")
                .put("price", "6,00 €").put("favorites", "4 favoris").put("views", "22 vues")
                .put("image", "https://evil.example.com/x.jpg"),
            JSONObject().put("url", "https://www.vinted.fr/member/42"),
        )
        val items = PageImport.parseGrid(json)
        assertEquals(listOf(111L, 222L), items.map { it.itemId })
        val collier = items[0]
        assertEquals("Collier strass argent", collier.title)
        assertEquals("https://www.vinted.fr/items/111-collier-strass-argent", collier.url)
        assertEquals(900L, collier.priceCents)
        assertEquals(0, collier.favorites)
        assertEquals(2, collier.views)
        assertEquals(1, collier.imageUrls.size)
        val bracelet = items[1]
        assertEquals("Bracelet doré", bracelet.title)
        assertEquals(4, bracelet.favorites)
        assertTrue(bracelet.imageUrls.isEmpty()) // image hors Vinted refusée
        assertFalse(bracelet.detailed)
    }

    @Test
    fun itemPageReadsDetailsAndFiltersImages() {
        val json = JSONObject()
            .put("url", "https://www.vinted.fr/items/222-bracelet-dore")
            .put("title", "Bracelet doré grain de café")
            .put("price", "6.00")
            .put("description", "Joli bracelet")
            .put("brand", "Sans marque").put("size", "Taille unique").put("condition", "Très bon état")
            .put("images", JSONArray(listOf(
                "https://images1.vinted.net/t/a/f800/1.jpeg",
                "https://images1.vinted.net/t/a/f800/1.jpeg",
                "http://images1.vinted.net/t/a/f800/2.jpeg",
                "https://images2.vinted.net/t/a/f800/3.jpeg",
            )))
            .toString()
        val item = PageImport.parseItem(json)!!
        assertEquals(222L, item.itemId)
        assertEquals(600L, item.priceCents)
        assertEquals("Très bon état", item.condition)
        assertEquals(2, item.imageUrls.size) // doublon et http retirés
        assertTrue(item.detailed)
    }

    @Test
    fun itemPageWithoutVintedUrlIsRejected() {
        assertNull(PageImport.parseItem(JSONObject().put("url", "https://example.com/items/1-x").put("title", "x").toString()))
    }

    @Test
    fun helpers() {
        assertEquals(123456L, VintedLinks.itemId("https://www.vinted.fr/items/123456-robe-noire?x=1"))
        assertNull(VintedLinks.itemId("https://example.com/items/1"))
        assertEquals("Robe noire", VintedLinks.titleFromUrl("https://www.vinted.fr/items/123456-robe-noire"))
        assertEquals(1250L, PageImport.price("12,50 €"))
        assertEquals(22, PageImport.count("22 vues"))
        assertTrue(PageImport.isAllowedImage("https://images1.vinted.net/t/1.jpeg"))
        assertFalse(PageImport.isAllowedImage("https://vinted.net.evil.com/1.jpeg"))
    }
}
