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
import fr.vintedassistant.mobile.domain.StockLedger
import fr.vintedassistant.mobile.domain.SaleValidation
import fr.vintedassistant.mobile.domain.ShippingStatus
import fr.vintedassistant.mobile.domain.VintedEventKind
import fr.vintedassistant.mobile.data.importer.ExportCandidate
import fr.vintedassistant.mobile.data.importer.ImportedListing
import fr.vintedassistant.mobile.data.importer.ExportReport
import fr.vintedassistant.mobile.data.importer.ExportOrderItem
import fr.vintedassistant.mobile.data.importer.ExportOrder
import fr.vintedassistant.mobile.data.importer.ExportListing
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
        val saved = purchases.saveOrder("Nihaojewelry", order, downloadImages = false, received = true)
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

        // Le réimport a remplacé la commande : on supprime la commande actuelle.
        purchases.deleteOrder(purchases.orders.first().single().id)
        assertEquals(216L, repo.getArticle(articleId)!!.costCents) // coût conservé
        assertTrue(purchases.orders.first().isEmpty())
    }

    @Test
    fun reimportUpdatesCostsAndKeepsLinks() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        // Première lecture erronée : prix d'origine au lieu de 0 €.
        val wrong = ParsedOrder("NH-FREE", null, listOf(ParsedOrderLine("Bracelet trèfle", 270, 1, "", ""), ParsedOrderLine("Collier", 100, 1, "", "")), 879, 0, null, "")
        purchases.saveOrder("Nihaojewelry", wrong, downloadImages = false)
        val item = purchases.orders.first().single().items.first { it.name == "Bracelet trèfle" }
        val articleId = repo.create(fields())
        purchases.link(articleId, item.id)
        assertEquals(item.landedUnitCents, repo.getArticle(articleId)!!.costCents)

        // Relecture correcte : produits offerts, seul le port compte.
        val right = wrong.copy(lines = wrong.lines.map { it.copy(unitPriceCents = 0) })
        val r = purchases.saveOrder("Nihaojewelry", right, downloadImages = false)
        assertTrue(!r.created)
        val order = purchases.orders.first().single()
        assertEquals(1, order.items.first { it.name == "Bracelet trèfle" }.linkedCount)
        assertEquals(440L, repo.getArticle(articleId)!!.costCents) // 8,79 € / 2 pièces, arrondi
    }

    @Test
    fun relaunchCopiesSoldListingOntoOldestFreeLot() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        purchases.saveOrder("Nihaojewelry", ParsedOrder("A", 1_000L, listOf(ParsedOrderLine("Cordon rouge", 100, 1, "", "")), 0, 0, null, ""), downloadImages = false, received = true)
        purchases.saveOrder("Nihaojewelry", ParsedOrder("B", 2_000L, listOf(ParsedOrderLine("Cordon rouge", 150, 2, "", "")), 0, 0, null, ""), downloadImages = false, received = true)
        val orders = purchases.orders.first()
        val lotA = orders.first { it.orderRef == "A" }.items.single()
        val lotB = orders.first { it.orderRef == "B" }.items.single()

        val sold = repo.upsertImported(ImportedListing(5, "https://www.vinted.fr/items/5-cordon", "Bracelet cordon rouge", 800, description = "Joli cordon"), sold = true).articleId
        purchases.link(sold, lotA.id)

        val stock = StockLedger.build(purchases.orders.first(), repo.getAllArticles())
        val product = stock.products.single()
        assertEquals(2, product.notListed)
        assertEquals(lotB.id, product.nextLot?.itemId)

        val copyId = purchases.duplicateForSale(sold, product.nextLot!!.itemId)
        val copy = repo.getArticle(copyId)!!
        assertEquals(ArticleStatus.DRAFT, copy.status)
        assertEquals("Bracelet cordon rouge", copy.title)
        assertEquals("Joli cordon", copy.description)
        assertEquals(800L, copy.priceCents)
        assertEquals(150L, copy.costCents)
        assertEquals("", copy.vintedUrl)
        assertEquals(lotB.id, copy.purchaseItemId)
        assertEquals(ArticleStatus.SOLD, repo.getArticle(sold)!!.status) // l'ancienne ne bouge pas
        assertEquals(1, repo.sales.first().size)

        // La copie publiée puis relue dans le dressing n'est pas dupliquée (même titre, sans lien).
        val merged = repo.upsertImported(ImportedListing(6, "https://www.vinted.fr/items/6-cordon", "Bracelet cordon rouge", 800), sold = false)
        assertEquals(copyId, merged.articleId)
        assertTrue(!merged.created)

        purchases.duplicateForSale(sold, lotB.id)
        try {
            purchases.duplicateForSale(sold, lotB.id)
            throw AssertionError("Le lot B n'a plus de pièce libre")
        } catch (e: IllegalStateException) {
            // attendu
        }
        assertEquals(0, StockLedger.build(purchases.orders.first(), repo.getAllArticles()).products.single().notListed)
    }

    @Test
    fun recentOrderIsInTransitAndReimportMatchesBySku() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        val first = ParsedOrder(
            "NHFR611", now - 60_000, listOf(
                ParsedOrderLine("Cordon (Rouge | B1)", 84, 11, "", "", sku = "NH59670045"),
                ParsedOrderLine("Croix (Or)", 100, 1, "", "", sku = "NH1111"),
            ), 1005, 0, null, "", deliveryEta = "mer. 25 nov. - mar. 1 déc.",
        )
        purchases.saveOrder("Nihaojewelry", first, downloadImages = false)
        var order = purchases.orders.first().single()
        assertTrue(!order.received)
        assertEquals("mer. 25 nov. - mar. 1 déc.", order.deliveryEta)
        val cordon = order.items.first { it.sku == "NH59670045" }
        val articleId = repo.create(fields())
        purchases.link(articleId, cordon.id) // préparation possible, mise en vente non
        try {
            purchases.createDraft(cordon.id)
            throw AssertionError("Commande pas encore reçue")
        } catch (e: IllegalStateException) {
            // attendu
        }

        purchases.setReceived(order.id, true)
        // Relecture où l'ordre des produits a changé : le rattachement suit le code produit.
        purchases.saveOrder("Nihaojewelry", first.copy(lines = first.lines.reversed()), downloadImages = false)
        order = purchases.orders.first().single()
        assertTrue(order.received) // conservé au réimport
        assertEquals(1, order.items.first { it.sku == "NH59670045" }.linkedCount)
        assertEquals(0, order.items.first { it.sku == "NH1111" }.linkedCount)
        purchases.createDraft(order.items.first { it.sku == "NH59670045" }.id)

        // Relecture qui trouve un produit manqué la première fois, inséré au milieu : les rattachements ne bougent pas.
        val withMissing = first.copy(lines = listOf(first.lines[1], ParsedOrderLine("Pendentif (3)", 74, 1, "", "", sku = "NH57425383"), first.lines[0]))
        purchases.saveOrder("Nihaojewelry", withMissing, downloadImages = false)
        order = purchases.orders.first().single()
        assertEquals(3, order.items.size)
        assertEquals(0, order.items.first { it.sku == "NH57425383" }.linkedCount)
        assertEquals(2, order.items.first { it.sku == "NH59670045" }.linkedCount)

        // Nom corrigé à la relecture (« Order Qty » retiré) : le code produit suffit à garder les rattachements.
        purchases.saveOrder("Nihaojewelry", withMissing.copy(lines = withMissing.lines.map { it.copy(name = it.name + " Order Qty : 1") }), downloadImages = false)
        assertEquals(2, purchases.orders.first().single().items.first { it.sku == "NH59670045" }.linkedCount)

        // Achat saisi à la main : reçu d'office.
        purchases.saveOrder("Saisie manuelle", ParsedOrder("MANUEL-1", null, listOf(ParsedOrderLine("Bague", 300, 1, "", "")), 0, 0, null, ""), downloadImages = false, received = true)
        assertTrue(purchases.orders.first().first { it.orderRef == "MANUEL-1" }.received)
    }

    @Test
    fun htmlExportMergesByItemIdDatesSalesAndAddsMissing() = runBlocking {
        // Fiche déjà importée par le navigateur : vendue, sans date.
        val sold = repo.upsertImported(ImportedListing(201, "https://www.vinted.fr/items/201-croix", "Croix dorée", 500), sold = true).articleId
        // Fiche en ligne liée à une ancienne annonce supprimée (republication).
        val republished = repo.create(fields().copy(title = "Bracelet trèfle noir", vintedUrl = "https://www.vinted.fr/items/150"))
        repo.markPublished(republished)
        val before = repo.articles.first().size

        val listings = listOf(
            ExportListing(201, "Croix dorée", "Belle croix", "Vintage", "", "Neuf sans étiquette", "Doré", 80, 0, 1_000L, 500, emptyList()),
            ExportListing(202, "Bracelet trèfle noir", "Joli", "", "", "", "", 30, 4, 2_000L, 500, emptyList()),
            ExportListing(203, "Collier tennis", "", "", "", "", "", 5, 2, 3_000L, 900, emptyList()),
            ExportListing(204, "Cordon rouge", "", "", "", "", "", 20, 0, 1_500L, 800, emptyList()),
        )
        val orders = listOf(
            ExportOrder(5_000L, "Commande finalisée", 450, listOf(ExportOrderItem("Croix dorée", 450))),
            ExportOrder(6_000L, "Commande envoyée", 800, listOf(ExportOrderItem("Cordon rouge", 800))),
        )
        val report = ExportReport(emptyList(), emptyList(), listings, orders, listOf(ExportListing(150, "Bracelet trèfle noir", "", "", "", "", "", 0, 0, 500L, 500, emptyList())))
        val r = repo.importVintedExport(report) { emptyMap() }

        assertEquals(2, r.updated)
        assertEquals(2, r.created) // collier en ligne, cordon vendu
        assertEquals(1, r.salesDated)
        assertEquals(1, r.salesAdded)
        assertEquals(before + 2, repo.articles.first().size)

        val croix = repo.getArticle(sold)!!
        assertEquals("Vintage", croix.brand) // champ vide complété
        val croixSale = repo.sales.first().first { it.articleId == sold }
        assertTrue(croixSale.dateKnown)
        assertEquals(5_000L, croixSale.soldAt)
        assertEquals(450L, croixSale.salePriceCents)

        val bracelet = repo.getArticle(republished)!!
        assertEquals("https://www.vinted.fr/items/202", bracelet.vintedUrl) // suit la nouvelle annonce
        assertEquals(ArticleStatus.ONLINE, bracelet.status)

        val cordon = repo.articles.first().first { it.title == "Cordon rouge" }
        assertEquals(ArticleStatus.SOLD, cordon.status)
        assertEquals(ShippingStatus.SHIPPED, repo.sales.first().first { it.articleId == cordon.id }.shippingStatus)
        assertEquals(ArticleStatus.ONLINE, repo.articles.first().first { it.title == "Collier tennis" }.status)

        // Deuxième import : rien de nouveau.
        val again = repo.importVintedExport(report) { emptyMap() }
        assertEquals(0, again.created)
        assertEquals(0, again.salesDated + again.salesAdded)
        assertEquals(before + 2, repo.articles.first().size)
    }

    @Test
    fun linksAreRestoredAfterDeleteAndReimport() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        val order = ParsedOrder("NHFR608", 1_000L, listOf(
            ParsedOrderLine("Bracelet cœur (Vert)", 0, 1, "", "", sku = "NH53003570"),
            ParsedOrderLine("Bracelet cœur (Rose)", 0, 1, "", "", sku = "NH53003567"),
        ), 879, 0, null, "")
        purchases.saveOrder("Nihaojewelry", order, downloadImages = false)
        assertTrue(purchases.orders.first().single().received) // aucune livraison annoncée : reçue
        val a = repo.create(fields())
        val b = repo.create(fields())
        val items = purchases.orders.first().single().items
        purchases.link(a, items.first { it.sku == "NH53003570" }.id)
        val backup = listOf(
            PurchaseRepository.LinkBackup(a, "NHFR608", "NH53003570", "Bracelet cœur (Vert) Order Qty : 1"),
            PurchaseRepository.LinkBackup(b, "NHFR608", "NH53003567", "Bracelet cœur (Rose)"),
            PurchaseRepository.LinkBackup(b, "NHFR608", "NH53003567", "Bracelet cœur (Rose)"), // doublon : ignoré
        )
        purchases.deleteOrder(purchases.orders.first().single().id)
        purchases.saveOrder("Nihaojewelry", order, downloadImages = false)
        assertEquals(2, purchases.restoreLinks(backup))
        val restored = purchases.orders.first().single().items
        assertEquals(1, restored.first { it.sku == "NH53003570" }.linkedCount)
        assertEquals(1, restored.first { it.sku == "NH53003567" }.linkedCount)
        assertEquals(440L, repo.getArticle(a)!!.costCents) // nouveau coût de revient : 8,79 € / 2
        assertEquals(0, purchases.restoreLinks(backup)) // déjà fait
    }

    @Test
    fun manualOrderDateSurvivesReimportWhenPageDateIsUnreadable() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        val order = ParsedOrder("NHFR608", null, listOf(ParsedOrderLine("Bracelet (Vert)", 0, 1, "", "", sku = "NH1")), 879, 0, null, "")
        purchases.saveOrder("Nihaojewelry", order, downloadImages = false)
        val id = purchases.orders.first().single().id
        purchases.setOrderedAt(id, 123_456L)
        purchases.saveOrder("Nihaojewelry", order, downloadImages = false)
        assertEquals(123_456L, purchases.orders.first().single().orderedAt)
        // Date lisible : elle l'emporte.
        purchases.saveOrder("Nihaojewelry", order.copy(orderedAt = 999L), downloadImages = false)
        assertEquals(999L, purchases.orders.first().single().orderedAt)
    }

    @Test
    fun correctionsFileIsCheckedThenApplied() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        val corrections = Corrections(repo, purchases)
        purchases.saveOrder("Nihaojewelry", ParsedOrder("AOUT", 1_000L, listOf(
            ParsedOrderLine("Romain (Or)", 0, 1, "", "", sku = "NH76"),
            ParsedOrderLine("Romain (Argent)", 0, 1, "", "", sku = "NH75"),
            ParsedOrderLine("Tennis (18 pouces)", 0, 1, "", "", sku = "NH25"),
        ), 879, 0, null, ""), downloadImages = false, received = true)
        purchases.saveOrder("Nihaojewelry", ParsedOrder("OCT", 2_000L, listOf(
            ParsedOrderLine("Tennis (18 pouces)", 50, 5, "", "", sku = "NH25"),
        ), 922, 0, null, ""), downloadImages = false, received = true)
        val items = purchases.orders.first().flatMap { o -> o.items.map { o.orderRef to it } }
        fun item(ref: String, sku: String) = items.first { it.first == ref && it.second.sku == sku }.second.id
        val argente = repo.create(fields().copy(title = "Gourmette argentée"))
        val draft = repo.create(fields().copy(title = "Romain brouillon"))
        val tennis = repo.create(fields().copy(title = "Tennis vendu en novembre"))
        val dore = repo.create(fields().copy(title = "Gourmette dorée"))
        val tennis2 = repo.create(fields().copy(title = "Tennis de septembre"))
        purchases.link(argente, item("AOUT", "NH76"))
        purchases.link(draft, item("AOUT", "NH75"))
        purchases.link(tennis, item("AOUT", "NH25"))

        val json = """{"format":"vinted-assistant-corrections","version":1,
          "orderDates":[{"ref":"AOUT","date":"2026-08-20"}],
          "deleteArticles":[{"id":$draft,"title":"Romain brouillon","status":"Brouillon"},{"id":$dore,"title":"Pas ce titre"}],
          "links":[
            {"articleId":$argente,"title":"Gourmette argentée","orderRef":"AOUT","sku":"NH75"},
            {"articleId":$dore,"title":"Gourmette dorée","orderRef":"AOUT","sku":"NH76"},
            {"articleId":$tennis,"title":"Tennis vendu","orderRef":"OCT","sku":"NH25"},
            {"articleId":$tennis2,"title":"Tennis de septembre","orderRef":"AOUT","sku":"NH25"}
          ]}"""
        val steps = corrections.plan(json)
        assertEquals(listOf(true, true, false, true, true, true, true), steps.map { it.ok }) // titre différent : écarté
        assertEquals(6, corrections.apply(steps))

        assertNull(repo.getArticle(draft))
        assertTrue(repo.getArticle(dore) != null) // non supprimée : titre différent
        assertEquals(item("AOUT", "NH75"), repo.getArticle(argente)!!.purchaseItemId)
        assertEquals(item("AOUT", "NH76"), repo.getArticle(dore)!!.purchaseItemId)
        assertEquals(item("OCT", "NH25"), repo.getArticle(tennis)!!.purchaseItemId)
        assertEquals(item("AOUT", "NH25"), repo.getArticle(tennis2)!!.purchaseItemId)
        val aout = purchases.orders.first().first { it.orderRef == "AOUT" }
        assertEquals(8, java.time.Instant.ofEpochMilli(aout.orderedAt).atZone(java.time.ZoneId.systemDefault()).monthValue)
        assertTrue(corrections.plan(json).none { it.ok && it.label.contains("→") }) // tout est déjà fait
    }

    @Test
    fun saleNotificationRecordsTheSaleToShip() = runBlocking {
        val id = repo.create(fields(price = 900).copy(title = "Collier Chaîne Tennis Strass Argenté 45cm 4mm Unisexe Iced Out"))
        repo.markPublished(id)
        val auto = repo.handleNotification("k1", now, "Vinted", "Collier Chaîne Tennis Strass Argenté 45cm… s'est vendu pour 7,50 €")
        assertEquals(id, auto?.articleId)
        assertEquals(750L, auto?.priceCents)
        assertEquals(ArticleStatus.SOLD, repo.getArticle(id)!!.status)
        val sale = repo.sales.first().single()
        assertEquals(ShippingStatus.TO_SHIP, sale.shippingStatus)
        assertEquals(750L, sale.salePriceCents)
        assertTrue(repo.notices.first().single().handled)
        // Même notification reçue deux fois : rien de plus.
        assertNull(repo.handleNotification("k1", now, "Vinted", "Collier Chaîne Tennis Strass Argenté 45cm… s'est vendu pour 7,50 €"))
        assertEquals(1, repo.sales.first().size)
    }

    @Test
    fun dressingImportOfAnOnlineListingNowSoldCreatesASaleToShip() = runBlocking {
        val url = "https://www.vinted.fr/items/555-collier"
        val created = repo.upsertImported(ImportedListing(555, url, "Collier", 900), sold = false).articleId
        repo.upsertImported(ImportedListing(555, url, "Collier", 900), sold = true)
        val sale = repo.sales.first().single { it.articleId == created }
        assertEquals(ShippingStatus.TO_SHIP, sale.shippingStatus)
        assertTrue(sale.dateKnown)
    }

    @Test
    fun fullBackupContainsDatabaseAndIsAcceptedForRestore() = runBlocking {
        val id = repo.create(fields())
        repo.recordSale(id, SaleValidation.Valid(2000, 0, ""))
        val prefs = context.getSharedPreferences("settings-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        prefs.edit().putInt("contribution_rate_bp", 1230).putBoolean("reminder_enabled", true).commit()
        val backup = Backup(context, db, prefs, closeDatabase = {})
        val out = java.io.ByteArrayOutputStream()
        val written = backup.writeTo(out, "test")
        assertEquals(1, written.articles)
        assertEquals(1, written.sales)
        val info = backup.prepare(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, info.articles)
        assertEquals(1, info.sales)
        assertEquals(VintedBoostDatabase.VERSION, info.schema)
        backup.discardPrepared()
        // Réglages : aller-retour fidèle.
        val json = Backup.prefsToJson(prefs)
        prefs.edit().clear().commit()
        Backup.jsonToPrefs(json, prefs)
        assertEquals(1230, prefs.getInt("contribution_rate_bp", 0))
        assertTrue(prefs.getBoolean("reminder_enabled", false))
    }

    @Test
    fun foreignZipIsRefusedForRestore() = runBlocking {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("donnees.json")); z.write("{}".toByteArray()); z.closeEntry()
        }
        val backup = Backup(context, db, context.getSharedPreferences("x", Context.MODE_PRIVATE), closeDatabase = {})
        val refused = try { backup.prepare(java.io.ByteArrayInputStream(out.toByteArray())); false } catch (e: IllegalArgumentException) { true }
        assertTrue(refused)
    }

    @Test
    fun saleWithoutFicheIsAttachedToTheShippedPiece() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        val order = ParsedOrder("NH-9", null, listOf(ParsedOrderLine("Bracelet ange", 120, 2, "", "")),
            shippingCents = 0, feesCents = 0, totalCents = null, url = "")
        purchases.saveOrder("Nihaojewelry", order, downloadImages = false, received = true)
        val item = purchases.orders.first().single().items.single()
        val saleId = db.saleDao().insert(
            fr.vintedassistant.mobile.data.local.SaleEntity(
                articleId = null, title = "Pendentif croix strass", category = "Pendentifs", salePriceCents = 280,
                costCents = 0, feesCents = 0, soldAt = now, listedAt = null, buyer = "", shippingStatus = "Livrée",
                shippedAt = null, labelFile = null, notes = "",
            ),
        )
        val articleId = purchases.attachSaleToPurchase(saleId, item.id)
        val sale = repo.sales.first().single()
        assertEquals(articleId, sale.articleId)
        assertEquals(item.landedUnitCents, sale.costCents)
        assertEquals("Pendentif croix strass", sale.title)
        assertEquals(ArticleStatus.SOLD, repo.getArticle(articleId)!!.status)
        assertEquals(1, purchases.orders.first().single().items.single().linkedCount)
        val again = try { purchases.attachSaleToPurchase(saleId, item.id); false } catch (e: IllegalStateException) { true }
        assertTrue(again)
    }

    @Test
    fun missingPiecesLeaveStockAndShippingIsReallocated() = runBlocking {
        val purchases = PurchaseRepository(db, photoStore = null, clock = { now })
        val order = ParsedOrder("NH-M", null,
            listOf(ParsedOrderLine("Bracelet fleurs", 0, 1, "", "", sku = "NH1"), ParsedOrderLine("Collier tennis", 0, 1, "", "", sku = "NH2")),
            shippingCents = 800, feesCents = 0, totalCents = null, url = "")
        purchases.saveOrder("Nihaojewelry", order, downloadImages = false, received = true)
        val items = purchases.orders.first().single().items
        assertEquals(400L, items.first { it.sku == "NH2" }.landedUnitCents)
        purchases.setMissing(items.first { it.sku == "NH1" }.id, 1)
        val after = purchases.orders.first().single().items
        assertEquals(0, after.first { it.sku == "NH1" }.delivered)
        assertEquals(800L, after.first { it.sku == "NH2" }.landedUnitCents)
        // Relecture de la commande : la pièce manquante reste signalée.
        purchases.saveOrder("Nihaojewelry", order, downloadImages = false, received = true)
        assertEquals(1, purchases.orders.first().single().items.first { it.sku == "NH1" }.missing)
    }
}
