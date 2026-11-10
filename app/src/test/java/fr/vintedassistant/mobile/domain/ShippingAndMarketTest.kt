package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ShippingAndMarketTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun sale(soldAt: Long, status: ShippingStatus = ShippingStatus.TO_SHIP, dateKnown: Boolean = true, title: String = "x", price: Long = 900) =
        Sale(1, null, title, "", price, 0, 0, soldAt, null, shippingStatus = status, dateKnown = dateKnown)

    @Test
    fun easterDates() {
        assertEquals(LocalDate.of(2026, 4, 5), ShippingDeadline.easter(2026))
        assertEquals(LocalDate.of(2027, 3, 28), ShippingDeadline.easter(2027))
        assertEquals(LocalDate.of(2024, 3, 31), ShippingDeadline.easter(2024))
    }

    @Test
    fun fiveWorkingDaysSkipWeekendsAndHolidays() {
        // Lundi 2 novembre 2026 → lundi 9 novembre.
        assertEquals(LocalDate.of(2026, 11, 9), ShippingDeadline.lastDay(at(2026, 11, 2), zone))
        // Vendredi 6 novembre 2026 : 9, 10, (11 férié), 12, 13, 16.
        assertEquals(LocalDate.of(2026, 11, 16), ShippingDeadline.lastDay(at(2026, 11, 6), zone))
        // Samedi 7 novembre 2026 : 9, 10, 12, 13, 16.
        assertEquals(LocalDate.of(2026, 11, 16), ShippingDeadline.lastDay(at(2026, 11, 7), zone))
        // Jeudi 24 décembre 2026 : 25 férié, week-end, 28, 29, 30, 31, 1er janvier férié, 4.
        assertEquals(LocalDate.of(2027, 1, 4), ShippingDeadline.lastDay(at(2026, 12, 24), zone))
        assertTrue(ShippingDeadline.isHoliday(LocalDate.of(2026, 5, 14))) // Ascension 2026
        assertTrue(ShippingDeadline.isHoliday(LocalDate.of(2026, 5, 25))) // lundi de Pentecôte 2026
    }

    @Test
    fun dueCountsRemainingWorkingDays() {
        val s = sale(at(2026, 11, 2))
        val monday = ShippingDeadline.due(s, at(2026, 11, 2, 18), zone)!!
        assertEquals(LocalDate.of(2026, 11, 9), monday.lastDay)
        assertEquals(6, monday.workingDaysLeft) // du 2 au 9 inclus : 2, 3, 4, 5, 6, 9
        assertFalse(monday.urgent)
        val saturday = ShippingDeadline.due(s, at(2026, 11, 7), zone)!!
        assertEquals(1, saturday.workingDaysLeft)
        assertTrue(saturday.urgent)
        assertFalse(saturday.late)
        val after = ShippingDeadline.due(s, at(2026, 11, 10), zone)!!
        assertTrue(after.late)
    }

    @Test
    fun noDeadlineWhenShippedOrUndated() {
        assertNull(ShippingDeadline.due(sale(at(2026, 11, 2), ShippingStatus.SHIPPED), at(2026, 11, 3), zone))
        assertNull(ShippingDeadline.due(sale(at(2026, 11, 2), dateKnown = false), at(2026, 11, 3), zone))
    }

    @Test
    fun reminderMessages() {
        assertNull(ShippingDeadline.reminderText(0, 0))
        assertTrue(ShippingDeadline.reminderText(1, 0)!!.startsWith("1 colis est à déposer"))
        assertTrue(ShippingDeadline.reminderText(2, 1)!!.contains("dont 1 en retard"))
    }

    private fun art(title: String, price: Long, floor: Long? = null) =
        Article(1, title, 0, price, ArticleStatus.ONLINE, 0, floorPriceCents = floor, firstPublishedAt = 0)

    @Test
    fun marketPriceUsesSimilarSoldModels() {
        val sales = listOf(
            sale(0, ShippingStatus.DELIVERED, title = "Bracelet trèfle bleu acier inoxydable", price = 700),
            sale(0, ShippingStatus.DELIVERED, title = "Bracelet trèfle rouge acier inoxydable", price = 800),
            sale(0, ShippingStatus.DELIVERED, title = "Bracelet trèfle noir", price = 900),
            sale(0, ShippingStatus.DELIVERED, title = "Collier trèfle bleu", price = 2000), // autre type
            sale(0, ShippingStatus.CANCELLED, title = "Bracelet trèfle vert", price = 100), // annulée
        )
        val a = art("Bracelet trèfle vert acier inoxydable", 1200)
        val m = PriceAdvisor.market(a, sales)!!
        assertEquals(800L, m.medianCents)
        assertEquals(3, m.sales)
        assertEquals(800L, PriceAdvisor.suggest(a, sales, 10))
        // Plancher respecté.
        assertEquals(950L, PriceAdvisor.suggest(art("Bracelet trèfle vert", 1200, floor = 950), sales, 10))
        // Marché plus haut que le prix : baisse classique de 10 %.
        assertEquals(500L, PriceAdvisor.suggest(art("Bracelet trèfle vert", 590), sales, 10))
        // Une seule vente proche : pas de prix de marché.
        assertNull(PriceAdvisor.market(art("Collier trèfle doré", 1500), sales))
    }
}

class AuditSalesTest {
    @Test
    fun salesWithoutFicheOrCostAreReported() {
        val a = Article(1, "Collier tennis", 37, 800, ArticleStatus.SOLD, 0, purchaseItemId = 9)
        val sales = listOf(
            Sale(1, 1, "Collier tennis", "", 800, 0, 0, 0, null),
            Sale(2, null, "Pendentif croix", "", 280, 0, 0, 0, null),
        )
        val stock = StockLedger.build(emptyList(), listOf(a))
        val titles = DataAudit.findings(listOf(a), sales, stock).map { it.title }
        org.junit.Assert.assertTrue(titles.any { it.startsWith("Ventes sans coût") })
    }
}

class QuickReplyTest {
    private val a = Article(7, "Bracelet trèfle vert", 37, 800, ArticleStatus.ONLINE, 0, firstPublishedAt = 0)

    @Test
    fun offerBelowFloorGetsCounterOffer() {
        val p = QuickReply.prepare(VintedEventKind.OFFER, "lea", "lea: Je te propose 5 € ?", a, 10)!!
        org.junit.Assert.assertTrue(p.heading.startsWith("Offre ${Money.formatEuros(500)} : contre-proposer"))
        org.junit.Assert.assertTrue(p.message.contains("Je ne peux pas descendre"))
    }

    @Test
    fun messageAndFavoriteAreDrafted() {
        val m = QuickReply.prepare(VintedEventKind.MESSAGE, "lea", "lea: Il est toujours dispo ?", a, 10)!!
        org.junit.Assert.assertTrue(m.message.contains("toujours disponible"))
        val f = QuickReply.prepare(VintedEventKind.FAVORITE, "Vinted", "lea a ajouté Bracelet trèfle vert à ses favoris", a, 10)!!
        org.junit.Assert.assertTrue(f.heading.contains(Money.formatEuros(700)))
        org.junit.Assert.assertNull(QuickReply.prepare(VintedEventKind.OTHER, "", "", a, 10))
    }
}

class MissingPiecesTest {
    @Test
    fun missingPiecesLeaveTheStock() {
        val items = listOf(
            PurchaseItem(1, 10, "Bracelet fleurs (Vert Rose)", null, 0, 1, 37, 0, "", "NH1", missing = 1),
            PurchaseItem(2, 10, "Collier tennis", null, 0, 3, 37, 1, "", "NH2"),
        )
        val order = PurchaseOrder(10, "Nihao", "R", 0, 0, 879, 0, items)
        val stock = StockLedger.build(listOf(order), emptyList())
        org.junit.Assert.assertEquals(3, stock.bought)
        org.junit.Assert.assertEquals(0, items[0].remaining)
        org.junit.Assert.assertEquals(0, items[0].delivered)
    }
}

class ExpressListingTest {
    private fun item(id: Long, name: String, qty: Int, sku: String) = PurchaseItem(id, 1, name, null, 0, qty, 40, 0, "", sku)

    @Test
    fun planUsesSameModelOrFamilyTemplate() {
        val sold = Article(5, "Bracelet cœur vert émeraude", 40, 700, ArticleStatus.SOLD, 0, description = "Joli", purchaseItemId = 1)
        val order = PurchaseOrder(1, "Nihao", "R", 0, 0, 0, 0, listOf(
            item(1, "Bracelet femme cœur (Vert)", 2, "NH1"),
            item(2, "Bracelet femme cœur (Rose)", 1, "NH2"),
            item(3, "Collier tennis", 1, "NH3"),
        ))
        val plan = ExpressListing.plan(StockLedger.build(listOf(order), listOf(sold)))
        org.junit.Assert.assertEquals(3, plan.size)
        val vert = plan.first { it.itemId == 1L }
        org.junit.Assert.assertEquals(5L, vert.templateId); org.junit.Assert.assertTrue(vert.sameVariant)
        val rose = plan.first { it.itemId == 2L }
        org.junit.Assert.assertEquals(5L, rose.templateId); org.junit.Assert.assertFalse(rose.sameVariant)
        org.junit.Assert.assertNull(plan.first { it.itemId == 3L }.templateId)
        org.junit.Assert.assertEquals("Rose", ExpressListing.variant("Bracelet femme cœur (Rose)"))
    }

    @Test
    fun priceFromSimilarSales() {
        val sales = listOf(
            Sale(1, null, "Collier tennis strass argenté", "Colliers", 750, 0, 0, 0, null),
            Sale(2, null, "Collier tennis strass 45 cm", "Colliers", 900, 0, 0, 0, null),
        )
        org.junit.Assert.assertEquals(800L, ExpressListing.suggestPrice("Collier tennis strass doré", sales))
        org.junit.Assert.assertNull(ExpressListing.suggestPrice("Bague argent", sales))
    }
}

class RestockTest {
    @Test
    fun soldOutBestSellersAreAdvised() {
        val items = listOf(PurchaseItem(1, 1, "Collier tennis", null, 0, 3, 70, 3, "", "NH1"), PurchaseItem(2, 1, "Bague", null, 0, 5, 50, 1, "", "NH2"))
        val order = PurchaseOrder(1, "N", "R", 0, 0, 0, 0, items)
        val arts = (1L..3L).map { Article(it, "Collier tennis strass", 70, 800, ArticleStatus.SOLD, 0, purchaseItemId = 1) } +
            Article(9, "Bague", 50, 500, ArticleStatus.SOLD, 0, purchaseItemId = 2)
        val sales = (1L..3L).map { Sale(it, it, "Collier tennis strass", "", 800, 70, 0, 3 * DAY_MS, 0) } + Sale(9, 9, "Bague", "", 500, 50, 0, 0, null)
        val advice = Restock.advise(StockLedger.build(listOf(order), arts), sales)
        org.junit.Assert.assertEquals(1, advice.size)
        org.junit.Assert.assertEquals(3, advice[0].sold)
        org.junit.Assert.assertEquals(730L, advice[0].marginCents)
        org.junit.Assert.assertEquals(3.0, advice[0].averageDays!!, 0.01)
    }

    @Test
    fun monthProjection() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        fun at(m: Int, d: Int) = java.time.LocalDateTime.of(2026, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val sales = listOf(Sale(1, null, "a", "", 1000, 0, 0, at(11, 5), null), Sale(2, null, "b", "", 600, 0, 0, at(10, 20), null))
        val p = Restock.projection(sales, at(11, 10), zone)
        org.junit.Assert.assertEquals(3000L, p.projectedCents)
        org.junit.Assert.assertEquals(600L, p.lastMonthCents)
    }
}

class PepitesTest {
    private val items = listOf(PurchaseItem(1, 1, "Collier chaîne de tennis argent en métal avec incrustation en zircon", null, 30, 4, 70, 3, "", "NH1"))
    private val order = PurchaseOrder(1, "N", "R", 0, 120, 800, 0, items)
    private val arts = (1L..3L).map { Article(it, "Collier tennis strass", 70, 800, ArticleStatus.SOLD, 0, purchaseItemId = 1) }
    private val sales = (1L..3L).map { Sale(it, it, "Collier tennis strass", "Colliers", 800, 70, 0, 2 * DAY_MS, 0) }
    private val k = Pepites.learn(StockLedger.build(listOf(order), arts), sales, listOf(order))

    @Test
    fun estimateFromCloseModelTypeOrNothing() {
        org.junit.Assert.assertEquals(200L, k.shippingPerPieceCents)
        val close = Pepites.estimate("Collier chaîne de tennis doré en métal incrustation zircon", 40, k)
        org.junit.Assert.assertEquals(Pepites.Basis.MODEL, close.basis)
        org.junit.Assert.assertEquals(240L, close.costCents)
        org.junit.Assert.assertEquals(800L, close.saleCents)
        org.junit.Assert.assertEquals("×3,3", Pepites.badge(close))
        val type = Pepites.estimate("Collier papillon émail", 30, k)
        org.junit.Assert.assertEquals(Pepites.Basis.TYPE, type.basis)
        org.junit.Assert.assertEquals(Pepites.Basis.NONE, Pepites.estimate("Bague serpent", 30, k).basis)
        org.junit.Assert.assertTrue(Pepites.keywords(k).contains("tennis"))
    }

    @Test
    fun briefInsightAndReports() {
        val lines = MorningBrief.lines(MorningBrief.Input(1, 0, 2, 3, 0, 15, 1, 0, 0))
        org.junit.Assert.assertEquals(4, lines.size)
        org.junit.Assert.assertTrue(MorningBrief.lines(MorningBrief.Input(0, 0, 0, 0, 0, 0, 0, 0, 0)).isEmpty())
        val stock = StockLedger.build(listOf(order), arts)
        org.junit.Assert.assertTrue(SaleInsight.of(1, stock, sales)!!.contains("monter le prix"))
        val w = Reports.week(sales, 0, 3 * DAY_MS)
        org.junit.Assert.assertEquals(3, w.sales)
        org.junit.Assert.assertEquals(2190L, w.netCents)
        val csv = Reports.monthCsv(sales, 0, 3 * DAY_MS, java.time.ZoneId.of("UTC"))
        org.junit.Assert.assertTrue(csv.lines()[1].startsWith("1970-01-03;\"Collier tennis strass\";8,00;0,70"))
        org.junit.Assert.assertTrue(csv.contains("Total;;24,00;2,10"))
    }
}

class EtaTest {
    @Test
    fun etaParsing() {
        val d = java.time.LocalDate.of(2026, 12, 1)
        org.junit.Assert.assertTrue(MorningBrief.etaReached("Tues, Dec 01", d))
        org.junit.Assert.assertFalse(MorningBrief.etaReached("Tues, Dec 01", d.minusDays(1)))
        org.junit.Assert.assertFalse(MorningBrief.etaReached("Mon, Jan 04", d))
        org.junit.Assert.assertFalse(MorningBrief.etaReached("", d))
    }
}

class StockBoardTest {
    @Test
    fun piecesFallIntoExactlyOneBlock() {
        val today = java.time.LocalDate.of(2026, 11, 10)
        val received = PurchaseOrder(1, "N", "A", 0, 0, 0, 0, listOf(
            PurchaseItem(1, 1, "Collier tennis", null, 0, 6, 70, 5, "", "NH1"),
            PurchaseItem(2, 1, "Bracelet fleurs", null, 0, 1, 37, 0, "", "NH2", missing = 1),
        ))
        val coming = PurchaseOrder(2, "N", "B", 1000, 0, 0, 0, listOf(PurchaseItem(3, 2, "Collier chapelet", null, 0, 12, 200, 0, "", "NH3")),
            received = false, deliveryEta = "Thur, Dec 03")
        val arts = listOf(
            Article(1, "t1", 70, 800, ArticleStatus.ONLINE, 0, purchaseItemId = 1),
            Article(2, "t2", 70, 800, ArticleStatus.DRAFT, 0, purchaseItemId = 1),
            Article(3, "t3", 70, 800, ArticleStatus.SOLD, 0, purchaseItemId = 1),
            Article(4, "t4", 70, 800, ArticleStatus.SOLD, 0, purchaseItemId = 1),
            Article(5, "t5", 70, 800, ArticleStatus.SOLD, 0, purchaseItemId = 1),
        )
        val sales = listOf(
            Sale(1, 3, "t3", "", 800, 70, 0, 0, null, shippingStatus = ShippingStatus.TO_SHIP),
            Sale(2, 4, "t4", "", 800, 70, 0, 0, null, shippingStatus = ShippingStatus.SHIPPED),
            Sale(3, 5, "t5", "", 800, 70, 0, 0, null, shippingStatus = ShippingStatus.DELIVERED),
        )
        val b = StockBoard.build(StockLedger.build(listOf(received, coming), arts), sales, today)
        org.junit.Assert.assertEquals(1, b.toList)
        org.junit.Assert.assertEquals(4, b.inHand) // 1 à mettre en vente + 1 brouillon + 1 en ligne + 1 colis à préparer
        org.junit.Assert.assertEquals(12, b.restocking)
        org.junit.Assert.assertEquals(1, b.shipping)
        org.junit.Assert.assertEquals(1, b.done)
        org.junit.Assert.assertEquals(1, b.neverReceived)
        org.junit.Assert.assertEquals(18, b.total) // 6 + 12 commandées reçues ou attendues
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 12, 3), b.orders.single().eta)
        org.junit.Assert.assertTrue(StockBoard.etaLabel(b.orders.single(), today).contains("dans 23 jours"))
    }
}

class InventoryTest {
    @Test
    fun countingFewerPiecesUsesFreePiecesThenDrafts() {
        val order = PurchaseOrder(1, "N", "A", 0, 0, 0, 0, listOf(PurchaseItem(1, 1, "Bracelet jonc (6)", null, 0, 4, 105, 3, "", "NH1")))
        val arts = listOf(
            Article(1, "Jonc", 105, 600, ArticleStatus.ONLINE, 0, purchaseItemId = 1),
            Article(2, "Jonc", 105, 600, ArticleStatus.DRAFT, 0, purchaseItemId = 1),
            Article(3, "Jonc", 105, 600, ArticleStatus.DRAFT, 0, purchaseItemId = 1, photos = listOf("a.jpg")),
        )
        val line = Inventory.lines(StockLedger.build(listOf(order), arts), emptyList()).single()
        org.junit.Assert.assertEquals(4, line.expected)
        val s = Inventory.shortfall(line, 2)
        org.junit.Assert.assertEquals(listOf(1L to 1), s.missing)
        org.junit.Assert.assertEquals(listOf(2L), s.withdrawDrafts.map { it.id }) // le brouillon sans photo d'abord
        org.junit.Assert.assertEquals(0, s.unresolved)
        org.junit.Assert.assertEquals(1, Inventory.shortfall(line, 0).unresolved)
    }
}
