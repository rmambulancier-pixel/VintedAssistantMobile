package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockLedgerTest {

    private fun item(id: Long, orderId: Long, name: String, qty: Int, landed: Long, image: String = "https://img.nihaojewelry.com/p/$name.jpg?w=200") =
        PurchaseItem(id, orderId, name, null, landed - 10, qty, landed, 0, image)

    private fun order(id: Long, at: Long, vararg items: PurchaseItem) =
        PurchaseOrder(id, "Nihaojewelry", "REF$id", at, 0, 0, 0, items.toList())

    private fun article(id: Long, status: ArticleStatus, itemId: Long?, title: String = "Fiche $id", createdAt: Long = id) =
        Article(id = id, title = title, costCents = 0, priceCents = 900, status = status, createdAt = createdAt, purchaseItemId = itemId)

    @Test
    fun sameJewelInTwoOrdersIsOneProductWithTwoLots() {
        val august = order(1, 1_000, item(10, 1, "Bracelet trèfle (Argent | 17cm)", 3, 103))
        val october = order(2, 2_000, item(20, 2, "Bracelet trèfle (Argent | 17cm)", 2, 110))
        val stock = StockLedger.build(listOf(october, august), emptyList())
        assertEquals(1, stock.products.size)
        val p = stock.products.single()
        assertEquals(listOf(10L, 20L), p.lots.map { it.itemId })
        assertEquals(5, p.bought)
        assertEquals(5, p.notListed)
        assertTrue(p.costVaries)
        assertEquals(10L, p.nextLot?.itemId)
    }

    @Test
    fun variantsStaySeparate() {
        val o = order(1, 1_000,
            item(10, 1, "Bracelet Cœur (Vert)", 1, 148, image = "https://img/a.jpg"),
            item(11, 1, "Bracelet Cœur (Rouge)", 1, 148, image = "https://img/b.jpg"),
        )
        assertEquals(2, StockLedger.build(listOf(o), emptyList()).products.size)
    }

    @Test
    fun accentsAndCaseDoNotSplitAProduct() {
        val a = order(1, 1_000, item(10, 1, "Bracelet Trèfle doré", 1, 100, image = "https://x/1.jpg?a=1"))
        val b = order(2, 2_000, item(20, 2, "bracelet trefle DORE", 1, 120, image = "https://x/1.jpg?a=2"))
        assertEquals(1, StockLedger.build(listOf(a, b), emptyList()).products.size)
    }

    @Test
    fun piecesAreCountedByStatusAndOldestLotIsUsedFirst() {
        val august = order(1, 1_000, item(10, 1, "Cordon rouge", 2, 108))
        val october = order(2, 2_000, item(20, 2, "Cordon rouge", 3, 120))
        val articles = listOf(
            article(1, ArticleStatus.SOLD, 10),
            article(2, ArticleStatus.SOLD, 10),
            article(3, ArticleStatus.ONLINE, 20),
            article(4, ArticleStatus.DRAFT, null),
        )
        val stock = StockLedger.build(listOf(august, october), articles)
        val p = stock.products.single()
        assertEquals(5, p.bought)
        assertEquals(2, p.sold)
        assertEquals(1, p.forSale)
        assertEquals(2, p.notListed)
        // Le lot d'août est épuisé : la prochaine fiche prend le lot d'octobre et son coût.
        assertEquals(20L, p.nextLot?.itemId)
        assertEquals(120L, p.nextLot?.landedUnitCents)
        assertEquals(listOf(4L), stock.unlinked.map { it.id })
        assertEquals(2 * 120L, stock.notListedValueCents)
        assertEquals(p, stock.productOf(articles[0]))
        assertNull(stock.productOf(articles[3]))
    }

    @Test
    fun templateIsTheNewestArticleWithContent() {
        val o = order(1, 1_000, item(10, 1, "Croix", 3, 100))
        val old = article(1, ArticleStatus.SOLD, 10, createdAt = 1).copy(description = "Belle croix")
        val empty = article(2, ArticleStatus.DRAFT, 10, createdAt = 5)
        val p = StockLedger.build(listOf(o), listOf(old, empty)).products.single()
        assertEquals(1L, p.template?.id)
        assertEquals(1, p.notListed)
    }

    @Test
    fun overLinkedIsFlaggedAndRemainingNeverNegative() {
        val o = order(1, 1_000, item(10, 1, "Collier", 1, 100))
        val p = StockLedger.build(listOf(o), listOf(article(1, ArticleStatus.SOLD, 10), article(2, ArticleStatus.SOLD, 10))).products.single()
        assertTrue(p.overLinked)
        assertEquals(0, p.notListed)
        assertNull(p.nextLot)
    }

    @Test
    fun articleLinkedToADeletedOrderCountsAsUnlinked() {
        val stock = StockLedger.build(emptyList(), listOf(article(1, ArticleStatus.ONLINE, 999)))
        assertEquals(1, stock.unlinked.size)
        assertEquals(0, stock.bought)
    }

    @Test
    fun similarityFindsTheMatchingPurchase() {
        val title = "Bracelet trèfle argenté strass brillants style joaillerie"
        val good = StockLedger.similarity(title, "Bracelet unisexe en acier inoxydable 304 doré avec motif trèfle à quatre feuilles")
        val bad = StockLedger.similarity(title, "Pendentif Croix en acier inoxydable 316L")
        assertTrue(good > bad)
        assertFalse(StockLedger.similarity("", "x") > 0)
    }
}
