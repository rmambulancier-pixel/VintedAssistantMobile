package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetSummaryTest {
    private val today = LocalDate.of(2026, 11, 19)
    private val board = StockBoard(
        toList = 2, drafts = 7, online = 8, reserved = 1, toShip = 1, restocking = 35, shipping = 12, done = 23,
        withdrawn = 0, neverReceived = 2, inHandCostCents = 1364, onSaleValueCents = 9800, restockCostCents = 5333,
        orders = listOf(StockBoard.Arrival("A", 0, 11, 0, LocalDate.of(2026, 12, 1), "")),
    )

    @Test fun `mois, objectif et stock`() {
        val w = WidgetSummary.build(9420, 8150, 12, 100, emptyList(), 0, board, today)
        assertEquals("Ventes de novembre", w.monthLabel)
        assertEquals(94, w.goalPercent)
        assertTrue(w.goalText.startsWith("encore 5,80"))
        assertEquals("Aucun colis à déposer", w.parcels)
        assertEquals("9 en ligne • 9 à mettre en vente • 35 en réassort (1 déc)", w.stock)
        assertTrue(w.monthDetail.startsWith("12 ventes"))
    }

    @Test fun `colis urgent`() {
        val due = ShippingDeadline.Due(today, 1)
        val w = WidgetSummary.build(0, 0, 0, 0, listOf(due), 0, board, today)
        assertEquals("1 colis à déposer aujourd'hui", w.parcels)
        assertTrue(w.parcelsUrgent)
        assertEquals(null, w.goalPercent)
    }

    @Test fun `colis avec delai`() {
        val due = ShippingDeadline.Due(LocalDate.of(2026, 11, 26), 6)
        val w = WidgetSummary.build(0, 0, 1, 100, listOf(due), 0, board, today)
        assertEquals("1 colis à déposer • avant jeu. 26", w.parcels)
        assertFalse(w.parcelsUrgent)
        assertEquals("1 vente • " + Money.formatEuros(0) + " net", w.monthDetail)
    }
}
