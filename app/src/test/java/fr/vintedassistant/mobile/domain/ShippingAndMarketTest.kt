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
