package fr.vintedassistant.mobile.data

import android.net.Uri
import androidx.room.withTransaction
import fr.vintedassistant.mobile.data.importer.ExportCandidate
import fr.vintedassistant.mobile.data.importer.ImportedListing
import fr.vintedassistant.mobile.data.files.ImageDownloader
import fr.vintedassistant.mobile.domain.VintedLinks
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
        val existing = articleDao.getAll().firstOrNull { VintedLinks.itemId(it.vintedUrl) == listing.itemId }
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
                    description = listing.description,
                    vintedUrl = listing.url,
                    favoritesCount = listing.favorites,
                    viewsCount = listing.views,
                )
            )
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
                description = existing.description.ifBlank { listing.description },
                vintedUrl = listing.url,
                favoritesCount = listing.favorites ?: existing.favoritesCount,
                viewsCount = listing.views ?: existing.viewsCount,
                updatedAt = now,
            )
        )
        if (newPrice != existing.priceCents) {
            eventDao.insert(ListingEventEntity(articleId = existing.id, type = ListingEventEntity.PRICE_CHANGED, at = now, priceCents = newPrice))
        }
        UpsertResult(existing.id, created = false)
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

    suspend fun setVintedUrl(id: Long, url: String) {
        val current = articleDao.getById(id) ?: return
        articleDao.update(current.copy(vintedUrl = url, updatedAt = clock()))
    }

    /** Enregistre une notification Vinted reçue sur le téléphone. Renvoie false si elle était déjà connue. */
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
