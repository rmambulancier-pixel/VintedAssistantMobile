package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GrowthTest {
    private val line = "Bracelet femme luxueux motif Cœur porte-bonheur en alliage avec incrustation en zircon versicolore"

    @Test fun `titre ramene au coloris achete`() {
        assertEquals(
            "Bracelet trèfle strass argenté et rose 17 cm style joaillerie vintage chic",
            ExpressListing.retitle("Bracelet trèfle strass rose poudré et or 17 cm style joaillerie vintage chic", "$line (Argent + Rose)"),
        )
        // Titre déjà juste : inchangé.
        val ok = "Bracelet trèfle strass rose rubis argenté 17 cm"
        assertEquals(ok, ExpressListing.retitle(ok, "$line (Argent + Rose)"))
        assertEquals("Doré, Vert", ExpressListing.colorField("Or + Vert"))
    }

    @Test fun `hausse de prix`() {
        assertEquals(800L, Growth.raise(700))
        assertEquals(1100L, Growth.raise(1000))
        assertEquals(1350L, Growth.raise(1250))
    }

    @Test fun `saison des cadeaux`() {
        assertTrue(Growth.giftSeason(LocalDate.of(2026, 11, 26)))
        assertTrue(Growth.giftSeason(LocalDate.of(2026, 12, 24)))
        assertFalse(Growth.giftSeason(LocalDate.of(2026, 12, 26)))
        val d = Growth.seasonal("Joli bracelet", LocalDate.of(2026, 12, 1))
        assertTrue(d.startsWith(Growth.GIFT_LINE))
        assertEquals(d, Growth.seasonal(d, LocalDate.of(2026, 12, 1)))
        assertEquals("Joli bracelet", Growth.seasonal("Joli bracelet", LocalDate.of(2027, 1, 5)))
    }

    private val day = DAY_MS
    private val now = 200 * day

    private fun sale(id: Long, title: String, soldDaysAgo: Int, listedBefore: Double, cents: Long = 700) =
        Sale(id, null, title, "", cents, 50, 0, now - soldDaysAgo * day, now - soldDaysAgo * day - (listedBefore * day).toLong())

    @Test fun `conseil de prix pour un modele qui part vite`() {
        val t = "Collier tennis 45 cm rivière de strass"
        val sales = listOf(sale(1, t, 10, 1.0), sale(2, t, 20, 2.0), sale(3, t, 30, 3.0))
        val draft = Article(9, t, 50, 700, ArticleStatus.DRAFT, now)
        val advice = Growth.priceAdvice(StockOverview(emptyList(), emptyList()), listOf(draft), sales, now).single()
        assertEquals(700L, advice.medianPriceCents)
        assertEquals(800L, advice.suggestedCents)
        assertEquals(listOf(9L), advice.draftIds)
    }

    @Test fun `pas de hausse si ca part lentement`() {
        val t = "Bracelet trèfle noir"
        val sales = listOf(sale(1, t, 10, 9.0), sale(2, t, 20, 12.0), sale(3, t, 30, 8.0))
        assertTrue(Growth.priceAdvice(StockOverview(emptyList(), emptyList()), emptyList(), sales, now).isEmpty())
    }

    @Test fun `message aux favoris`() {
        val m = Growth.favoriteOffer(Article(1, "Bracelet ange doré", 77, 800, ArticleStatus.ONLINE, 0))
        assertTrue(m.contains("6,50") || m.contains("6,50"))
    }
}

class PepitesSearchTest {
    @Test fun `mots traduits pour un site en anglais`() {
        assertEquals("clover", Pepites.searchWord("trefle", "en-US"))
        assertEquals("necklace", Pepites.searchWord("collier", "en"))
        assertEquals("trefle", Pepites.searchWord("trefle", "fr"))
        assertEquals("tennis", Pepites.searchWord("tennis", "en"))
    }
}
