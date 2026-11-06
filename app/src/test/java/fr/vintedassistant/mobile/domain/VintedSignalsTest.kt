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
        // Exemples réels relevés sur le téléphone (6 novembre 2026).
        assertEquals(VintedEventKind.MESSAGE, c.classify("grognack", "grognack: Merci"))
        assertEquals(VintedEventKind.SALE, c.classify("grognack", "Ton article s'est vendu ! L'acheteur attend le colis."))
        assertEquals(
            VintedEventKind.SHIPPING,
            c.classify("grognack", "Bordereau d'envoi prêt ! Imprime-le et dépose ton colis dans n'importe quel point relais Mondial Relay."),
        )
        assertEquals(VintedEventKind.OFFER, c.classify("lea", "lea: Je te propose 5 € ?"))
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

    @Test
    fun splitsTrailingKeywordBlock() {
        val desc = "Bracelet argenté joli orné de trèfles\nLongueur 18 cm\nEnvoi rapide et soigné sous 24h\n\n" +
            "bracelettrefle joaillerie chic argente strassnoir trefle noir luxe vintage gothique"
        val (text, keywords) = ListingTemplates.splitKeywords(desc)
        assertEquals("Bracelet argenté joli orné de trèfles\nLongueur 18 cm\nEnvoi rapide et soigné sous 24h", text)
        assertTrue(keywords.startsWith("bracelettrefle joaillerie"))

        val hashtags = ListingTemplates.splitKeywords("Robe d'été.\n\n#robe #ete #fleurs #boheme")
        assertEquals("#robe #ete #fleurs #boheme", hashtags.second)

        // Une vraie phrase en dernier paragraphe reste dans la description.
        val sentence = "Pull chaud.\n\nN'hésitez pas à me contacter pour un lot."
        assertEquals("" , ListingTemplates.splitKeywords(sentence).second)
        assertEquals("", ListingTemplates.splitKeywords("Un seul paragraphe sans mots-clés").second)
    }

    @Test
    fun parsesVintedRelativeDates() {
        val now = 100L * DAY_MS
        assertEquals(now - 7 * DAY_MS, RelativeDates.parse("Il y a une semaine", now))
        assertEquals(now - 3 * DAY_MS, RelativeDates.parse("il y a 3 jours", now))
        assertEquals(now - 2 * 30 * DAY_MS, RelativeDates.parse("Il y a 2 mois", now))
        assertEquals(now - 365 * DAY_MS, RelativeDates.parse("il y a un an", now))
        assertEquals(now - 5 * 3_600_000L, RelativeDates.parse("Il y a 5 heures", now))
        assertNull(RelativeDates.parse("hier", now))
    }

    @Test
    fun undatedSalesCountInTotalOnly() {
        val now = 100L * DAY_MS
        val sales = listOf(
            Sale(1, 1, "a", "Bijoux", 1100, 0, 0, now, null, dateKnown = false),
            Sale(2, 2, "b", "Bijoux", 600, 200, 0, now, now - DAY_MS),
        )
        val articles = listOf(Article(1, "a", 0, 1100, ArticleStatus.SOLD, 0), Article(2, "b", 200, 600, ArticleStatus.SOLD, 0))
        val stats = computeDashboard(articles, sales, DashboardSettings(0, 21, 10), now, java.time.ZoneId.of("UTC"))
        assertEquals(1, stats.monthSalesCount)
        assertEquals(2, stats.totalSalesCount)
        assertEquals(1700L, stats.totalRevenueCents)
        assertEquals(1, stats.undatedSalesCount)
        assertEquals(1, stats.missingCostCount)
        assertEquals(1.0, stats.averageDaysToSell!!, 0.001)
    }
}
