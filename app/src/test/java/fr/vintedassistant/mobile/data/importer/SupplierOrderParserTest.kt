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
                item("Bijou gratuit", "€0,00", "1"), // produit offert : gardé, coût = part du port
            )))
            .put("shipping", "Frais de port €4,80")
            .put("total", "€12,00")
            .toString()
        val order = SupplierOrderParser.parse(json)!!
        assertEquals("NH2026101200123", order.orderRef)
        assertEquals(3, order.lines.size)
        assertEquals(3, order.lines[0].quantity)
        assertEquals(0L, order.lines[2].unitPriceCents)
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

    /** Commande réelle NHFR610132392734 (13 octobre 2026), telle que relevée sur la page. */
    @Test
    fun realNihaoOrder() {
        val lines = listOf(
            Triple("Bracelet en acier inoxydable 304 (6)", "€ 0.74", "1"),
            Triple("Bracelet en acier inoxydable 304 (7)", "€ 0.74", "1"),
            Triple("Collier chaîne de tennis argent en (4mm Argent | 18 pouces (46 cm))", "€ 0.50", "5"),
            Triple("Bracelet unisexe en cuivre plaqué (Rouge | B26081706A-P)", "€ 0.76", "4"),
            Triple("Collier homme style romain avec (Or)", "€ 0.46", "2"),
            Triple("Bracelet en acier inoxydable 304 (4)", "€ 0.74", "1"),
            Triple("Bracelet en acier inoxydable 304 (2)", "€ 0.74", "1"),
            Triple("Pendentif Croix en acier inoxydab (Couleur Acier | 45cm)", "€ 0.86", "4"),
            Triple("Collier chapelet unisexe en acier i (Vierge dorée | Acier Inoxydable)", "€ 1.27", "2"),
            Triple("Collier femme modèle oval en aci (2)", "€ 1.95", "1"),
            Triple("Bracelet Ange Femme en Acier In (Taille Unique | XLJ459)", "€ 0.54", "3"),
            Triple("Collier chapelet unisexe en acier i (Vierge Marie Acier | Acier Inoxydable)", "€ 0.79", "2"),
            Triple("Bracelet Ange Femme en Acier In (Taille Unique | XLY459)", "€ 0.43", "3"),
        )
        val json = JSONObject()
            .put("url", "https://www.nihaojewelry.com/order/detail")
            .put("ref", "NHFR610132392734")
            .put("date", "13 oct. 2026, 11:30")
            .put("items", JSONArray(lines.map { (n, p, q) -> item(n, p, q) }))
            .put("shipping", "€ 9.22")
            .put("total", "€ 24.27")
            .toString()
        val order = SupplierOrderParser.parse(json)!!
        assertEquals(13, order.lines.size)
        assertEquals(30, order.lines.sumOf { it.quantity })
        assertEquals(2184L, order.itemsTotalCents) // prix après coupon
        assertEquals(922L, order.shippingCents)
        assertEquals(0L, order.feesCents) // le total affiché (24,27, avant coupon) ne crée pas de faux frais
        val expected = LocalDateTime.of(2026, 10, 13, 11, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, order.orderedAt)

        val landed = fr.vintedassistant.mobile.domain.LandedCost.allocate(
            order.lines.map { fr.vintedassistant.mobile.domain.PurchaseLine(it.unitPriceCents, it.quantity) },
            order.shippingCents + order.feesCents,
        )
        assertEquals(105L, landed[0]) // bracelet jonc 0,74 → 1,05
        assertEquals(71L, landed[2]) // chaîne tennis 0,50 → 0,71
        assertEquals(277L, landed[9]) // collier oval 1,95 → 2,77
    }

    /** Commande « Essai gratuit » NHFR608202311892 : produits à 0 €, seul le port est payé. */
    @Test
    fun freeTrialOrderKeepsFreeItems() {
        val json = JSONObject()
            .put("ref", "NHFR608202311892")
            .put("date", "20 août 2026, 23:05")
            .put("items", JSONArray((1..24).map { i -> item("ESSAI GRATUITBracelet femme luxueux $i", "€ 0.00", "1") }))
            .put("shipping", "€ 8.79")
            .put("total", "€ 0.00")
            .toString()
        val order = SupplierOrderParser.parse(json)!!
        assertEquals(24, order.lines.size)
        assertEquals("Bracelet femme luxueux 1", order.lines[0].name) // badge retiré
        assertEquals(0L, order.itemsTotalCents)
        assertEquals(879L, order.shippingCents)
        assertEquals(0L, order.feesCents)
        assertEquals(8, java.time.Instant.ofEpochMilli(order.orderedAt!!).atZone(ZoneId.systemDefault()).monthValue)
        val landed = fr.vintedassistant.mobile.domain.LandedCost.allocate(
            order.lines.map { fr.vintedassistant.mobile.domain.PurchaseLine(it.unitPriceCents, it.quantity) }, order.shippingCents,
        )
        assertEquals(37L, landed[0]) // 8,79 € / 24 pièces
    }

    @Test
    fun readsSkuAndDeliveryWindow() {
        val json = JSONObject()
            .put("url", "https://www.nihaojewelry.com/order/detail?id=9")
            .put("ref", "NHFR611")
            .put("date", "7 nov. 2026, 13:19")
            .put("eta", "mer. 25 nov.   - mar. 1 déc.")
            .put("shipping", "€ 10.05").put("total", "€ 19.30")
            .put("items", org.json.JSONArray().put(
                JSONObject().put("name", "Bracelet unisexe en cuivre plaqué (Rouge | B26081706A-P)").put("price", "€ 0.84")
                    .put("qty", "11").put("image", "https://img.nihaojewelry.com/a.jpg").put("url", "").put("sku", "nh59670045"),
            ))
            .toString()
        val order = SupplierOrderParser.parse(json)!!
        assertEquals("NH59670045", order.lines.single().sku)
        assertEquals("mer. 25 nov. - mar. 1 déc.", order.deliveryEta)
        assertEquals(0L, order.feesCents) // 0,01 € d'écart : arrondi, pas des frais
        assertEquals(1005L, order.shippingCents)
    }

    @Test
    fun datesWithNonBreakingSpacesAreRead() {
        val expected = LocalDateTime.of(2026, 8, 20, 23, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, SupplierOrderParser.date("20\u00A0août\u00A02026, 23:05"))
        assertEquals(
            LocalDateTime.of(2026, 10, 13, 11, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            SupplierOrderParser.date("13\u202Foct.\u00A02026,\u00A011:30"),
        )
    }

    @Test
    fun freeItemsCostNothingEvenWhenTheOriginalPriceIsShown() {
        fun order(free: Boolean, total: String) = JSONObject()
            .put("url", "https://www.nihaojewelry.com/order/detail?id=1").put("ref", "NHFR608")
            .put("shipping", "€ 8.79").put("total", total)
            .put("items", JSONArray()
                .put(JSONObject().put("name", "Bracelet trèfle (Doré)").put("price", "€ 2.70").put("qty", "1").put("image", "").put("free", free))
                .put(JSONObject().put("name", "Collier tennis (45cm)").put("price", "€ 0.49").put("qty", "1").put("image", "")))
            .toString()
        // Drapeau « Essai gratuit » sur la ligne.
        val flagged = SupplierOrderParser.parse(order(free = true, total = "€ 9.28"))!!
        assertEquals(listOf(0L, 49L), flagged.lines.map { it.unitPriceCents })
        // Total payé égal au port : toute la commande était offerte.
        val allFree = SupplierOrderParser.parse(order(free = false, total = "€ 8.79"))!!
        assertEquals(listOf(0L, 0L), allFree.lines.map { it.unitPriceCents })
        assertEquals(0L, allFree.feesCents)
    }

    @Test
    fun expectedPiecesAndSkuVariantsAreKept() {
        val items = JSONArray()
        (1..6).forEach { i ->
            items.put(JSONObject().put("name", "Pendentif humain ($i Order Number |)").put("price", "€ 0.00").put("qty", "1")
                .put("image", "https://img.nihaojewelry.com/p.jpg").put("sku", "NH5742538$i"))
        }
        val order = SupplierOrderParser.parse(
            JSONObject().put("ref", "NHFR608").put("count", "6").put("shipping", "€ 8.79").put("total", "€ 8.79").put("items", items).toString(),
        )!!
        assertEquals(6, order.lines.size)
        assertEquals(6, order.expectedPieces)
    }

    @Test
    fun englishDatesAreRead() {
        val z = ZoneId.systemDefault()
        assertEquals(LocalDateTime.of(2026, 8, 20, 23, 5).atZone(z).toInstant().toEpochMilli(), SupplierOrderParser.date("Aug\u00A020, 2026 23:05:11"))
        assertEquals(LocalDateTime.of(2026, 10, 13, 23, 30).atZone(z).toInstant().toEpochMilli(), SupplierOrderParser.date("Oct 13, 2026 11:30 PM"))
        assertEquals(LocalDateTime.of(2026, 11, 7, 12, 0).atZone(z).toInstant().toEpochMilli(), SupplierOrderParser.date("November 7, 2026"))
        assertEquals(LocalDateTime.of(2026, 11, 7, 13, 19).atZone(z).toInstant().toEpochMilli(), SupplierOrderParser.date("7 nov. 2026, 13:19"))
    }
}
