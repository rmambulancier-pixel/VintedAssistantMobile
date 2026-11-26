package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FiscalTest {
    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(12, 0).atZone(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
    private fun sale(id: Long, ms: Long, cents: Long, status: ShippingStatus = ShippingStatus.DELIVERED) =
        Sale(id, null, "Bijou $id", "", cents, 40, 0, ms, null, shippingStatus = status)

    @Test fun `seuil des 30 ventes`() {
        val sales = (1..36).map { sale(it.toLong(), at(2026, 10, 1), 650) } + sale(99, at(2025, 12, 31), 900) +
            sale(100, at(2026, 11, 1), 900, ShippingStatus.CANCELLED)
        val y = Fiscal.year(sales, 2026)
        assertEquals(36, y.sales)
        assertEquals(23_400L, y.revenueCents)
        assertTrue(y.reached)
        assertFalse(Fiscal.year(sales, 2025).reached)
    }

    @Test fun `livre des recettes`() {
        val csv = Fiscal.receiptsCsv(listOf(sale(7, at(2026, 11, 13), 500)), 2026)
        assertTrue(csv.contains("\"13/11/2026\";\"V-7\";\"Bijou 7\";5,00;"))
    }

    @Test fun `registre des achats`() {
        val csv = Fiscal.purchasesCsv(listOf(Fiscal.Purchase(LocalDate.of(2026, 11, 7), "Nihaojewelry", "NHFR1", 4436, 1000, 0, 11)), 2026)
        assertTrue(csv.contains("\"07/11/2026\";\"Nihaojewelry\";\"NHFR1\";11;44,36;10,00;0,00;54,36"))
    }
}
