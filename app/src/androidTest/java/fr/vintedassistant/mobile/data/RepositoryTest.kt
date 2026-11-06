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
import fr.vintedassistant.mobile.data.importer.ImportedListing
import fr.vintedassistant.mobile.data.importer.ParsedOrder
import fr.vintedassistant.mobile.data.importer.ParsedOrderLine
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

    @Test
    fun importedListingsAreMergedNotDuplicated() = runBlocking {
        val url = "https://www.vinted.fr/items/222-bracelet-dore"
        val first = repo.upsertImported(ImportedListing(222, url, "Bracelet doré", 600, favorites = 4, views = 22), sold = false)
        assertTrue(first.created)
        var article = repo.getArticle(first.articleId)!!
        assertEquals(ArticleStatus.ONLINE, article.status)
        assertEquals(4, article.favoritesCount)
        assertNull(article.lastVisibleAt) // date inconnue : prioritaire dans le plan de republication

        // L'utilisateur complète sa fiche, puis réimporte la page détaillée.
        repo.update(first.articleId, fields(price = 600).copy(title = "Mon bracelet", description = "Ma description", brand = "", vintedUrl = url))
        val again = repo.upsertImported(
            ImportedListing(222, url, "Bracelet doré", 500, description = "Texte Vinted", brand = "Sans marque", favorites = 5, detailed = true),
            sold = false,
        )
        assertTrue(!again.created)
        assertEquals(first.articleId, again.articleId)
        article = repo.getArticle(first.articleId)!!
        assertEquals("Mon bracelet", article.title) // jamais écrasé
        assertEquals("Ma description", article.description)
        assertEquals("Sans marque", article.brand) // champ vide complété
        assertEquals(500L, article.priceCents) // le prix suit Vinted
        assertEquals(5, article.favoritesCount)
        assertEquals(1, repo.articles.first().size)

        // Vue ensuite dans « Vendus ».
        repo.upsertImported(ImportedListing(222, url, "Bracelet doré", 500), sold = true)
        assertEquals(ArticleStatus.SOLD, repo.getArticle(first.articleId)!!.status)
    }

    @Test
    fun soldImportCreatesOneUndatedSaleAndKeywordsAreSplit() = runBlocking {
        val listing = ImportedListing(
            333, "https://www.vinted.fr/items/333-collier", "Collier", 1100,
            description = "Joli collier doré.\n\ncollier chapelet dore acier medaille",
            category = "Colliers", detailed = true,
        )
        val r = repo.upsertImported(listing, sold = true)
        repo.upsertImported(listing, sold = true) // deuxième import : pas de deuxième vente
        val sales = repo.sales.first()
        assertEquals(1, sales.size)
        assertEquals(1100L, sales.single().salePriceCents)
        assertTrue(!sales.single().dateKnown)
        val article = repo.getArticle(r.articleId)!!
        assertEquals("Joli collier doré.", article.description)
        assertEquals("collier chapelet dore acier medaille", article.keywords)
        assertEquals("Colliers", article.category)

        // Le coût saisi ensuite se reporte sur la vente importée.
        repo.update(r.articleId, fields(price = 1100).copy(title = "Collier", costCents = 300, vintedUrl = listing.url))
        assertEquals(300L, repo.sales.first().single().costCents)
    }

    @Test
    fun cleanUpCreatesMissingSalesAndReclassifiesNotices() = runBlocking {
        // Article vendu importé par une version précédente (sans vente).
        val id = repo.create(fields().copy(vintedUrl = "https://www.vinted.fr/items/444-pull"))
        repo.setStatus(id, ArticleStatus.SOLD)
        repo.recordNotification("n1", now, "grognack", "grognack: Merci")
        val report = repo.cleanUpImportedData()
        assertEquals(1, report.salesCreated)
        assertEquals(VintedEventKind.MESSAGE, repo.notices.first().single().kind)
        assertEquals(0, repo.cleanUpImportedData().salesCreated) // idempotent
    }

    @Test
    fun purchaseOrderLinksCostsAndCreatesDrafts() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        val order = ParsedOrder(
            "NH-1", null,
            listOf(ParsedOrderLine("Bracelet trèfle", 120, 3, "", ""), ParsedOrderLine("Collier tennis", 240, 1, "", "")),
            shippingCents = 480, feesCents = 0, totalCents = null, url = "",
        )
        val saved = purchases.saveOrder("Nihaojewelry", order, downloadImages = false)
        assertTrue(saved.created)
        assertTrue(!purchases.saveOrder("Nihaojewelry", order, downloadImages = false).created) // pas de doublon

        val items = purchases.orders.first().single().items
        val bracelet = items.first { it.name == "Bracelet trèfle" }
        assertEquals(216L, bracelet.landedUnitCents)

        // Fiche Vinted existante, vendue sans date : le coût se reporte sur la vente importée.
        val listing = ImportedListing(9, "https://www.vinted.fr/items/9-bracelet", "Bracelet", 600)
        val articleId = repo.upsertImported(listing, sold = true).articleId
        purchases.link(articleId, bracelet.id)
        assertEquals(216L, repo.getArticle(articleId)!!.costCents)
        assertEquals(216L, repo.sales.first().single().costCents)
        assertEquals(1, purchases.orders.first().single().items.first { it.id == bracelet.id }.linkedCount)

        val draftId = purchases.createDraft(bracelet.id)
        val draft = repo.getArticle(draftId)!!
        assertEquals(ArticleStatus.DRAFT, draft.status)
        assertEquals(216L, draft.costCents)
        assertEquals(2, purchases.orders.first().single().items.first { it.id == bracelet.id }.linkedCount)

        purchases.deleteOrder(saved.orderId)
        assertEquals(216L, repo.getArticle(articleId)!!.costCents) // coût conservé
        assertTrue(purchases.orders.first().isEmpty())
    }
}
