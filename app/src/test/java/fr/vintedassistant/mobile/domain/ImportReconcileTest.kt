package fr.vintedassistant.mobile.domain

import fr.vintedassistant.mobile.domain.ArticleStatus.DRAFT
import fr.vintedassistant.mobile.domain.ArticleStatus.ONLINE
import fr.vintedassistant.mobile.domain.ArticleStatus.SOLD
import fr.vintedassistant.mobile.domain.ImportReconcile.Fiche
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportReconcileTest {
    private val trefle = "Bracelet style joaillerie argenté et noir trèfle gothique chic strass 18 cm"
    private val tennis = "Collier tennis 40 cm rivière de strass argenté brillant Y2K"

    private fun real(id: Long, title: String, itemId: Long?, status: ArticleStatus = ONLINE, sale: Boolean = false) =
        Fiche(id, title, itemId, status, hasPurchase = true, hasSale = sale, costCents = 40, createdAt = id)
    private fun imported(id: Long, title: String, itemId: Long) =
        Fiche(id, title, itemId, ONLINE, hasPurchase = false, hasSale = false, costCents = 0, createdAt = 1000 + id)

    @Test fun `annonce republiee, la nouvelle va sur la fiche dont l'ancienne annonce a disparu`() {
        val fiches = listOf(real(1, trefle, itemId = 100), imported(2, trefle, itemId = 200))
        val plan = ImportReconcile.plan(fiches, active = setOf(200), sold = emptySet(), reliable = true)
        assertEquals(listOf(1L to 2L), plan.merges)
    }

    @Test fun `deux pieces identiques en ligne restent deux fiches`() {
        val fiches = listOf(real(1, trefle, itemId = 100), imported(2, trefle, itemId = 200))
        val plan = ImportReconcile.plan(fiches, active = setOf(100, 200), sold = emptySet(), reliable = true)
        assertTrue(plan.isEmpty)
    }

    @Test fun `annonce vendue lue en ligne, rattachee a la fiche vendue, jamais a un brouillon`() {
        val fiches = listOf(real(1, tennis, itemId = null, status = SOLD, sale = true), real(3, tennis, itemId = null, status = DRAFT), imported(2, tennis, itemId = 300))
        val plan = ImportReconcile.plan(fiches, active = emptySet(), sold = setOf(300), reliable = true)
        assertEquals(listOf(1L to 2L), plan.merges)
    }

    @Test fun `annonce vendue sans fiche vendue, marquee vendue, sans vente inventee`() {
        val fiches = listOf(real(3, tennis, itemId = null, status = DRAFT), imported(2, tennis, itemId = 300))
        val plan = ImportReconcile.plan(fiches, active = emptySet(), sold = setOf(300), reliable = true)
        assertEquals(listOf(2L), plan.markSold)
        assertTrue(plan.merges.isEmpty())
    }

    @Test fun `annonce disparue, doublon retire seulement si la lecture est complete`() {
        val fiches = listOf(real(1, trefle, itemId = 100), imported(2, trefle, itemId = 200))
        assertEquals(listOf(2L), ImportReconcile.plan(fiches, emptySet(), emptySet(), reliable = true).drops)
        assertTrue(ImportReconcile.plan(fiches, emptySet(), emptySet(), reliable = false).isEmpty)
    }

    @Test fun `une fiche avec achat n'est jamais retiree`() {
        val fiches = listOf(real(1, trefle, itemId = 100), real(2, trefle, itemId = 200))
        assertTrue(ImportReconcile.plan(fiches, setOf(300), emptySet(), reliable = true).isEmpty)
    }

    @Test fun `correspondance a l'import`() {
        val fiches = listOf(real(1, trefle, itemId = 100), real(4, trefle, itemId = null, status = DRAFT))
        // Lien exact.
        assertEquals(1L, ImportReconcile.match(100, trefle, false, fiches, setOf(100), emptySet()))
        // Nouvelle annonce, l'ancienne (100) a disparu : fiche 1 (en ligne, avec achat) avant le brouillon.
        assertEquals(1L, ImportReconcile.match(200, trefle, false, fiches, setOf(200), emptySet()))
        // L'ancienne est encore en ligne : le brouillon sans lien.
        assertEquals(4L, ImportReconcile.match(200, trefle, false, fiches, setOf(100, 200), emptySet()))
        // Import d'une seule annonce : seul un lien vide compte.
        assertEquals(4L, ImportReconcile.match(200, trefle, false, fiches, null, emptySet()))
        // Annonce vendue : jamais une fiche en stock.
        assertNull(ImportReconcile.match(200, trefle, true, fiches, setOf(200), emptySet()))
    }
}
