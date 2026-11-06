package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VintedSignalsTest {

    @Test
    fun recognisesVintedLinksOnly() {
        assertTrue(VintedLinks.isVintedUrl("https://www.vinted.fr/items/123-veste"))
        assertTrue(VintedLinks.isVintedUrl("https://vinted.com/items/1"))
        assertTrue(VintedLinks.isVintedUrl("https://www.vinted.co.uk/items/1"))
        assertFalse(VintedLinks.isVintedUrl("http://www.vinted.fr/items/1"))
        assertFalse(VintedLinks.isVintedUrl("https://vinted.fr.evil.com/items/1"))
        assertFalse(VintedLinks.isVintedUrl("https://notvinted.fr/items/1"))
        assertFalse(VintedLinks.isVintedUrl("pas un lien"))
    }

    @Test
    fun extractsLinkFromSharedText() {
        assertEquals(
            "https://www.vinted.fr/items/123-veste",
            VintedLinks.extract("Regarde cet article sur Vinted : https://www.vinted.fr/items/123-veste."),
        )
        assertNull(VintedLinks.extract("https://example.com/x"))
    }

    @Test
    fun classifiesNotifications() {
        val c = VintedNotificationClassifier
        assertEquals(VintedEventKind.SALE, c.classify("Vendu !", "Ton article Veste en jean a été acheté"))
        assertEquals(VintedEventKind.OFFER, c.classify("Nouvelle offre", "lea te propose 15 € pour Robe"))
        assertEquals(VintedEventKind.FAVORITE, c.classify("", "lea a ajouté Robe à ses favoris"))
        assertEquals(VintedEventKind.MESSAGE, c.classify("lea", "Nouveau message : toujours dispo ?"))
        assertEquals(VintedEventKind.OTHER, c.classify("Vinted", "Découvre les tendances"))
    }

    @Test
    fun extractsAmountAndMatchesArticle() {
        val c = VintedNotificationClassifier
        assertEquals(1500L, c.amountCents("lea te propose 15 € pour Robe"))
        assertEquals(1250L, c.amountCents("offre de 12,50€"))
        assertNull(c.amountCents("pas de montant"))
        val robe = Article(1, "Robe", 0, 2000, ArticleStatus.ONLINE, 0)
        val robeLongue = Article(2, "Robe longue été", 0, 3000, ArticleStatus.ONLINE, 0)
        assertEquals(2L, c.matchArticle("Offre pour Robe longue ÉTÉ", listOf(robe, robeLongue))?.id)
        assertEquals(1L, c.matchArticle("Offre pour robe", listOf(robe, robeLongue))?.id)
        assertNull(c.matchArticle("Offre pour pull", listOf(robe)))
    }

    @Test
    fun formAcceptsAndCleansVintedLink() {
        val ok = validateArticleForm(ArticleForm(title = "Pull", cost = "1", price = "2", vintedUrl = "Vu sur https://www.vinted.fr/items/9-pull"))
        assertEquals("https://www.vinted.fr/items/9-pull", (ok as FormValidation.Valid).fields.vintedUrl)
        val bad = validateArticleForm(ArticleForm(title = "Pull", cost = "1", price = "2", vintedUrl = "https://exemple.fr"))
        assertTrue(bad is FormValidation.Invalid)
    }
}
