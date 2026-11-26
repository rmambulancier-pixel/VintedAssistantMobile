package fr.vintedassistant.mobile.domain

import fr.vintedassistant.mobile.domain.Coherence.Kind
import fr.vintedassistant.mobile.domain.Coherence.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoherenceTest {
    private val day = DAY_MS
    private val now = 100 * day
    private val line = "Bracelet femme luxueux motif Cœur porte-bonheur en alliage avec incrustation en zircon versicolore"
    private val lines = listOf(
        Line(335, "$line (Or + rose)"), Line(337, "$line (Argent + Rose)"), Line(330, "$line (Argent + bleu)"),
    )

    private fun art(id: Long, title: String, status: ArticleStatus, item: Long? = null, url: String = "") =
        Article(id, title, 40, 500, status, id, vintedUrl = url, purchaseItemId = item)

    private fun sale(id: Long, articleId: Long, title: String, at: Long, notes: String = "") =
        Sale(id, articleId, title, "", 500, 40, 0, at, null, notes = notes)

    @Test fun `coloris contradictoire`() {
        assertTrue(Coherence.contradicts("Bracelet trèfle strass rose poudré et or 17 cm", "$line (Argent + Rose)"))
        assertFalse(Coherence.contradicts("Bracelet trèfle strass rose rubis argenté 17 cm", "$line (Argent + Rose)"))
        assertFalse(Coherence.contradicts("Bracelet trèfle strass rose poudré et or", "$line (Or + rose)"))
        assertTrue(Coherence.contradicts("Bracelet trèfle argenté strass rose", "$line (Argent + bleu)"))
        assertFalse(Coherence.contradicts("Collier trésor doré", "Collier (Or)"))
    }

    @Test fun `cas du bracelet rose rubis argente`() {
        val articles = listOf(
            art(66, "Bracelet trèfle strass rose poudré et or 17 cm style joaillerie vintage chic", ArticleStatus.DRAFT, 337),
            art(46, "Bracelet trèfle acier inoxydable argenté strass brillants chic", ArticleStatus.DRAFT, 330),
            art(69, "Bracelet trèfle strass rose rubis argenté 17 cm joaillerie vintage style chic", ArticleStatus.ONLINE, null, "https://www.vinted.fr/items/1"),
        )
        val issues = Coherence.check(articles, emptyList(), lines, now)
        val dup = issues.single { it.kind == Kind.DUPLICATE_PIECE }
        assertEquals(69L, dup.articleId)
        assertEquals(66L, dup.otherId)
        assertEquals(337L, dup.itemId)
        assertEquals(66L, issues.single { it.kind == Kind.VARIANT_MISMATCH }.articleId)
    }

    @Test fun `cas du bracelet ange argente`() {
        val t = "Bracelet Ange Gardien Acier Inoxydable 304 Argenté Ajustable"
        val sales = listOf(
            sale(35, 3, t, now - 6 * day),
            sale(36, 58, t, now, "Vue vendue à l'import du dressing : prix affiché, à corriger si une offre a été acceptée."),
        )
        val issue = Coherence.check(emptyList(), sales, emptyList(), now).single()
        assertEquals(Kind.DUPLICATE_SALE, issue.kind)
        assertEquals(36L, issue.otherId)
    }

    @Test fun `deux vraies ventes du meme bijou ne sont pas signalees`() {
        val t = "Pendentif Croix Acier inoxydable Vintage"
        val sales = listOf(sale(32, 60, t, now - day), sale(33, 61, t, now))
        assertTrue(Coherence.check(emptyList(), sales, emptyList(), now).isEmpty())
    }
}
