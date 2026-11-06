package fr.vintedassistant.mobile.ai

import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.reminders.ReminderTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AiPromptsTest {

    private val article = Article(1, "Pull", 0, 2000, ArticleStatus.DRAFT, 0, brand = "Zara", size = "M")

    @Test
    fun parsesWellFormedAnswer() {
        val text = """
            TITRE: Pull Zara taille M
            DESCRIPTION: Joli pull Zara en très bon état.
            Taille M, porté deux fois.
            MOTS-CLES: pull zara maille hiver
        """.trimIndent()
        val draft = AiPrompts.parseListing(text, article)!!
        assertEquals("Pull Zara taille M", draft.title)
        assertEquals("Joli pull Zara en très bon état.\nTaille M, porté deux fois.", draft.description)
        assertTrue(draft.keywords.contains("#maille"))
    }

    @Test
    fun toleratesMarkdownAndAccents() {
        val text = "**TITRE:** Pull Zara\n**DESCRIPTION:** Très beau pull.\n**MOTS-CLÉS:** pull"
        val draft = AiPrompts.parseListing(text, article)!!
        assertEquals("Pull Zara", draft.title)
        assertEquals("Très beau pull.", draft.description)
    }

    @Test
    fun rejectsAnswerWithoutRequiredFields() {
        assertNull(AiPrompts.parseListing("Voici une belle annonce pour ton pull !", article))
        assertNull(AiPrompts.parseListing("TITRE: Pull", article))
    }

    @Test
    fun promptsContainFactsAndRules() {
        val listing = AiPrompts.listing(article, hasPhoto = true)
        assertTrue(listing.contains("brand: Zara"))
        assertTrue(listing.contains("Never invent"))
        val reply = AiPrompts.reply(article, "Dernier prix ?", floorCents = 1700)
        assertTrue(reply.contains("17,00"))
    }

    @Test
    fun cleanReplyStripsQuotes() {
        assertEquals("Bonjour !", AiPrompts.cleanReply("  \"Bonjour !\"  "))
        assertNull(AiPrompts.cleanReply("   "))
    }

    @Test
    fun reminderDelayTargetsNextOccurrence() {
        val zone = ZoneId.of("Europe/Paris")
        val at = { h: Int, m: Int -> LocalDateTime.of(2026, 11, 6, h, m).atZone(zone).toInstant().toEpochMilli() }
        assertEquals(60L * 60 * 1000, ReminderTime.millisUntilNext(19, at(18, 0), zone))
        assertEquals(23L * 60 * 60 * 1000, ReminderTime.millisUntilNext(19, at(20, 0), zone))
        assertEquals(24L * 60 * 60 * 1000, ReminderTime.millisUntilNext(19, at(19, 0), zone))
    }
}
