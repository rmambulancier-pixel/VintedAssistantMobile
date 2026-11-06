package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LandedCostTest {

    @Test
    fun shippingIsSharedByValue() {
        // 3 bracelets à 1,20 € et 1 collier à 2,40 €, 4,80 € de port : valeur 6,00 €.
        val landed = LandedCost.allocate(listOf(PurchaseLine(120, 3), PurchaseLine(240, 1)), 480)
        // Bracelets : 3,60/6,00 du port = 2,88 € sur 3 pièces = 0,96 € chacune.
        assertEquals(listOf(216L, 432L), landed)
        // Total réparti = port payé.
        assertEquals(480L, (landed[0] - 120) * 3 + (landed[1] - 240))
    }

    @Test
    fun noExtraKeepsPrice() {
        assertEquals(listOf(150L), LandedCost.allocate(listOf(PurchaseLine(150, 2)), 0))
    }

    @Test
    fun freeItemsShareByPiece() {
        assertEquals(listOf(100L, 100L), LandedCost.allocate(listOf(PurchaseLine(0, 1), PurchaseLine(0, 1)), 200))
    }
}
