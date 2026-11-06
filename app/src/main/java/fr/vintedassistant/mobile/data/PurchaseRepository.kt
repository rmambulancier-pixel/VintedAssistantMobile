package fr.vintedassistant.mobile.data

import androidx.room.withTransaction
import fr.vintedassistant.mobile.data.files.ImageDownloader
import fr.vintedassistant.mobile.data.files.PhotoStore
import fr.vintedassistant.mobile.data.importer.ParsedOrder
import fr.vintedassistant.mobile.data.local.ArticleEntity
import fr.vintedassistant.mobile.data.local.PhotoEntity
import fr.vintedassistant.mobile.data.local.PurchaseItemEntity
import fr.vintedassistant.mobile.data.local.PurchaseOrderEntity
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import fr.vintedassistant.mobile.data.local.toDomain
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.LandedCost
import fr.vintedassistant.mobile.domain.PurchaseItem
import fr.vintedassistant.mobile.domain.PurchaseLine
import fr.vintedassistant.mobile.domain.PurchaseOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Achats fournisseur : commandes, coût de revient, rattachement aux fiches. */
class PurchaseRepository(
    private val db: VintedBoostDatabase,
    private val photoStore: PhotoStore?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.purchaseDao()
    private val articleDao = db.articleDao()
    private val saleDao = db.saleDao()
    private val photoDao = db.photoDao()

    val orders: Flow<List<PurchaseOrder>> =
        combine(dao.observeOrders(), dao.observeItems(), articleDao.observePurchaseLinks()) { orders, items, links ->
            val linked = links.associate { it.itemId to it.count }
            val byOrder = items.groupBy { it.orderId }
            orders.map { o ->
                PurchaseOrder(
                    id = o.id, supplier = o.supplier, orderRef = o.orderRef, orderedAt = o.orderedAt,
                    itemsTotalCents = o.itemsTotalCents, shippingCents = o.shippingCents, feesCents = o.feesCents,
                    items = byOrder[o.id].orEmpty().map {
                        PurchaseItem(it.id, it.orderId, it.name, it.imageFile, it.unitPriceCents, it.quantity, it.landedUnitCents, linked[it.id] ?: 0)
                    },
                )
            }
        }

    data class SaveResult(val orderId: Long, val created: Boolean, val items: Int)

    /**
     * Enregistre une commande (importée ou saisie). Une commande déjà connue (même fournisseur et
     * même référence) n'est pas dupliquée. Les photos des produits sont téléchargées si possible.
     */
    suspend fun saveOrder(supplier: String, order: ParsedOrder, downloadImages: Boolean = true): SaveResult {
        val existing = dao.findOrder(supplier, order.orderRef)
        // Commande déjà importée : elle est remplacée par la nouvelle lecture, rattachements conservés
        // (même position dans la commande), et le coût des fiches rattachées est recalculé.
        val previousLinks: Map<Int, List<Long>> = if (existing == null) emptyMap() else
            dao.itemsOf(existing.id).sortedBy { it.id }.mapIndexed { index, item ->
                index to articleDao.linkedToPurchase(item.id).map { it.id }
            }.toMap()
        val oldImages = existing?.let { e -> dao.itemsOf(e.id).mapNotNull { it.imageFile } }.orEmpty()
        val landed = LandedCost.allocate(order.lines.map { PurchaseLine(it.unitPriceCents, it.quantity) }, order.shippingCents + order.feesCents)
        val images = order.lines.map { line ->
            if (!downloadImages || line.imageUrl.isBlank() || photoStore == null) null
            else try {
                photoStore.importBytes(ImageDownloader.download(line.imageUrl, anyHttps = true))
            } catch (e: Exception) {
                null
            }
        }
        val now = clock()
        val orderId = db.withTransaction {
            if (existing != null) {
                previousLinks.values.flatten().forEach { aid -> articleDao.getById(aid)?.let { articleDao.update(it.copy(purchaseItemId = null)) } }
                dao.deleteOrder(existing.id)
            }
            val id = dao.insertOrder(
                PurchaseOrderEntity(
                    supplier = supplier, orderRef = order.orderRef, orderedAt = order.orderedAt ?: now,
                    itemsTotalCents = order.itemsTotalCents, shippingCents = order.shippingCents, feesCents = order.feesCents,
                    url = order.url, importedAt = now,
                )
            )
            order.lines.forEachIndexed { i, line ->
                val itemId = dao.insertItem(
                    PurchaseItemEntity(
                        orderId = id, name = line.name, imageFile = images[i], imageUrl = line.imageUrl,
                        productUrl = line.productUrl, unitPriceCents = line.unitPriceCents,
                        quantity = line.quantity, landedUnitCents = landed[i],
                    )
                )
                previousLinks[i].orEmpty().forEach { aid ->
                    articleDao.getById(aid)?.let { a ->
                        articleDao.update(a.copy(purchaseItemId = itemId, costCents = landed[i], updatedAt = now))
                        saleDao.syncCostForImported(aid, landed[i])
                    }
                }
            }
            id
        }
        oldImages.forEach { photoStore?.delete(it) }
        return SaveResult(orderId, created = existing == null, items = order.lines.size)
    }

    /** Rattache un article à un produit acheté : son coût d'achat devient le coût de revient unitaire. */
    suspend fun link(articleId: Long, itemId: Long) = db.withTransaction {
        val item = dao.getItem(itemId) ?: return@withTransaction
        val article = articleDao.getById(articleId) ?: return@withTransaction
        articleDao.update(article.copy(costCents = item.landedUnitCents, purchaseItemId = itemId, updatedAt = clock()))
        saleDao.syncCostForImported(articleId, item.landedUnitCents)
    }

    /** Détache l'article ; son coût d'achat est conservé (modifiable à la main). */
    suspend fun unlink(articleId: Long) {
        val article = articleDao.getById(articleId) ?: return
        articleDao.update(article.copy(purchaseItemId = null, updatedAt = clock()))
    }

    suspend fun linkedArticles(itemId: Long): List<Article> = articleDao.linkedToPurchase(itemId).map { it.toDomain() }

    /** Crée une fiche brouillon depuis un produit acheté : nom, photo du fournisseur, coût de revient. Prix à fixer. */
    suspend fun createDraft(itemId: Long): Long {
        val item = dao.getItem(itemId) ?: throw IllegalStateException("Produit introuvable")
        val now = clock()
        val photo = item.imageFile?.let { source ->
            val store = photoStore ?: return@let null
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.file(source).readBytes() }
                store.importBytes(bytes)
            } catch (e: Exception) {
                null
            }
        }
        return db.withTransaction {
            val id = articleDao.insert(
                ArticleEntity(
                    title = item.name.take(Article.TITLE_MAX_LENGTH),
                    costCents = item.landedUnitCents,
                    priceCents = 0,
                    status = ArticleStatus.DRAFT.label,
                    createdAt = now,
                    updatedAt = now,
                    purchaseItemId = item.id,
                )
            )
            if (photo != null) photoDao.insert(PhotoEntity(articleId = id, fileName = photo, position = 0, createdAt = now))
            id
        }
    }

    /** Supprime une commande. Les articles rattachés gardent leur coût d'achat. */
    suspend fun deleteOrder(orderId: Long) {
        val items = dao.itemsOf(orderId)
        db.withTransaction {
            items.forEach { item -> articleDao.linkedToPurchase(item.id).forEach { a -> articleDao.update(a.copy(purchaseItemId = null)) } }
            dao.deleteOrder(orderId)
        }
        items.mapNotNull { it.imageFile }.forEach { photoStore?.delete(it) }
    }
}
