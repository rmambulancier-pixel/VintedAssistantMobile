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
import kotlinx.coroutines.flow.first

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
                        PurchaseItem(it.id, it.orderId, it.name, it.imageFile, it.unitPriceCents, it.quantity, it.landedUnitCents, linked[it.id] ?: 0, it.imageUrl, it.sku, it.missing)
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
            // Même code produit ; s'il apparaît plusieurs fois, même nom d'abord. Le nom seul peut changer
            // d'une lecture à l'autre (nettoyage), le code non.
            val sameSku = if (line.sku.isBlank()) emptyList() else oldItems.filter { it.id !in used && it.sku == line.sku }
            val bySku = sameSku.firstOrNull { StockLedger.normalize(it.name) == StockLedger.normalize(line.name) } ?: sameSku.firstOrNull()
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
        // Pièces jamais reçues déjà signalées : conservées à la relecture, et sans part des frais de port.
        val missing = order.lines.mapIndexed { i, line -> (matched[i]?.missing ?: 0).coerceIn(0, line.quantity) }
        val landed = allocateDelivered(order.lines.mapIndexed { i, l -> Triple(l.unitPriceCents, l.quantity, missing[i]) }, order.shippingCents + order.feesCents)
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
                    supplier = supplier, orderRef = order.orderRef, // Date illisible à la relecture : on garde celle déjà connue (éventuellement corrigée à la main).
                    orderedAt = order.orderedAt ?: existing?.orderedAt ?: now,
                    itemsTotalCents = order.itemsTotalCents, shippingCents = order.shippingCents, feesCents = order.feesCents,
                    url = order.url, importedAt = now,
                    // En livraison seulement si le site annonce encore une livraison ; sans annonce, la commande est reçue.
                    received = received ?: (order.deliveryEta.isBlank() || existing?.received == true),
                    deliveryEta = order.deliveryEta,
                )
            )
            order.lines.forEachIndexed { i, line ->
                val itemId = dao.insertItem(
                    PurchaseItemEntity(
                        orderId = id, name = line.name, imageFile = images[i], imageUrl = line.imageUrl,
                        productUrl = line.productUrl, unitPriceCents = line.unitPriceCents,
                        quantity = line.quantity, landedUnitCents = landed[i], sku = line.sku, missing = missing[i],
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

    /** Rattachement relu dans un export de l'application : fiche → produit (commande, code, nom). */
    data class LinkBackup(val articleId: Long, val orderRef: String, val sku: String, val name: String)

    /**
     * Rétablit les rattachements d'un export précédent (après suppression et réimport d'une commande).
     * Le produit est retrouvé par référence de commande et code produit (puis nom). Une fiche déjà
     * rattachée, introuvable, ou un produit déjà complet ne sont pas touchés. Renvoie le nombre rétabli.
     */
    suspend fun restoreLinks(backup: List<LinkBackup>): Int {
        var restored = 0
        val orders = dao.observeOrders().first()
        val items = dao.observeItems().first()
        for (b in backup) {
            val article = articleDao.getById(b.articleId) ?: continue
            if (article.purchaseItemId != null && dao.getItem(article.purchaseItemId) != null) continue
            val order = orders.firstOrNull { it.orderRef == b.orderRef } ?: continue
            val candidates = items.filter { it.orderId == order.id && (b.sku.isBlank() || it.sku == b.sku) }
            val item = (candidates.filter { StockLedger.normalize(it.name) == StockLedger.normalize(b.name) } + candidates)
                .firstOrNull { articleDao.linkedToPurchase(it.id).size < it.quantity - it.missing } ?: continue
            link(article.id, item.id)
            restored++
        }
        return restored
    }

    /** Date de commande corrigée à la main (conservée aux réimports si le site reste illisible). */
    suspend fun setOrderedAt(orderId: Long, at: Long) {
        dao.setOrderedAt(orderId, at)
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

    /**
     * Vente sans fiche (lot Vinted, pièce envoyée à la place d'une autre…) : crée une fiche « Vendu » pour la
     * pièce réellement sortie du stock, rattachée à son achat, et lui associe la vente avec ce coût d'achat.
     * Le titre de la vente ne change pas ; la pièce envoyée est notée dans la vente.
     */
    suspend fun attachSaleToPurchase(saleId: Long, itemId: Long): Long = db.withTransaction {
        val sale = saleDao.getById(saleId) ?: throw IllegalStateException("Vente introuvable")
        check(sale.articleId == null) { "Cette vente a déjà une fiche" }
        val item = dao.getItem(itemId) ?: throw IllegalStateException("Produit introuvable")
        val linked = articleDao.linkedToPurchase(itemId).size
        check(linked < item.quantity - item.missing) { "Toutes les pièces reçues de ce produit sont déjà rattachées" }
        val now = clock()
        val id = articleDao.insert(
            ArticleEntity(
                title = item.name.take(fr.vintedassistant.mobile.domain.Article.TITLE_MAX_LENGTH),
                costCents = item.landedUnitCents,
                priceCents = sale.salePriceCents,
                status = ArticleStatus.SOLD.label,
                createdAt = sale.soldAt,
                updatedAt = now,
                category = sale.category,
                firstPublishedAt = sale.listedAt,
                purchaseItemId = itemId,
            ),
        )
        val note = "Pièce envoyée : ${item.name.take(80)}"
        saleDao.update(sale.copy(articleId = id, costCents = item.landedUnitCents, notes = listOf(sale.notes, note).filter { it.isNotBlank() }.joinToString("\n")))
        id
    }

    /** Frais de port répartis sur les seules pièces reçues ; une ligne jamais reçue garde son prix payé. */
    private fun allocateDelivered(lines: List<Triple<Long, Int, Int>>, extraCents: Long): List<Long> {
        val delivered = lines.withIndex().filter { (_, l) -> l.second - l.third > 0 }
        val share = LandedCost.allocate(delivered.map { (_, l) -> PurchaseLine(l.first, l.second - l.third) }, extraCents)
        val out = lines.map { it.first }.toMutableList()
        delivered.forEachIndexed { k, (i, _) -> out[i] = share[k] }
        return out
    }

    /**
     * Signale [missing] pièces jamais reçues sur un produit (rupture, colis incomplet). Elles sortent du stock
     * et les frais de port de la commande sont répartis à nouveau sur les pièces reçues ; le coût des fiches
     * encore en stock suit. Les ventes passées gardent leur coût.
     */
    suspend fun setMissing(itemId: Long, missing: Int) = db.withTransaction {
        val item = dao.getItem(itemId) ?: throw IllegalStateException("Produit introuvable")
        val linked = articleDao.linkedToPurchase(itemId).size
        val value = missing.coerceIn(0, item.quantity)
        check(item.quantity - value >= linked) { "Des fiches sont rattachées à ces pièces : détache-les d'abord." }
        dao.updateItem(item.copy(missing = value))
        val order = dao.getOrder(item.orderId) ?: return@withTransaction
        val items = dao.itemsOf(order.id).sortedBy { it.id }
        val landed = allocateDelivered(items.map { Triple(it.unitPriceCents, it.quantity, it.missing) }, order.shippingCents + order.feesCents)
        val now = clock()
        items.forEachIndexed { i, it ->
            if (it.landedUnitCents == landed[i]) return@forEachIndexed
            dao.updateItem(it.copy(landedUnitCents = landed[i]))
            articleDao.linkedToPurchase(it.id).filter { a -> ArticleStatus.fromLabel(a.status).inStock }.forEach { a ->
                articleDao.update(a.copy(costCents = landed[i], updatedAt = now))
            }
        }
    }

    /**
     * Inventaire : applique un manque constaté. Les pièces sans fiche sont déclarées manquantes (hors stock,
     * port réparti à nouveau), puis les brouillons en trop passent « Retiré » (réversible, rien n'est supprimé).
     */
    suspend fun applyShortfall(s: fr.vintedassistant.mobile.domain.Inventory.Shortfall) {
        s.missing.forEach { (itemId, k) ->
            val item = dao.getItem(itemId) ?: return@forEach
            setMissing(itemId, item.missing + k)
        }
        val now = clock()
        s.withdrawDrafts.forEach { a ->
            articleDao.getById(a.id)?.let { articleDao.update(it.copy(status = ArticleStatus.WITHDRAWN.label, updatedAt = now)) }
        }
    }

    /** Détache l'article ; son coût d'achat est conservé (modifiable à la main). */
    suspend fun unlink(articleId: Long) {
        val article = articleDao.getById(articleId) ?: return
        articleDao.update(article.copy(purchaseItemId = null, updatedAt = clock()))
    }

    suspend fun linkedArticles(itemId: Long): List<Article> = articleDao.linkedToPurchase(itemId).map { it.toDomain() }

    /** Crée une fiche brouillon depuis un produit acheté : nom, photo du fournisseur, coût de revient. Prix à fixer. */
    suspend fun createDraft(itemId: Long, supplierPhoto: Boolean = true): Long {
        val item = dao.getItem(itemId) ?: throw IllegalStateException("Produit introuvable")
        check(dao.getOrder(item.orderId)?.received != false) { "Commande pas encore reçue" }
        val now = clock()
        val photo = item.imageFile?.takeIf { supplierPhoto }?.let { source ->
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
            check(used < item.quantity - item.missing) { "Plus de pièce libre dans ce lot" }
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

    /**
     * Mise en vente express : fiche brouillon pour [item]. Copie du modèle s'il existe (sans ses photos si c'est
     * une autre couleur : Vinted veut des photos de l'objet vendu), sinon fiche vierge sans la photo du fournisseur,
     * avec catégorie déduite et prix conseillé d'après les ventes.
     */
    suspend fun prepareExpress(item: fr.vintedassistant.mobile.domain.ExpressListing.Item, sales: List<fr.vintedassistant.mobile.domain.Sale>): Long {
        val templateId = item.templateId
        if (templateId != null) {
            val id = duplicateForSale(templateId, item.itemId)
            if (!item.sameVariant) {
                photoDao.forArticle(id).forEach { photoStore?.delete(it.fileName) }
                photoDao.deleteForArticle(id)
                articleDao.getById(id)?.let { articleDao.update(it.copy(color = "", updatedAt = clock())) }
            }
            return id
        }
        val id = createDraft(item.itemId, supplierPhoto = false)
        articleDao.getById(id)?.let { a ->
            articleDao.update(
                a.copy(
                    priceCents = fr.vintedassistant.mobile.domain.ExpressListing.suggestPrice(a.title, sales) ?: 0,
                    category = fr.vintedassistant.mobile.domain.JewelryCategory.guess(a.title) ?: "",
                    updatedAt = clock(),
                ),
            )
        }
        return id
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
