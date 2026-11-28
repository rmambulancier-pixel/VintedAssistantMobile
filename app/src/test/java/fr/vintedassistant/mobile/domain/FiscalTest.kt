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
        assertTrue(csv.contains("\"07/11/2026\";\"Nihaojewelry\";\"Marchandises\";\"\";\"NHFR1\";11;44,36;10,00;0,00;54,36"))
    }

    @Test fun `fournitures au registre des achats`() {
        val bubbles = Supply(1, at(2026, 11, 20), "Pochettes à bulles 13×15", SupplyKind.PACKAGING, "Nihaojewelry", 30, 284, perParcel = true)
        val csv = Fiscal.purchasesCsv(listOf(Fiscal.supplyLine(bubbles)), 2026)
        assertTrue(csv.contains("\"20/11/2026\";\"Nihaojewelry\";\"Fournitures\";\"Pochettes à bulles 13×15 (emballage et envoi)\";\"\";30;2,84;0,00;0,00;2,84"))
    }

    @Test fun `stock de pochettes estimé par les ventes`() {
        val first = Supply(1, at(2026, 11, 20), "Pochettes à bulles", SupplyKind.PACKAGING, "", 30, 284, perParcel = true)
        val again = first.copy(id = 2, boughtAt = at(2026, 11, 25), label = "pochettes a bulles", quantity = 10, amountCents = 100)
        val label = Supply(3, at(2026, 11, 1), "Ruban", SupplyKind.PACKAGING, "", 1, 300, perParcel = false)
        val sales = (1..6).map { sale(it.toLong(), at(2026, 11, 22), 700) } + sale(50, at(2026, 11, 10), 700) +
            sale(51, at(2026, 11, 23), 700, ShippingStatus.CANCELLED)
        val stocks = Supplies.stocks(listOf(first, again, label), sales)
        assertEquals(1, stocks.size)
        assertEquals(40, stocks[0].bought)
        assertEquals(6, stocks[0].used)
        assertEquals(34, stocks[0].remaining)
        assertEquals(10L, stocks[0].unitCents)
        assertEquals(null, Supplies.alert(stocks))
        val empty = Supplies.stocks(listOf(first.copy(quantity = 4)), sales)
        assertEquals(0, empty[0].remaining)
        assertTrue(Supplies.alert(empty)!!.contains("plus rien"))
    }
}
