package fr.vintedassistant.mobile.data

import android.net.Uri
import androidx.room.withTransaction
import fr.vintedassistant.mobile.data.importer.ExportCandidate
import fr.vintedassistant.mobile.data.importer.ImportedListing
import fr.vintedassistant.mobile.data.files.ImageDownloader
import fr.vintedassistant.mobile.domain.VintedLinks
import fr.vintedassistant.mobile.domain.ListingTemplates
import fr.vintedassistant.mobile.domain.RelativeDates
import fr.vintedassistant.mobile.data.importer.VintedExportParser
import fr.vintedassistant.mobile.data.files.LabelStore
import fr.vintedassistant.mobile.data.files.PhotoStore
import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter
import fr.vintedassistant.mobile.data.local.ArticleEntity
import fr.vintedassistant.mobile.data.local.ListingEventEntity
import fr.vintedassistant.mobile.data.local.PhotoEntity
import fr.vintedassistant.mobile.data.local.SaleEntity
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import fr.vintedassistant.mobile.data.local.VintedNotificationEntity
import fr.vintedassistant.mobile.data.local.toDomain
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleFields
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.NewArticle
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.SaleValidation
import fr.vintedassistant.mobile.domain.ShippingStatus
import fr.vintedassistant.mobile.domain.DAY_MS
import fr.vintedassistant.mobile.domain.VintedEventKind
import fr.vintedassistant.mobile.domain.VintedNotice
import fr.vintedassistant.mobile.domain.VintedNotificationClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Point d'accès unique aux données pour les écrans : articles, photos, ventes, journal.
 * Toute opération qui touche plusieurs tables passe par une transaction.
 */
class ArticleRepository(
    private val db: VintedBoostDatabase,
    private val importer: LegacyInventoryImporter,
    private val photoStore: PhotoStore? = null,
    private val labelStore: LabelStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val articleDao = db.articleDao()
    private val photoDao = db.photoDao()
    private val saleDao = db.saleDao()
    private val eventDao = db.listingEventDao()
    private val noticeDao = db.vintedNotificationDao()

    val articles: Flow<List<Article>> =
        combine(articleDao.observeAll(), photoDao.observeAll()) { articles, photos ->
            val byArticle = photos.groupBy({ it.articleId }, { it.fileName })
            articles.map { it.toDomain(byArticle[it.id].orEmpty()) }
        }

    val sales: Flow<List<Sale>> = saleDao.observeAll().map { list -> list.map { it.toDomain() } }

    val notices: Flow<List<VintedNotice>> = noticeDao.observeRecent().map { list ->
        list.map {
            VintedNotice(it.id, it.postedAt, it.title, it.text, VintedEventKind.fromName(it.kind), it.articleId, it.amountCents, it.handled)
        }
    }

    val republishedTimestamps: Flow<List<Long>> = eventDao.observeTimestamps(ListingEventEntity.REPUBLISHED)

    suspend fun importLegacyIfNeeded(): LegacyInventoryImporter.Outcome = importer.importIfNeeded()

    suspend fun getArticle(id: Long): Article? {
        val entity = articleDao.getById(id) ?: return null
        return entity.toDomain(photoDao.forArticle(id).map { it.fileName })
    }

    suspend fun getAllArticles(): List<Article> = articleDao.getAll().map { it.toDomain() }

    suspend fun countRepublishedSince(since: Long): Int =
        eventDao.countSince(ListingEventEntity.REPUBLISHED, since)

    // ---------- Articles ----------

    /** Formulaire rapide (nom, coût, prix). */
    suspend fun add(article: NewArticle): Long {
        val now = clock()
        return articleDao.insert(
            ArticleEntity(
                title = article.title,
                costCents = article.costCents,
                priceCents = article.priceCents,
                status = ArticleStatus.DRAFT.label,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun create(fields: ArticleFields): Long {
        val now = clock()
        return articleDao.insert(
            ArticleEntity(
                title = fields.title,
                costCents = fields.costCents,
                priceCents = fields.priceCents,
                status = ArticleStatus.DRAFT.label,
                createdAt = now,
                updatedAt = now,
            ).withFields(fields)
        )
    }

    /** Met à jour la fiche. Un changement de prix est inscrit au journal. */
    suspend fun update(id: Long, fields: ArticleFields) = db.withTransaction {
        val current = articleDao.getById(id) ?: return@withTransaction
        val now = clock()
        articleDao.update(current.withFields(fields).copy(updatedAt = now))
        if (current.costCents != fields.costCents) saleDao.syncCostForImported(id, fields.costCents)
        if (current.priceCents != fields.priceCents) {
            eventDao.insert(ListingEventEntity(articleId = id, type = ListingEventEntity.PRICE_CHANGED, at = now, priceCents = fields.priceCents))
        }
    }

    suspend fun setStatus(id: Long, status: ArticleStatus) {
        val current = articleDao.getById(id) ?: return
        articleDao.update(current.copy(status = status.label, updatedAt = clock()))
    }

    /** L'utilisateur a publié l'annonce sur Vinted. */
    suspend fun markPublished(id: Long) = db.withTransaction {
        val current = articleDao.getById(id) ?: return@withTransaction
        val now = clock()
        articleDao.update(
            current.copy(
                status = ArticleStatus.ONLINE.label,
                firstPublishedAt = current.firstPublishedAt ?: now,
                lastPublishedAt = now,
                updatedAt = now,
            )
        )
        eventDao.insert(ListingEventEntity(articleId = id, type = ListingEventEntity.PUBLISHED, at = now, priceCents = current.priceCents))
    }

    /** L'utilisateur a republié l'annonce sur Vinted. */
    suspend fun markRepublished(id: Long) = db.withTransaction {
        val current = articleDao.getById(id) ?: return@withTransaction
        val now = clock()
        articleDao.update(
            current.copy(
                status = ArticleStatus.ONLINE.label,
                firstPublishedAt = current.firstPublishedAt ?: now,
                lastPublishedAt = now,
                updatedAt = now,
            )
        )
        eventDao.insert(ListingEventEntity(articleId = id, type = ListingEventEntity.REPUBLISHED, at = now, priceCents = current.priceCents))
    }

    /** Nouveau prix appliqué (par exemple une baisse suggérée), inscrit au journal. */
    suspend fun applyPrice(id: Long, priceCents: Long) = db.withTransaction {
        val current = articleDao.getById(id) ?: return@withTransaction
        if (current.priceCents == priceCents) return@withTransaction
        val now = clock()
        val floor = current.floorPriceCents?.takeIf { it <= priceCents }
        articleDao.update(current.copy(priceCents = priceCents, floorPriceCents = floor, updatedAt = now))
        eventDao.insert(ListingEventEntity(articleId = id, type = ListingEventEntity.PRICE_CHANGED, at = now, priceCents = priceCents))
    }

    /** Supprime l'article, ses photos et son journal. Les ventes passées sont conservées. */
    suspend fun delete(id: Long): Int {
        val files = photoDao.forArticle(id).map { it.fileName }
        val deleted = db.withTransaction {
            photoDao.deleteForArticle(id)
            eventDao.deleteForArticle(id)
            saleDao.detachArticle(id)
            articleDao.deleteById(id)
        }
        files.forEach { photoStore?.delete(it) }
        return deleted
    }

    // ---------- Photos ----------

    /** Importe les photos choisies. Renvoie le nombre d'échecs. */
    suspend fun addPhotos(articleId: Long, uris: List<Uri>): Int {
        val store = photoStore ?: return uris.size
        var failures = 0
        for (uri in uris) {
            val name = try {
                store.import(uri)
            } catch (e: Exception) {
                failures++
                continue
            }
            try {
                val position = photoDao.maxPosition(articleId) + 1
                photoDao.insert(PhotoEntity(articleId = articleId, fileName = name, position = position, createdAt = clock()))
            } catch (e: Exception) {
                store.delete(name)
                failures++
            }
        }
        return failures
    }

    /**
     * Version améliorée d'une photo (détourage, fond clair, lumière), ajoutée en première position.
     * L'originale est conservée. Renvoie vrai si le bijou a pu être détouré.
     */
    suspend fun enhancePhoto(articleId: Long, fileName: String, enhancer: fr.vintedassistant.mobile.data.files.PhotoEnhancer): Boolean {
        val store = photoStore ?: throw IllegalStateException("Photos indisponibles")
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.file(fileName).readBytes() }
        val result = enhancer.enhanceFile(bytes)
        val name = store.importBytes(result.bytes)
        photoDao.insert(PhotoEntity(articleId = articleId, fileName = name, position = photoDao.minPosition(articleId) - 1, createdAt = clock()))
        return result.cutOut
    }

    suspend fun removePhoto(articleId: Long, fileName: String) {
        val photo = photoDao.find(articleId, fileName) ?: return
        photoDao.deleteById(photo.id)
        photoStore?.delete(fileName)
    }

    // ---------- Ventes ----------

    /** Enregistre une vente : instantané des données de l'article, article passé à « Vendu ». */
    suspend fun recordSale(articleId: Long, sale: SaleValidation.Valid): Long = db.withTransaction {
        val article = articleDao.getById(articleId) ?: throw IllegalStateException("Article introuvable")
        val now = clock()
        val id = saleDao.insert(
            SaleEntity(
                articleId = articleId,
                title = article.title,
                category = article.category,
                salePriceCents = sale.salePriceCents,
                costCents = article.costCents,
                feesCents = sale.feesCents,
                soldAt = now,
                listedAt = article.firstPublishedAt ?: article.createdAt,
                buyer = sale.buyer,
                shippingStatus = ShippingStatus.TO_SHIP.label,
                shippedAt = null,
                labelFile = null,
                notes = "",
            )
        )
        articleDao.update(article.copy(status = ArticleStatus.SOLD.label, updatedAt = now))
        id
    }

    /**
     * Change le statut d'expédition. Une vente annulée remet l'article en vente
     * (en ligne s'il avait été publié, sinon en brouillon).
     */
    suspend fun setShippingStatus(saleId: Long, status: ShippingStatus) = db.withTransaction {
        val sale = saleDao.getById(saleId) ?: return@withTransaction
        val now = clock()
        saleDao.update(
            sale.copy(
                shippingStatus = status.label,
                shippedAt = if (status == ShippingStatus.SHIPPED) now else sale.shippedAt,
            )
        )
        val articleId = sale.articleId ?: return@withTransaction
        val article = articleDao.getById(articleId) ?: return@withTransaction
        if (status == ShippingStatus.CANCELLED && article.status == ArticleStatus.SOLD.label) {
            val back = if (article.firstPublishedAt != null) ArticleStatus.ONLINE else ArticleStatus.DRAFT
            articleDao.update(article.copy(status = back.label, updatedAt = now))
        }
    }

    suspend fun attachLabel(saleId: Long, uri: Uri) {
        val store = labelStore ?: throw IllegalStateException("Stockage indisponible")
        val sale = saleDao.getById(saleId) ?: return
        val name = store.import(uri)
        try {
            saleDao.update(sale.copy(labelFile = name))
        } catch (e: Exception) {
            store.delete(name)
            throw e
        }
        sale.labelFile?.let { store.delete(it) }
    }

    // ---------- Vinted : import des annonces depuis les pages ouvertes ----------

    /** Résultat de la fusion d'une annonce relevée sur Vinted. */
    data class UpsertResult(val articleId: Long, val created: Boolean)

    /**
     * Ajoute ou met à jour l'article correspondant à une annonce Vinted (même numéro d'annonce).
     * Ne remplace jamais ce que l'utilisateur a saisi : seuls les champs vides sont complétés ;
     * le prix et les compteurs suivent Vinted. [sold] : annonce vue dans le filtre « Vendus ».
     */
    suspend fun upsertImported(listing: ImportedListing, sold: Boolean): UpsertResult = db.withTransaction {
        val now = clock()
        val all = articleDao.getAll()
        // Sinon, fiche encore sans lien Vinted portant exactement le même titre (remise en vente publiée
        // sans « Lier cette page ») : la plus récente, pour ne pas créer de doublon.
        val existing = all.firstOrNull { VintedLinks.itemId(it.vintedUrl) == listing.itemId }
            ?: all.firstOrNull {
                it.vintedUrl.isBlank() && ArticleStatus.fromLabel(it.status).inStock &&
                    fr.vintedassistant.mobile.domain.StockLedger.normalize(it.title) == fr.vintedassistant.mobile.domain.StockLedger.normalize(listing.title)
            }
        val (cleanDescription, foundKeywords) = ListingTemplates.splitKeywords(listing.description)
        val added = RelativeDates.parse(listing.addedText, now)
        if (existing == null) {
            val id = articleDao.insert(
                ArticleEntity(
                    title = listing.title.take(fr.vintedassistant.mobile.domain.Article.TITLE_MAX_LENGTH),
                    costCents = 0,
                    priceCents = listing.priceCents ?: 0,
                    status = (if (sold) ArticleStatus.SOLD else ArticleStatus.ONLINE).label,
                    createdAt = now,
                    updatedAt = now,
                    brand = listing.brand,
                    size = listing.size,
                    condition = listing.condition,
                    color = listing.color,
                    category = listing.category,
                    description = cleanDescription,
                    keywords = foundKeywords,
                    vintedUrl = listing.url,
                    favoritesCount = listing.favorites,
                    viewsCount = listing.views,
                    firstPublishedAt = if (sold) null else added,
                )
            )
            if (sold) articleDao.getById(id)?.let { ensureImportedSale(it) }
            return@withTransaction UpsertResult(id, created = true)
        }
        val status = ArticleStatus.fromLabel(existing.status)
        val newStatus = when {
            sold -> ArticleStatus.SOLD
            status == ArticleStatus.DRAFT -> ArticleStatus.ONLINE
            else -> status
        }
        val newPrice = listing.priceCents ?: existing.priceCents
        articleDao.update(
            existing.copy(
                priceCents = newPrice,
                floorPriceCents = existing.floorPriceCents?.takeIf { it <= newPrice },
                status = newStatus.label,
                brand = existing.brand.ifBlank { listing.brand },
                size = existing.size.ifBlank { listing.size },
                condition = existing.condition.ifBlank { listing.condition },
                color = existing.color.ifBlank { listing.color },
                category = existing.category.ifBlank { listing.category },
                description = existing.description.ifBlank { cleanDescription },
                keywords = existing.keywords.ifBlank { foundKeywords },
                firstPublishedAt = existing.firstPublishedAt ?: if (newStatus == ArticleStatus.SOLD) null else added,
                vintedUrl = listing.url,
                favoritesCount = listing.favorites ?: existing.favoritesCount,
                viewsCount = listing.views ?: existing.viewsCount,
                updatedAt = now,
            )
        )
        if (newPrice != existing.priceCents) {
            eventDao.insert(ListingEventEntity(articleId = existing.id, type = ListingEventEntity.PRICE_CHANGED, at = now, priceCents = newPrice))
        }
        if (newStatus == ArticleStatus.SOLD) {
            if (status.inStock && saleDao.forArticle(existing.id).isEmpty()) {
                // Fiche encore en vente dans l'application, vendue sur Vinted : vente récente, colis à envoyer.
                // Date du jour (au plus tôt) ; prix affiché, l'éventuelle offre acceptée n'étant pas visible ici.
                val sold = articleDao.getById(existing.id)!!
                saleDao.insert(
                    SaleEntity(
                        articleId = sold.id, title = sold.title, category = sold.category,
                        salePriceCents = sold.priceCents, costCents = sold.costCents, feesCents = 0,
                        soldAt = now, listedAt = sold.firstPublishedAt, buyer = "",
                        shippingStatus = ShippingStatus.TO_SHIP.label, shippedAt = null, labelFile = null,
                        notes = "Vue vendue à l'import du dressing : prix affiché, à corriger si une offre a été acceptée.",
                        dateKnown = true,
                    )
                )
            } else {
                articleDao.getById(existing.id)?.let { ensureImportedSale(it) }
            }
        }
        UpsertResult(existing.id, created = false)
    }

    /**
     * Vente d'un article vendu sur Vinted avant l'import : prix affiché, date inconnue.
     * Comptée dans le total, pas dans le mois. Créée une seule fois par article.
     */
    private suspend fun ensureImportedSale(article: ArticleEntity) {
        if (article.id in saleDao.articleIdsWithSales()) return
        saleDao.insert(
            SaleEntity(
                articleId = article.id,
                title = article.title,
                category = article.category,
                salePriceCents = article.priceCents,
                costCents = article.costCents,
                feesCents = 0,
                soldAt = clock(),
                listedAt = article.firstPublishedAt,
                buyer = "",
                shippingStatus = ShippingStatus.DELIVERED.label,
                shippedAt = null,
                labelFile = null,
                notes = "Importée de Vinted",
                dateKnown = false,
            )
        )
    }

    data class ExportImportReport(
        val updated: Int,
        val created: Int,
        val markedSold: Int,
        val salesDated: Int,
        val salesAdded: Int,
        val photosAdded: Int,
        val unmatchedOrders: Int,
    )

    /**
     * Import de l'export Vinted au format HTML. Chaque annonce est rapprochée de sa fiche par son numéro
     * Vinted ; à défaut, par un titre identique sur une fiche sans lien ou liée à une annonce supprimée
     * (republication). Ce que l'utilisateur a saisi n'est jamais écrasé : seuls les champs vides sont
     * complétés. Les annonces vendues d'après les commandes passent en « Vendu », avec la vraie date et le
     * vrai montant de la vente. Les photos ne sont ajoutées qu'aux fiches qui n'en ont aucune.
     */
    suspend fun importVintedExport(
        report: fr.vintedassistant.mobile.data.importer.ExportReport,
        loadPhotos: suspend (Set<String>) -> Map<String, ByteArray>,
    ): ExportImportReport {
        val now = clock()
        val sold = report.sold.datings.associateBy { it.articleId }
        val deletedIds = report.deleted.mapNotNull { it.itemId }.toSet()
        val needPhotos = LinkedHashMap<Long, List<String>>()
        var updated = 0
        var created = 0
        var markedSold = 0
        var salesDated = 0
        var salesAdded = 0
        db.withTransaction {
            val all = articleDao.getAll()
            val withPhotos = all.filter { photoDao.forArticle(it.id).isNotEmpty() }.map { it.id }.toHashSet()
            val claimed = HashSet<Long>()
            for (l in report.listings.sortedBy { it.createdAt ?: 0L }) {
                val itemId = l.itemId ?: continue
                val dating = sold[itemId]
                val key = fr.vintedassistant.mobile.domain.StockLedger.normalize(l.title)
                val wantSold = dating != null
                val existing = all.firstOrNull { it.id !in claimed && VintedLinks.itemId(it.vintedUrl) == itemId }
                    ?: all.filter {
                        it.id !in claimed && fr.vintedassistant.mobile.domain.StockLedger.normalize(it.title) == key &&
                            (it.vintedUrl.isBlank() || VintedLinks.itemId(it.vintedUrl) in deletedIds)
                    }.sortedBy { (ArticleStatus.fromLabel(it.status) == ArticleStatus.SOLD) != wantSold }.firstOrNull()
                val (cleanDescription, foundKeywords) = ListingTemplates.splitKeywords(l.description)
                val articleId: Long
                if (existing == null) {
                    articleId = articleDao.insert(
                        ArticleEntity(
                            title = l.title.take(Article.TITLE_MAX_LENGTH),
                            costCents = 0,
                            priceCents = l.priceCents ?: 0,
                            status = (if (wantSold) ArticleStatus.SOLD else ArticleStatus.ONLINE).label,
                            createdAt = now,
                            updatedAt = now,
                            brand = l.brand.take(120),
                            size = l.size.take(120),
                            condition = l.condition.take(60),
                            color = l.color.take(60),
                            description = cleanDescription,
                            keywords = foundKeywords,
                            vintedUrl = l.url,
                            favoritesCount = l.favorites,
                            viewsCount = l.views,
                            firstPublishedAt = l.createdAt,
                        )
                    )
                    created++
                    needPhotos[articleId] = l.photoPaths
                } else {
                    articleId = existing.id
                    val status = ArticleStatus.fromLabel(existing.status)
                    val newStatus = if (wantSold) ArticleStatus.SOLD else status
                    if (newStatus != status) markedSold++
                    articleDao.update(
                        existing.copy(
                            status = newStatus.label,
                            priceCents = if (!wantSold && status.inStock) l.priceCents ?: existing.priceCents else existing.priceCents,
                            brand = existing.brand.ifBlank { l.brand.take(120) },
                            size = existing.size.ifBlank { l.size.take(120) },
                            condition = existing.condition.ifBlank { l.condition.take(60) },
                            color = existing.color.ifBlank { l.color.take(60) },
                            description = existing.description.ifBlank { cleanDescription },
                            keywords = existing.keywords.ifBlank { foundKeywords },
                            vintedUrl = l.url,
                            favoritesCount = l.favorites ?: existing.favoritesCount,
                            viewsCount = l.views ?: existing.viewsCount,
                            // Vraie date de mise en ligne de l'annonce, sauf republication déjà notée dans l'application.
                            firstPublishedAt = if (existing.lastPublishedAt == null) l.createdAt ?: existing.firstPublishedAt else existing.firstPublishedAt,
                            updatedAt = now,
                        )
                    )
                    updated++
                    if (existing.id !in withPhotos && l.photoPaths.isNotEmpty()) needPhotos[articleId] = l.photoPaths
                }
                claimed += articleId
                if (dating != null) {
                    val article = articleDao.getById(articleId) ?: continue
                    val sales = saleDao.forArticle(articleId)
                    val undated = sales.firstOrNull { !it.dateKnown }
                    val shipping = if (dating.orderStatus.contains("envoy", ignoreCase = true)) ShippingStatus.SHIPPED else ShippingStatus.DELIVERED
                    when {
                        sales.any { it.dateKnown } -> Unit // vente déjà enregistrée à la main : on n'y touche pas
                        undated != null -> {
                            saleDao.update(
                                undated.copy(
                                    soldAt = dating.soldAt, dateKnown = true, salePriceCents = dating.priceCents,
                                    listedAt = l.createdAt ?: undated.listedAt, shippingStatus = shipping.label,
                                )
                            )
                            salesDated++
                        }
                        else -> {
                            saleDao.insert(
                                SaleEntity(
                                    articleId = articleId, title = article.title, category = article.category,
                                    salePriceCents = dating.priceCents, costCents = article.costCents, feesCents = 0,
                                    soldAt = dating.soldAt, listedAt = l.createdAt, buyer = "",
                                    shippingStatus = shipping.label, shippedAt = null, labelFile = null,
                                    notes = "Importée de l'export Vinted", dateKnown = true,
                                )
                            )
                            salesAdded++
                        }
                    }
                }
            }
        }
        // Photos en dehors de la transaction : lecture de l'archive et réencodage.
        var photosAdded = 0
        val store = photoStore
        if (store != null && needPhotos.isNotEmpty()) {
            val bytes = loadPhotos(needPhotos.values.flatten().toSet())
            for ((articleId, paths) in needPhotos) {
                var position = photoDao.maxPosition(articleId) + 1
                for (path in paths.take(20)) {
                    val data = bytes[path] ?: continue
                    val name = try { store.importBytes(data) } catch (e: Exception) { continue }
                    photoDao.insert(PhotoEntity(articleId = articleId, fileName = name, position = position++, createdAt = now))
                    photosAdded++
                }
            }
        }
        return ExportImportReport(updated, created, markedSold, salesDated, salesAdded, photosAdded, report.sold.unmatched.size)
    }

    data class CleanupReport(val salesCreated: Int, val keywordsMoved: Int, val photosRemoved: Int, val noticesReclassified: Int)

    /**
     * Remise en ordre des données importées par les versions précédentes, sans toucher à la saisie utilisateur :
     * ventes manquantes des articles vendus, mots-clés restés dans la description, image répétée dans de
     * nombreuses fiches (photo de profil), notifications à reclasser. Sans effet si tout est déjà propre.
     */
    suspend fun cleanUpImportedData(): CleanupReport {
        var sales = 0
        var keywords = 0
        db.withTransaction {
            val withSales = saleDao.articleIdsWithSales().toSet()
            // Catégorie manquante (import Vinted) : déduite du titre, sur la fiche et sur ses ventes.
            for (a in articleDao.getAll()) {
                if (a.category.isNotBlank()) continue
                val guess = fr.vintedassistant.mobile.domain.JewelryCategory.guess(a.title) ?: continue
                articleDao.update(a.copy(category = guess))
            }
            // Vente enregistrée avant le rattachement de la fiche à son achat : coût repris de la fiche.
            for (s in saleDao.getAll()) {
                if (s.costCents != 0L) continue
                val cost = s.articleId?.let { articleDao.getById(it)?.costCents } ?: continue
                if (cost > 0) saleDao.update(s.copy(costCents = cost))
            }
            for (s in saleDao.getAll()) {
                if (s.category.isNotBlank()) continue
                val fromArticle = s.articleId?.let { articleDao.getById(it)?.category }?.takeIf { it.isNotBlank() }
                val guess = fromArticle ?: fr.vintedassistant.mobile.domain.JewelryCategory.guess(s.title) ?: continue
                saleDao.update(s.copy(category = guess))
            }
            for (a in articleDao.getAll()) {
                if (a.vintedUrl.isBlank()) continue
                if (a.status == ArticleStatus.SOLD.label && a.id !in withSales) {
                    ensureImportedSale(a)
                    sales++
                }
                if (a.keywords.isBlank()) {
                    val (desc, kw) = ListingTemplates.splitKeywords(a.description)
                    if (kw.isNotEmpty()) {
                        articleDao.update(a.copy(description = desc, keywords = kw))
                        keywords++
                    }
                }
            }
        }
        val photos = removeRepeatedPhotos()
        var notices = 0
        val articles = getAllArticles()
        for (n in noticeDao.getAll()) {
            val kind = VintedNotificationClassifier.classify(n.title, n.text).name
            val match = n.articleId ?: VintedNotificationClassifier.matchArticle("${n.title} ${n.text}", articles)?.id
            if (kind != n.kind || match != n.articleId) {
                noticeDao.reclassify(n.id, kind, match)
                notices++
            }
        }
        return CleanupReport(sales, keywords, photos, notices)
    }

    /** Supprime une image identique présente dans au moins 5 fiches différentes (photo de profil, bannière). */
    private suspend fun removeRepeatedPhotos(): Int {
        val store = photoStore ?: return 0
        val photos = photoDao.getAll()
        val byHash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            photos.groupBy { p ->
                val f = store.file(p.fileName)
                if (!f.exists()) "absent-${p.id}" else java.security.MessageDigest.getInstance("SHA-256")
                    .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            }
        }
        var removed = 0
        byHash.values.filter { group -> group.map { it.articleId }.distinct().size >= 5 }.flatten().forEach { p ->
            photoDao.deleteById(p.id)
            store.delete(p.fileName)
            removed++
        }
        return removed
    }

    /** Télécharge les photos d'une annonce importée, seulement si la fiche n'en a encore aucune. Renvoie le nombre ajouté. */
    suspend fun attachRemotePhotos(articleId: Long, urls: List<String>, max: Int = 10): Int {
        val store = photoStore ?: return 0
        if (photoDao.forArticle(articleId).isNotEmpty()) return 0
        var added = 0
        for (url in urls.take(max)) {
            try {
                val name = store.importBytes(ImageDownloader.download(url))
                try {
                    photoDao.insert(PhotoEntity(articleId = articleId, fileName = name, position = added, createdAt = clock()))
                    added++
                } catch (e: Exception) {
                    store.delete(name)
                }
            } catch (e: Exception) {
                // Photo indisponible : on passe à la suivante.
            }
        }
        return added
    }

    // ---------- Vinted : lien, notifications, import ----------

    /** Retouche rapide d'une fiche (mise en vente express) : titre, prix, texte. */
    suspend fun quickEdit(id: Long, title: String, priceCents: Long, description: String? = null, keywords: String? = null) {
        val a = articleDao.getById(id) ?: return
        articleDao.update(
            a.copy(
                title = title.trim().take(Article.TITLE_MAX_LENGTH).ifBlank { a.title },
                priceCents = priceCents.coerceAtLeast(0),
                description = description ?: a.description,
                keywords = keywords ?: a.keywords,
                updatedAt = clock(),
            ),
        )
    }

    suspend fun setVintedUrl(id: Long, url: String) {
        val current = articleDao.getById(id) ?: return
        articleDao.update(current.copy(vintedUrl = url, updatedAt = clock()))
    }

    /** Enregistre une notification Vinted reçue sur le téléphone. Renvoie false si elle était déjà connue. */
    /** Vente enregistrée d'office à partir d'une notification Vinted. */
    data class AutoSale(val articleId: Long, val title: String, val priceCents: Long, val priceFromNotice: Boolean)

    /**
     * Enregistre la notification puis, si c'est une vente dont la fiche est reconnue sans ambiguïté et encore
     * en stock, enregistre la vente : statut « À expédier », prix lu dans la notification s'il est plausible
     * (au plus le prix affiché, au moins la moitié), sinon le prix affiché, à vérifier.
     */
    suspend fun handleNotification(key: String, postedAt: Long, title: String, text: String): AutoSale? {
        if (!recordNotification(key, postedAt, title, text)) return null
        return saleFromNotice(key, title, text)
    }

    data class Handled(val sale: AutoSale?, val reply: fr.vintedassistant.mobile.domain.QuickReply.Prepared?)

    /** Notification Vinted reçue : vente enregistrée, ou réponse préparée pour un message, une offre, un favori. */
    suspend fun processNotification(key: String, postedAt: Long, title: String, text: String, priceDropPercent: Int): Handled {
        if (!recordNotification(key, postedAt, title, text)) return Handled(null, null)
        val kind = VintedNotificationClassifier.classify(title, text)
        if (kind == fr.vintedassistant.mobile.domain.VintedEventKind.SALE) return Handled(saleFromNotice(key, title, text), null)
        val article = VintedNotificationClassifier.matchArticle("$title $text", getAllArticles().filter { it.status.inStock })
            ?: return Handled(null, null)
        return Handled(null, fr.vintedassistant.mobile.domain.QuickReply.prepare(kind, title, text, article, priceDropPercent))
    }

    private suspend fun saleFromNotice(key: String, title: String, text: String): AutoSale? {
        if (VintedNotificationClassifier.classify(title, text) != fr.vintedassistant.mobile.domain.VintedEventKind.SALE) return null
        val content = "$title $text"
        val article = VintedNotificationClassifier.soldArticle(content, getAllArticles().filter { it.status.inStock }) ?: return null
        if (saleDao.forArticle(article.id).isNotEmpty()) return null
        val read = VintedNotificationClassifier.plausiblePrice(content, article.priceCents)
        val price = read ?: article.priceCents
        val plausible = read != null
        recordSale(article.id, SaleValidation.Valid(price, 0, ""))
        saleDao.forArticle(article.id).lastOrNull()?.let { s ->
            saleDao.update(s.copy(notes = if (plausible) "Enregistrée depuis la notification Vinted."
                else "Enregistrée depuis la notification Vinted : prix affiché, à corriger si une offre a été acceptée."))
        }
        noticeDao.markHandledByKey(key)
        return AutoSale(article.id, article.title, price, plausible)
    }

    suspend fun recordNotification(key: String, postedAt: Long, title: String, text: String): Boolean {
        val articles = getAllArticles().filter { it.status != ArticleStatus.WITHDRAWN }
        val content = "$title $text"
        val inserted = noticeDao.insert(
            VintedNotificationEntity(
                notificationKey = key,
                postedAt = postedAt,
                title = title.take(300),
                text = text.take(1000),
                kind = VintedNotificationClassifier.classify(title, text).name,
                articleId = VintedNotificationClassifier.matchArticle(content, articles)?.id,
                amountCents = VintedNotificationClassifier.amountCents(content),
                handled = false,
            )
        )
        noticeDao.deleteOlderThan(clock() - 60 * DAY_MS)
        return inserted != -1L
    }

    suspend fun markNoticeHandled(id: Long) {
        noticeDao.markHandled(id)
    }

    suspend fun markAllNoticesHandled() {
        noticeDao.markAllHandled()
    }

    /** Crée en brouillon les articles de l'export RGPD absents du stock (même nom et même prix). Renvoie le nombre ajouté. */
    suspend fun importFromExport(candidates: List<ExportCandidate>): Int = db.withTransaction {
        val existing = articleDao.getAll().map { VintedExportParser.normalize(it.title) to it.priceCents }.toHashSet()
        val now = clock()
        var added = 0
        for (c in candidates) {
            val price = c.priceCents ?: 0L
            if (!existing.add(VintedExportParser.normalize(c.title) to price)) continue
            articleDao.insert(
                ArticleEntity(
                    title = c.title.take(fr.vintedassistant.mobile.domain.Article.TITLE_MAX_LENGTH),
                    costCents = 0,
                    priceCents = price,
                    status = ArticleStatus.DRAFT.label,
                    createdAt = now,
                    updatedAt = now,
                    brand = c.brand.take(120),
                    size = c.size.take(120),
                    description = c.description,
                )
            )
            added++
        }
        added
    }

    private fun ArticleEntity.withFields(f: ArticleFields) = copy(
        title = f.title,
        costCents = f.costCents,
        priceCents = f.priceCents,
        floorPriceCents = f.floorPriceCents,
        brand = f.brand,
        size = f.size,
        category = f.category,
        condition = f.condition,
        color = f.color,
        description = f.description,
        keywords = f.keywords,
        vintedUrl = f.vintedUrl,
    )
}
