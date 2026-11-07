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
import fr.vintedassistant.mobile.domain.StockLedger
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
                        PurchaseItem(it.id, it.orderId, it.name, it.imageFile, it.unitPriceCents, it.quantity, it.landedUnitCents, linked[it.id] ?: 0, it.imageUrl, it.sku)
                    },
                    received = o.received,
                    deliveryEta = o.deliveryEta,
                )
            }
        }

    data class SaveResult(val orderId: Long, val created: Boolean, val items: Int, val pieces: Int = 0, val expectedPieces: Int? = null)

    /**
     * Enregistre une commande (importée ou saisie). Une commande déjà connue (même fournisseur et
     * même référence) n'est pas dupliquée. Les photos des produits sont téléchargées si possible.
     */
    suspend fun saveOrder(supplier: String, order: ParsedOrder, downloadImages: Boolean = true, received: Boolean? = null): SaveResult {
        val existing = dao.findOrder(supplier, order.orderRef)
        // Commande déjà importée : elle est remplacée par la nouvelle lecture, rattachements conservés
        // (même code produit et même nom, sinon même position), et le coût des fiches rattachées est recalculé.
        val oldItems = existing?.let { e -> dao.itemsOf(e.id).sortedBy { it.id } }.orEmpty()
        val used = HashSet<Long>()
        val matched = order.lines.mapIndexed { index, line ->
            val bySku = if (line.sku.isBlank()) null else oldItems.firstOrNull {
                it.id !in used && it.sku == line.sku && StockLedger.normalize(it.name) == StockLedger.normalize(line.name)
            }
            // Par position seulement si les codes produit sont inconnus : un produit nouvellement lu
            // (ligne manquée à l'import précédent) ne doit pas prendre le rattachement d'un autre.
            val byPosition = if (line.sku.isNotBlank() && oldItems.any { it.sku.isNotBlank() }) null
                else oldItems.getOrNull(index)?.takeIf { it.id !in used }
            (bySku ?: byPosition)?.also { used += it.id }
        }
        val previousLinks: Map<Int, List<Long>> = matched.mapIndexedNotNull { i, old ->
            old?.let { i to articleDao.linkedToPurchase(it.id).map { a -> a.id } }
        }.toMap()
        // Fiches rattachées à un produit qui n'existe plus dans la nouvelle lecture : détachées, coût conservé.
        val orphanLinks = oldItems.filter { it.id !in used }.flatMap { articleDao.linkedToPurchase(it.id).map { a -> a.id } }
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
                (previousLinks.values.flatten() + orphanLinks).forEach { aid -> articleDao.getById(aid)?.let { articleDao.update(it.copy(purchaseItemId = null)) } }
                dao.deleteOrder(existing.id)
            }
            val id = dao.insertOrder(
                PurchaseOrderEntity(
                    supplier = supplier, orderRef = order.orderRef, orderedAt = order.orderedAt ?: now,
                    itemsTotalCents = order.itemsTotalCents, shippingCents = order.shippingCents, feesCents = order.feesCents,
                    url = order.url, importedAt = now,
                    // Nouvelle commande : en livraison si le site annonce une livraison ou si elle a moins de 30 jours.
                    received = received ?: existing?.received ?: !(order.deliveryEta.isNotBlank() || now - (order.orderedAt ?: now) < RECENT_MS),
                    deliveryEta = order.deliveryEta,
                )
            )
            order.lines.forEachIndexed { i, line ->
                val itemId = dao.insertItem(
                    PurchaseItemEntity(
                        orderId = id, name = line.name, imageFile = images[i], imageUrl = line.imageUrl,
                        productUrl = line.productUrl, unitPriceCents = line.unitPriceCents,
                        quantity = line.quantity, landedUnitCents = landed[i], sku = line.sku,
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
        return SaveResult(orderId, created = existing == null, items = order.lines.size,
            pieces = order.lines.sumOf { it.quantity }, expectedPieces = order.expectedPieces)
    }

    /** Commande reçue (pièces vendables) ou de nouveau marquée en cours de livraison. */
    suspend fun setReceived(orderId: Long, received: Boolean) {
        dao.setReceived(orderId, received)
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
        check(dao.getOrder(item.orderId)?.received != false) { "Commande pas encore reçue" }
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

    /**
     * Remise en vente d'un bijou déjà vendu (ou déjà en ligne) : nouvelle fiche brouillon qui reprend
     * le titre, la description, les détails, le prix et les photos de [templateId].
     * Avec [itemId], la fiche est rattachée à ce lot d'achat et prend son coût de revient ;
     * sans lot, elle garde le coût de la fiche d'origine. La fiche d'origine et sa vente ne changent pas.
     */
    suspend fun duplicateForSale(templateId: Long, itemId: Long?): Long {
        val template = articleDao.getById(templateId) ?: throw IllegalStateException("Fiche introuvable")
        val item = itemId?.let { dao.getItem(it) ?: throw IllegalStateException("Achat introuvable") }
        if (item != null) check(dao.getOrder(item.orderId)?.received != false) { "Commande pas encore reçue" }
        if (item != null) {
            val used = articleDao.linkedToPurchase(item.id).size
            check(used < item.quantity) { "Plus de pièce libre dans ce lot" }
        }
        val now = clock()
        val copies = photoDao.forArticle(templateId).mapNotNull { photo ->
            val store = photoStore ?: return@mapNotNull null
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.file(photo.fileName).readBytes() }
                store.importBytes(bytes)
            } catch (e: Exception) {
                null
            }
        }
        return db.withTransaction {
            val id = articleDao.insert(
                template.copy(
                    id = 0,
                    status = ArticleStatus.DRAFT.label,
                    costCents = item?.landedUnitCents ?: template.costCents,
                    purchaseItemId = item?.id,
                    createdAt = now,
                    updatedAt = now,
                    firstPublishedAt = null,
                    lastPublishedAt = null,
                    vintedUrl = "",
                    favoritesCount = null,
                    viewsCount = null,
                )
            )
            copies.forEachIndexed { i, name -> photoDao.insert(PhotoEntity(articleId = id, fileName = name, position = i, createdAt = now)) }
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

    private companion object {
        const val RECENT_MS = 30L * 24 * 60 * 60 * 1000
    }
}
