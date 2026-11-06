package fr.vintedassistant.mobile.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter
import fr.vintedassistant.mobile.data.local.ListingEventEntity
import fr.vintedassistant.mobile.data.local.PhotoEntity
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import fr.vintedassistant.mobile.domain.ArticleFields
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.SaleValidation
import fr.vintedassistant.mobile.domain.ShippingStatus
import fr.vintedassistant.mobile.domain.VintedEventKind
import fr.vintedassistant.mobile.data.importer.ExportCandidate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Base Room réelle (en mémoire) sur Android : chaque opération métier du dépôt. */
@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: VintedBoostDatabase
    private lateinit var repo: ArticleRepository
    private val prefsName = "legacy-test-${UUID.randomUUID()}"
    private var now = 1_000_000L

    private fun fields(price: Long = 2500, floor: Long? = null) = ArticleFields(
        title = "Pull", costCents = 500, priceCents = price, floorPriceCents = floor,
        brand = "Zara", size = "M", category = "Femmes", condition = "Bon état", color = "Bleu",
        description = "Joli pull", keywords = "pull",
    )

    @Before
    fun setUp() {
        db = VintedBoostDatabase.create(context, name = null)
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        repo = ArticleRepository(db, LegacyInventoryImporter(prefs, db.articleDao()), clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteSharedPreferences(prefsName)
    }

    @Test
    fun createUpdateAndPriceJournal() = runBlocking {
        val id = repo.create(fields())
        repo.update(id, fields(price = 2000, floor = 1800))
        val article = repo.getArticle(id)!!
        assertEquals(2000L, article.priceCents)
        assertEquals(1800L, article.floorPriceCents)
        assertEquals("Zara", article.brand)
        assertEquals(ArticleStatus.DRAFT, article.status)
        assertEquals(1, db.listingEventDao().countSince(ListingEventEntity.PRICE_CHANGED, 0))
    }

    @Test
    fun publishThenRepublishUpdatesDatesAndCounts() = runBlocking {
        val id = repo.create(fields())
        repo.markPublished(id)
        now += 10_000
        repo.markRepublished(id)
        val article = repo.getArticle(id)!!
        assertEquals(ArticleStatus.ONLINE, article.status)
        assertEquals(1_000_000L, article.firstPublishedAt)
        assertEquals(1_010_000L, article.lastPublishedAt)
        assertEquals(listOf(1_010_000L), repo.republishedTimestamps.first())
        assertEquals(1, repo.countRepublishedSince(1_005_000L))
    }

    @Test
    fun applyPriceLowersFloorWhenNeeded() = runBlocking {
        val id = repo.create(fields(price = 2500, floor = 2200))
        repo.applyPrice(id, 2000)
        val article = repo.getArticle(id)!!
        assertEquals(2000L, article.priceCents)
        assertNull(article.floorPriceCents)
    }

    @Test
    fun saleSnapshotsArticleAndCancelPutsItBackOnline() = runBlocking {
        val id = repo.create(fields())
        repo.markPublished(id)
        val saleId = repo.recordSale(id, SaleValidation.Valid(salePriceCents = 2200, feesCents = 100, buyer = "lea"))
        assertEquals(ArticleStatus.SOLD, repo.getArticle(id)!!.status)
        val sale = repo.sales.first().single()
        assertEquals(500L, sale.costCents)
        assertEquals("Pull", sale.title)
        assertEquals("Femmes", sale.category)
        assertEquals(ShippingStatus.TO_SHIP, sale.shippingStatus)

        repo.setShippingStatus(saleId, ShippingStatus.CANCELLED)
        assertEquals(ArticleStatus.ONLINE, repo.getArticle(id)!!.status)
        assertEquals(ShippingStatus.CANCELLED, repo.sales.first().single().shippingStatus)
    }

    @Test
    fun deleteRemovesPhotosButKeepsSales() = runBlocking {
        val id = repo.create(fields())
        db.photoDao().insert(PhotoEntity(articleId = id, fileName = "a.jpg", position = 0, createdAt = now))
        db.photoDao().insert(PhotoEntity(articleId = id, fileName = "b.jpg", position = 1, createdAt = now))
        assertEquals(listOf("a.jpg", "b.jpg"), repo.articles.first().single().photos)

        repo.recordSale(id, SaleValidation.Valid(2000, 0, ""))
        repo.delete(id)

        assertTrue(repo.articles.first().isEmpty())
        assertEquals(0, db.photoDao().forArticle(id).size)
        val sale = repo.sales.first().single()
        assertNull(sale.articleId)
        assertEquals("Pull", sale.title)
    }

    @Test
    fun legacyImportIsIdempotentAndKeepsOriginal() = runBlocking {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val raw = """[{"id":1760777000000,"title":"Veste","cost":8.5,"price":25.0,"status":"Brouillon"}]"""
        prefs.edit().putString(LegacyInventoryImporter.KEY_ITEMS, raw).commit()

        val first = repo.importLegacyIfNeeded()
        assertEquals(LegacyInventoryImporter.Outcome.Imported(count = 1, skipped = 0), first)
        assertEquals(LegacyInventoryImporter.Outcome.AlreadyDone, repo.importLegacyIfNeeded())

        val articles = repo.articles.first()
        assertEquals(1, articles.size)
        assertEquals(850L, articles.single().costCents)
        assertNotNull(prefs.getString(LegacyInventoryImporter.KEY_ITEMS, null))

        // Même si le drapeau est effacé, pas de doublon.
        prefs.edit().remove(LegacyInventoryImporter.KEY_DONE).commit()
        assertEquals(LegacyInventoryImporter.Outcome.Imported(count = 0, skipped = 0), repo.importLegacyIfNeeded())
        assertEquals(1, repo.articles.first().size)
    }

    @Test
    fun corruptLegacyDataIsNotMarkedDone() = runBlocking {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(LegacyInventoryImporter.KEY_ITEMS, "{pas du json").commit()
        assertTrue(repo.importLegacyIfNeeded() is LegacyInventoryImporter.Outcome.Failed)
        assertEquals(false, prefs.getBoolean(LegacyInventoryImporter.KEY_DONE, false))
    }

    @Test
    fun vintedNotificationIsClassifiedLinkedAndDeduplicated() = runBlocking {
        val id = repo.create(fields())
        assertTrue(repo.recordNotification("k1", now, "Nouvelle offre", "lea te propose 18 € pour Pull"))
        assertTrue(!repo.recordNotification("k1", now, "Nouvelle offre", "lea te propose 18 € pour Pull"))
        val notice = repo.notices.first().single()
        assertEquals(VintedEventKind.OFFER, notice.kind)
        assertEquals(id, notice.articleId)
        assertEquals(1800L, notice.amountCents)
        repo.markNoticeHandled(notice.id)
        assertTrue(repo.notices.first().single().handled)
    }

    @Test
    fun oldNotificationsArePurged() = runBlocking {
        repo.recordNotification("old", 0, "Vinted", "ancienne")
        now += 61L * 24 * 60 * 60 * 1000
        repo.recordNotification("new", now, "Vinted", "récente")
        assertEquals(listOf("récente"), repo.notices.first().map { it.text })
    }

    @Test
    fun exportImportSkipsExistingArticles() = runBlocking {
        repo.create(fields(price = 2500))
        val added = repo.importFromExport(
            listOf(
                ExportCandidate("pull", 2500), // même nom (casse ignorée) et même prix : ignoré
                ExportCandidate("Robe", 1999, brand = "Zara"),
                ExportCandidate("Robe", 1999),
            )
        )
        assertEquals(1, added)
        val robe = repo.articles.first().first { it.title == "Robe" }
        assertEquals(ArticleStatus.DRAFT, robe.status)
        assertEquals("Zara", robe.brand)
        assertEquals(0L, robe.costCents)
    }

    @Test
    fun vintedLinkIsStored() = runBlocking {
        val id = repo.create(fields())
        repo.setVintedUrl(id, "https://www.vinted.fr/items/1-pull")
        assertEquals("https://www.vinted.fr/items/1-pull", repo.getArticle(id)!!.vintedUrl)
    }
}
