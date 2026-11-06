package fr.vintedassistant.mobile.data.importer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SupplierOrderParserTest {

    private fun item(name: String, price: String, qty: String, image: String = "https://img.nihaojewelry.com/a.jpg") =
        JSONObject().put("name", name).put("price", price).put("qty", qty).put("image", image).put("url", "")

    @Test
    fun parsesOrderAndDerivesFeesFromTotal() {
        val json = JSONObject()
            .put("url", "https://www.nihaojewelry.com/order/detail?id=123")
            .put("ref", "NH2026101200123")
            .put("date", "2026-10-12 14:33")
            .put("items", JSONArray(listOf(
                item("Bracelet trèfle strass acier", "€1,20", "3"),
                item("Collier tennis strass argenté", "2,40 €", "1"),
                item("Collier tennis strass argenté", "2,40 €", "1"), // doublon d'affichage
                item("x", "€1,00", "1"), // nom trop court
                item("Bijou gratuit", "€0,00", "1"),
            )))
            .put("shipping", "Frais de port €4,80")
            .put("total", "€12,00")
            .toString()
        val order = SupplierOrderParser.parse(json)!!
        assertEquals("NH2026101200123", order.orderRef)
        assertEquals(2, order.lines.size)
        assertEquals(3, order.lines[0].quantity)
        assertEquals(600L, order.itemsTotalCents)
        assertEquals(480L, order.shippingCents)
        assertEquals(120L, order.feesCents) // 12,00 − 6,00 − 4,80
        val expected = LocalDateTime.of(2026, 10, 12, 14, 33).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, order.orderedAt)
    }

    @Test
    fun noRecognisedItemMeansNoOrder() {
        assertNull(SupplierOrderParser.parse(JSONObject().put("items", JSONArray()).toString()))
    }

    @Test
    fun dateFormats() {
        assertNotNull(SupplierOrderParser.date("12/10/2026"))
        assertNotNull(SupplierOrderParser.date("Commandé le 2026/10/12"))
        assertNull(SupplierOrderParser.date("hier"))
        assertEquals(123L, SupplierOrderParser.money("EUR 1,23"))
    }
}
