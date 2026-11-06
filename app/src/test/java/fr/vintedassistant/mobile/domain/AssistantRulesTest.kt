package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AssistantRulesTest {

    private val zone = ZoneId.of("Europe/Paris")
    private fun at(d: Int, h: Int = 12) = LocalDateTime.of(2026, 11, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun online(id: Long, lastVisible: Long?) =
        Article(id, "A$id", 0, 1000, ArticleStatus.ONLINE, 0, firstPublishedAt = lastVisible)

    // ---------- Republication ----------

    @Test
    fun planTakesOldestEligibleFirstWithinQuota() {
        val now = at(20)
        val articles = listOf(online(1, at(10)), online(2, at(1)), online(3, at(5)), online(4, at(18)), online(5, null))
        val plan = RepublishPlanner.plan(articles, emptyList(), RepublishSettings(minAgeDays = 7, maxPerDay = 2), now, zone)
        // Article 5 : date inconnue (import Vinted), donc prioritaire ; puis le plus ancien.
        assertEquals(listOf(5L, 2L), plan.today.map { it.id })
        assertEquals(2, plan.remainingQuota)
        assertEquals(1, plan.waiting) // article 4, trop récent
    }

    @Test
    fun planCountsRepublicationsAlreadyDoneToday() {
        val now = at(20, 18)
        val done = listOf(at(20, 9), at(20, 10), at(19, 23))
        val plan = RepublishPlanner.plan(listOf(online(1, at(1))), done, RepublishSettings(7, 3), now, zone)
        assertEquals(2, plan.doneToday)
        assertEquals(1, plan.remainingQuota)
        assertEquals(1, plan.today.size)

        val full = RepublishPlanner.plan(listOf(online(1, at(1))), done, RepublishSettings(7, 2), now, zone)
        assertTrue(full.today.isEmpty())
    }

    @Test
    fun planIgnoresNonOnlineArticles() {
        val reserved = online(1, at(1)).copy(status = ArticleStatus.RESERVED)
        val plan = RepublishPlanner.plan(listOf(reserved), emptyList(), RepublishSettings(7, 10), at(20), zone)
        assertTrue(plan.today.isEmpty())
    }

    // ---------- Négociation ----------

    @Test
    fun offerAboveFloorIsAccepted() {
        val advice = Negotiator.advise(listedCents = 2500, floorCents = 2000, offerCents = 2100)
        assertEquals(OfferDecision.ACCEPT, advice.decision)
        assertEquals(2100L, advice.amountCents)
    }

    @Test
    fun offerBelowFloorGetsCounterNeverBelowFloor() {
        val advice = Negotiator.advise(listedCents = 2500, floorCents = 2000, offerCents = 1000)
        assertEquals(OfferDecision.COUNTER, advice.decision)
        assertEquals(2000L, advice.amountCents) // milieu 17,50 relevé au plancher
        val mid = Negotiator.advise(listedCents = 3000, floorCents = 1500, offerCents = 1000)
        assertEquals(2000L, mid.amountCents) // milieu exact 20 €
        val rounded = Negotiator.advise(listedCents = 2999, floorCents = 1000, offerCents = 500)
        assertEquals(1800L, rounded.amountCents) // 17,49 arrondi à 18
    }

    @Test
    fun defaultFloorIs85Percent() {
        assertEquals(2125L, Negotiator.effectiveFloor(2500, null))
        assertEquals(OfferDecision.COUNTER, Negotiator.advise(2500, null, 2000).decision)
        assertEquals(OfferDecision.ACCEPT, Negotiator.advise(2500, null, 2200).decision)
    }

    @Test
    fun offerAboveListedIsCapped() {
        assertEquals(2500L, Negotiator.advise(2500, null, 3000).amountCents)
    }

    // ---------- Modèles de texte ----------

    @Test
    fun listingDraftUsesFieldsAndHashtags() {
        val a = Article(1, "Veste en jean", 0, 2500, ArticleStatus.DRAFT, 0,
            brand = "Levi's", size = "M", color = "Bleu", condition = "Très bon état", category = "Femmes")
        val draft = ListingTemplates.draft(a)
        assertEquals("Veste en jean Levi's Bleu taille M", draft.title)
        assertTrue(draft.description.contains("État : Très bon état"))
        assertTrue(draft.keywords.contains("#levis"))
        assertTrue(draft.keywords.contains("#femmes"))
        assertFalse(draft.title.contains("  "))
    }

    @Test
    fun slugRemovesAccentsAndPunctuation() {
        assertEquals("ecru", ListingTemplates.slug("Écru"))
        assertEquals("levis", ListingTemplates.slug("Levi's"))
    }

    @Test
    fun replyDetectsTopics() {
        assertEquals(setOf(BuyerTopic.AVAILABILITY), ReplyTemplates.topics("Bonjour, toujours dispo ?"))
        assertTrue(BuyerTopic.PRICE in ReplyTemplates.topics("Vous feriez un prix pour un lot ?"))
        assertTrue(BuyerTopic.SIZE in ReplyTemplates.topics("Il taille grand ?"))
        assertTrue(BuyerTopic.SHIPPING in ReplyTemplates.topics("Envoi en relais possible ?"))
    }

    @Test
    fun replyUsesArticleInfoAndAvailability() {
        val a = Article(1, "Pull", 0, 2000, ArticleStatus.ONLINE, 0, size = "L", condition = "Bon état")
        val reply = ReplyTemplates.reply("Dispo ? Il taille comment ?", a)
        assertTrue(reply.contains("toujours disponible"))
        assertTrue(reply.contains("taille L"))
        val sold = ReplyTemplates.reply("dispo ?", a.copy(status = ArticleStatus.SOLD))
        assertTrue(sold.contains("plus disponible"))
    }

    @Test
    fun favoriteMessageOnlyOffersRealDiscount() {
        val a = Article(1, "Pull", 0, 2000, ArticleStatus.ONLINE, 0)
        assertTrue(ReplyTemplates.favoriteMessage(a, 1800).contains("18,00"))
        assertFalse(ReplyTemplates.favoriteMessage(a, 2500).contains("25"))
        assertFalse(ReplyTemplates.favoriteMessage(a, null).contains("€"))
    }
}
