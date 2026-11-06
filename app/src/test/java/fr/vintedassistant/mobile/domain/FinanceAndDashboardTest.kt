package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FinanceAndDashboardTest {

    private val zone = ZoneId.of("Europe/Paris")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sale(price: Long, cost: Long, soldAt: Long, listedAt: Long? = null, fees: Long = 0,
                     category: String = "", status: ShippingStatus = ShippingStatus.DELIVERED) =
        Sale(0, null, "x", category, price, cost, fees, soldAt, listedAt, shippingStatus = status)

    @Test
    fun contributionsAreRoundedHalfUp() {
        assertEquals(615L, Finance.contributionsCents(5000, 1230)) // 12,30 % de 50 €
        assertEquals(0L, Finance.contributionsCents(5000, 0))
        assertEquals(1L, Finance.contributionsCents(5, 1000)) // 0,5 centime arrondi à 1
    }

    @Test
    fun netProfitDeductsEverything() {
        val s = sale(price = 5000, cost = 1500, soldAt = 0, fees = 200)
        assertEquals(5000L - 1500 - 200 - 615, Finance.netCents(s, 1230))
    }

    @Test
    fun rateParsing() {
        assertEquals(1230, Finance.parseRatePercent("12,3"))
        assertEquals(1230, Finance.parseRatePercent("12,30 %"))
        assertEquals(0, Finance.parseRatePercent("0"))
        assertNull(Finance.parseRatePercent("101"))
        assertNull(Finance.parseRatePercent("abc"))
        assertEquals("12,30 %", Finance.formatRate(1230))
    }

    @Test
    fun saleValidation() {
        assertTrue(validateSale("22", "", "lea") is SaleValidation.Valid)
        assertEquals(150L, (validateSale("22", "1,50", "") as SaleValidation.Valid).feesCents)
        assertTrue(validateSale("", "", "") is SaleValidation.Invalid)
        assertTrue(validateSale("10", "x", "") is SaleValidation.Invalid)
    }

    @Test
    fun priceDropRespectsFloorAndRounding() {
        assertEquals(2250L, PriceAdvisor.suggestDrop(2500, null, 10))
        assertEquals(2300L, PriceAdvisor.suggestDrop(2500, 2300, 10))
        assertEquals(1750L, PriceAdvisor.suggestDrop(1999, null, 10)) // 17,99 arrondi à 17,50
        assertNull(PriceAdvisor.suggestDrop(2500, 2500, 10))
        assertNull(PriceAdvisor.suggestDrop(2500, null, 0))
    }

    @Test
    fun dashboardSeparatesMonthAndTotalAndIgnoresCancelled() {
        val now = at(2026, 11, 15)
        val sales = listOf(
            sale(2000, 500, at(2026, 11, 2), listedAt = at(2026, 10, 28), category = "Femmes"),
            sale(3000, 1000, at(2026, 10, 20), listedAt = at(2026, 10, 10), category = "Femmes"),
            sale(9999, 0, at(2026, 11, 3), status = ShippingStatus.CANCELLED),
            sale(1000, 200, at(2026, 11, 10), status = ShippingStatus.TO_SHIP, category = "Hommes"),
        )
        val stats = computeDashboard(emptyList(), sales, DashboardSettings(0, 21, 10), now, zone)
        assertEquals(2, stats.monthSalesCount)
        assertEquals(3000L, stats.monthRevenueCents)
        assertEquals(3000L - 700, stats.monthNetCents)
        assertEquals(3, stats.totalSalesCount)
        assertEquals(6000L, stats.totalRevenueCents)
        assertEquals(1, stats.toShipCount)
        assertEquals(7.5, stats.averageDaysToSell!!, 0.01) // (5 + 10) / 2
        assertEquals("Femmes", stats.categories.first().category)
        assertEquals(2, stats.categories.first().salesCount)
    }

    @Test
    fun dashboardFindsStagnantOnlineArticles() {
        val now = at(2026, 11, 30)
        val old = Article(1, "Vieux", 500, 2000, ArticleStatus.ONLINE, 0, firstPublishedAt = at(2026, 11, 1))
        val recent = old.copy(id = 2, lastPublishedAt = at(2026, 11, 25))
        val draft = old.copy(id = 3, status = ArticleStatus.DRAFT)
        val sold = old.copy(id = 4, status = ArticleStatus.SOLD)
        val stats = computeDashboard(listOf(old, recent, draft, sold), emptyList(), DashboardSettings(0, 21, 10), now, zone)
        assertEquals(listOf(1L), stats.stagnant.map { it.article.id })
        assertEquals(29, stats.stagnant.single().daysOnline)
        assertEquals(1800L, stats.stagnant.single().suggestedPriceCents)
        assertEquals(3, stats.stockCount) // vendu exclu
        assertEquals(1, stats.draftCount)
        assertEquals(1500L * 3, stats.potentialMarginCents)
    }
}
