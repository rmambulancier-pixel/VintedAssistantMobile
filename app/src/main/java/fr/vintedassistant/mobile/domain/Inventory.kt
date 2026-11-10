package fr.vintedassistant.mobile.domain

/**
 * Inventaire : on compte ce qu'on a vraiment en main, bijou par bijou, et l'application se corrige.
 * Attendu en main = pièces reçues sans fiche + brouillons + en vente + réservées + vendues pas encore expédiées.
 */
object Inventory {
    data class Line(
        val key: String,
        val name: String,
        val image: String?,
        val variant: String,
        val expected: Int,
        /** Lots reçus qui ont des pièces sans fiche : (produit acheté, pièces libres). */
        val freeLots: List<Pair<Long, Int>>,
        val drafts: List<Article>,
        val online: List<Article>,
        val toShip: List<Article>,
    )

    fun lines(stock: StockOverview, sales: List<Sale>): List<Line> {
        val toShipIds = sales.filter { it.shippingStatus == ShippingStatus.TO_SHIP && it.articleId != null }.map { it.articleId!! }.toSet()
        return stock.products.mapNotNull { p ->
            val free = p.lots.filter { it.received && it.remaining > 0 }.map { it.itemId to it.remaining }
            val drafts = p.articles.filter { it.status == ArticleStatus.DRAFT }
            val online = p.articles.filter { it.status == ArticleStatus.ONLINE || it.status == ArticleStatus.RESERVED }
            val toShip = p.articles.filter { it.status == ArticleStatus.SOLD && it.id in toShipIds }
            val expected = free.sumOf { it.second } + drafts.size + online.size + toShip.size
            if (expected == 0) return@mapNotNull null
            Line(
                key = p.key,
                name = p.articles.firstOrNull { it.status.inStock }?.title ?: p.template?.title ?: p.name,
                image = p.articles.firstNotNullOfOrNull { it.photos.firstOrNull() } ?: p.imageFile,
                variant = ExpressListing.variant(p.name),
                expected = expected,
                freeLots = free,
                drafts = drafts,
                online = online,
                toShip = toShip,
            )
        }.sortedWith(compareBy<Line> { ExpressListing.modelName(it.name) }.thenBy { it.variant })
    }

    /** Ce qu'il faut faire quand on en compte moins que prévu. */
    data class Shortfall(
        /** Pièces sans fiche à déclarer manquantes, par produit acheté. */
        val missing: List<Pair<Long, Int>>,
        /** Brouillons à retirer (la pièce n'existe plus). */
        val withdrawDrafts: List<Article>,
        /** Pièces encore manquantes après ça : annonces en ligne à vérifier à la main. */
        val unresolved: Int,
    )

    fun shortfall(line: Line, counted: Int): Shortfall {
        var gap = (line.expected - counted).coerceAtLeast(0)
        val missing = mutableListOf<Pair<Long, Int>>()
        for ((itemId, free) in line.freeLots) {
            if (gap == 0) break
            val k = minOf(free, gap)
            missing += itemId to k
            gap -= k
        }
        val drafts = line.drafts.sortedBy { it.photos.size }.take(gap)
        gap -= drafts.size
        return Shortfall(missing, drafts, gap)
    }
}
