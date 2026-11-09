package fr.vintedassistant.mobile.domain

import java.text.Normalizer

/**
 * Lot d'un même bijou dans une commande : les pièces achetées ensemble, au même coût de revient.
 * Un bijou racheté dans une autre commande forme un second lot, avec son propre prix.
 */
data class StockLot(
    val itemId: Long,
    val orderId: Long,
    val orderRef: String,
    val supplier: String,
    val orderedAt: Long,
    val unitPriceCents: Long,
    val landedUnitCents: Long,
    val quantity: Int,
    val linkedCount: Int,
    /** Faux tant que la commande est en cours de livraison. */
    val received: Boolean = true,
    val deliveryEta: String = "",
) {
    /** Pièces de ce lot qui n'ont encore aucune fiche. */
    val remaining: Int get() = (quantity - linkedCount).coerceAtLeast(0)
}

/**
 * Un bijou (même nom, même variante, même photo fournisseur), toutes commandes confondues.
 * Les pièces se répartissent ainsi : sans fiche (pas encore en vente), brouillon, en vente, vendue, retirée.
 */
data class StockProduct(
    val key: String,
    val name: String,
    val imageFile: String?,
    /** Du plus ancien au plus récent : la prochaine fiche prend le plus ancien lot qui a des pièces libres. */
    val lots: List<StockLot>,
    /** Fiches rattachées à ce bijou, la plus récente d'abord. */
    val articles: List<Article>,
) {
    val bought: Int get() = lots.sumOf { it.quantity }
    /** Pièces reçues, sans fiche : prêtes à être mises en vente. */
    val notListed: Int get() = lots.filter { it.received }.sumOf { it.remaining }
    /** Pièces commandées, pas encore reçues. */
    val inTransit: Int get() = lots.filter { !it.received }.sumOf { it.remaining }
    val drafts: Int get() = articles.count { it.status == ArticleStatus.DRAFT }
    val forSale: Int get() = articles.count { it.status == ArticleStatus.ONLINE || it.status == ArticleStatus.RESERVED }
    val sold: Int get() = articles.count { it.status == ArticleStatus.SOLD }
    val withdrawn: Int get() = articles.count { it.status == ArticleStatus.WITHDRAWN }

    /** Plus de fiches que de pièces achetées : un rattachement est probablement erroné. */
    val overLinked: Boolean get() = articles.size > bought

    /** Lot utilisé pour la prochaine fiche (le plus ancien qui a encore des pièces libres). */
    val nextLot: StockLot? get() = lots.firstOrNull { it.received && it.remaining > 0 }

    /** Vrai si le même bijou a été payé à des prix différents selon la commande. */
    val costVaries: Boolean get() = lots.map { it.landedUnitCents }.distinct().size > 1

    /** Fiche à reprendre pour une remise en vente : la plus récente qui a un texte ou des photos. */
    val template: Article? get() = articles.firstOrNull { it.description.isNotBlank() || it.photos.isNotEmpty() } ?: articles.firstOrNull()
}

/** Vue d'ensemble du stock, pièce par pièce. */
data class StockOverview(
    val products: List<StockProduct>,
    /** Fiches qui ne viennent d'aucun achat enregistré (coût saisi à la main ou inconnu). */
    val unlinked: List<Article>,
) {
    val bought: Int get() = products.sumOf { it.bought }
    val notListed: Int get() = products.sumOf { it.notListed }
    val drafts: Int get() = products.sumOf { it.drafts }
    val forSale: Int get() = products.sumOf { it.forSale }
    val sold: Int get() = products.sumOf { it.sold }
    val withdrawn: Int get() = products.sumOf { it.withdrawn }
    val inTransit: Int get() = products.sumOf { it.inTransit }

    /** Valeur d'achat des pièces reçues pas encore en vente. */
    val notListedValueCents: Long get() = products.sumOf { p -> p.lots.filter { it.received }.sumOf { it.remaining * it.landedUnitCents } }

    /** Valeur d'achat des pièces en cours de livraison. */
    val inTransitValueCents: Long get() = products.sumOf { p -> p.lots.filter { !it.received }.sumOf { it.remaining * it.landedUnitCents } }

    fun productOf(article: Article): StockProduct? =
        article.purchaseItemId?.let { id -> products.firstOrNull { p -> p.lots.any { it.itemId == id } } }
}

object StockLedger {

    /**
     * Clé d'un bijou. Avec le code produit du fournisseur : code + variante (couleur, taille), sans les
     * codes internes qui peuvent changer d'une commande à l'autre. Sans code : nom (variante comprise)
     * sans accents ni casse, plus le fichier de la photo fournisseur.
     */
    fun productKey(name: String, imageUrl: String, sku: String = ""): String {
        if (sku.isNotBlank()) return "sku:" + sku.uppercase() + "|" + variantKey(name)
        return legacyKey(name, imageUrl)
    }

    /** Variante lue entre parenthèses en fin de nom, sans les codes du type « B26081706A-P ». */
    fun variantKey(name: String): String {
        val variant = VARIANT.find(name)?.groupValues?.get(1).orEmpty()
        return normalize(variant).split(' ')
            .filter { it.isNotEmpty() && !(it.length >= 6 && it.any(Char::isDigit) && it.any(Char::isLetter)) }
            .joinToString(" ")
    }

    private val VARIANT = Regex("\\(([^()]*)\\)\\s*$")

    private fun legacyKey(name: String, imageUrl: String): String {
        val image = imageUrl.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
        return normalize(name) + "|" + image
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    fun build(orders: List<PurchaseOrder>, articles: List<Article>): StockOverview {
        val byItem = articles.filter { it.purchaseItemId != null }.groupBy { it.purchaseItemId!! }
        val knownItems = HashSet<Long>()
        val groups = LinkedHashMap<String, MutableList<Pair<PurchaseOrder, PurchaseItem>>>()
        orders.sortedWith(compareBy<PurchaseOrder> { it.orderedAt }.thenBy { it.id }).forEach { order ->
            order.items.sortedBy { it.id }.forEach { item ->
                knownItems += item.id
                groups.getOrPut(productKey(item.name, item.imageUrl, item.sku)) { mutableListOf() } += order to item
            }
        }
        val products = groups.map { (key, entries) ->
            val lots = entries.map { (o, i) ->
                StockLot(
                    itemId = i.id, orderId = o.id, orderRef = o.orderRef, supplier = o.supplier, orderedAt = o.orderedAt,
                    unitPriceCents = i.unitPriceCents, landedUnitCents = i.landedUnitCents,
                    quantity = i.delivered, linkedCount = byItem[i.id]?.size ?: 0,
                    received = o.received, deliveryEta = o.deliveryEta,
                )
            }
            val linked = entries.flatMap { (_, i) -> byItem[i.id].orEmpty() }
                .sortedWith(compareByDescending<Article> { it.createdAt }.thenByDescending { it.id })
            val newest = entries.last().second
            StockProduct(
                key = key,
                name = newest.name,
                imageFile = entries.asReversed().firstNotNullOfOrNull { it.second.imageFile },
                lots = lots,
                articles = linked,
            )
        }
        val unlinked = articles.filter { it.purchaseItemId == null || it.purchaseItemId !in knownItems }
        return StockOverview(products, unlinked)
    }

    /**
     * Ressemblance entre un titre de fiche et un nom de produit fournisseur (0 à 1),
     * pour proposer d'abord les achats les plus probables lors d'un rattachement.
     */
    fun similarity(a: String, b: String): Double {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        val common = wa.count { it in wb }
        return common.toDouble() / minOf(wa.size, wb.size)
    }

    private val STOP = setOf("avec", "pour", "dans", "style", "femme", "homme", "unisexe", "plaque", "acier", "inoxydable")

    private fun words(s: String): Set<String> =
        normalize(s).split(' ').filter { it.length >= 4 && it !in STOP }.map { it.removeSuffix("s") }.toSet()
}
