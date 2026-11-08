package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoLayoutTest {
    @Test
    fun subjectBoxFindsOpaquePixels() {
        val w = 10; val h = 10
        val alpha = IntArray(w * h)
        for (y in 2..5) for (x in 3..7) alpha[y * w + x] = 255
        assertEquals(PhotoLayout.Box(3, 2, 8, 6), PhotoLayout.subjectBox(alpha, w, h))
        assertNull(PhotoLayout.subjectBox(IntArray(w * h), w, h))
    }

    @Test
    fun subjectIsCenteredAndFillsEightyPercent() {
        val p = PhotoLayout.place(PhotoLayout.Box(100, 50, 300, 150), 1000)
        assertEquals(4f, p.scale, 0.001f) // 800 / 200
        val left = 100 * p.scale + p.x
        val top = 50 * p.scale + p.y
        assertEquals(100f, left, 0.01f)
        assertEquals(300f, top, 0.01f) // hauteur 400, centrée dans 1000
    }

    @Test
    fun levelsStayGentle() {
        val flat = IntArray(1000) { 100 + it % 5 }
        assertEquals(1f to 0f, PhotoLayout.levels(flat)) // pas d'étirement sur une image uniforme
        val dull = IntArray(1000) { 80 + it % 120 }
        val (gain, offset) = PhotoLayout.levels(dull)
        assertTrue(gain in 1.0f..1.3f)
        assertTrue(offset in -40f..40f)
    }
}
