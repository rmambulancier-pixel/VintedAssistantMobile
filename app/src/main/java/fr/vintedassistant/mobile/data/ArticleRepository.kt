package fr.vintedassistant.mobile.data

import android.net.Uri
import androidx.room.withTransaction
import fr.vintedassistant.mobile.data.files.LabelStore
import fr.vintedassistant.mobile.data.files.PhotoStore
import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter
import fr.vintedassistant.mobile.data.local.ArticleEntity
import fr.vintedassistant.mobile.data.local.ListingEventEntity
import fr.vintedassistant.mobile.data.local.PhotoEntity
import fr.vintedassistant.mobile.data.local.SaleEntity
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import fr.vintedassistant.mobile.data.local.toDomain
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleFields
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.NewArticle
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.SaleValidation
import fr.vintedassistant.mobile.domain.ShippingStatus
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

    val articles: Flow<List<Article>> =
        combine(articleDao.observeAll(), photoDao.observeAll()) { articles, photos ->
            val byArticle = photos.groupBy({ it.articleId }, { it.fileName })
            articles.map { it.toDomain(byArticle[it.id].orEmpty()) }
        }

    val sales: Flow<List<Sale>> = saleDao.observeAll().map { list -> list.map { it.toDomain() } }

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
    )
}
