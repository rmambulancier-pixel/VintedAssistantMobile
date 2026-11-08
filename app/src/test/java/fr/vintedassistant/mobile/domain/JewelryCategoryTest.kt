package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class JewelryCategoryTest {
    @Test
    fun firstRecognisedWordWins() {
        assertEquals("Colliers", JewelryCategory.guess("Collier homme gourmette croix strass doré bling hip-hop Y2K"))
        assertEquals("Pendentifs", JewelryCategory.guess("Croix pendentif doré style baroque vintage"))
        assertEquals("Bracelets", JewelryCategory.guess("Bracelet Cordon Rouge Protection Croix Dorée"))
        assertEquals("Colliers", JewelryCategory.guess("Collier Chapelet Rosaire Acier Inoxydable 304 Doré Perlé Médaille"))
        assertEquals("Colliers", JewelryCategory.guess("Collier chaîne tennis strass argenté 40 cm ras de cou"))
        assertEquals(null, JewelryCategory.guess("Livre jeunesse"))
        assertEquals("Sans catégorie", JewelryCategory.of("", "Livre jeunesse"))
        assertEquals("Bijoux", JewelryCategory.of("Bijoux", "Bracelet"))
    }
}
