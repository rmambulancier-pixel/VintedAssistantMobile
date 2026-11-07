package fr.vintedassistant.mobile.data.importer

import fr.vintedassistant.mobile.domain.StockLedger

/**
 * Datation des ventes importées sans date, à partir des commandes de l'export Vinted.
 *
 * Règles, de la plus sûre à la moins sûre :
 * 1. Commande qui nomme l'article : rattachée à la fiche vendue de même titre mise en ligne avant la commande
 *    (la plus ancienne si plusieurs fiches identiques).
 * 2. Commande sans titre : rattachée seulement si toutes les fiches vendues encore sans date, mises en ligne
 *    avant la commande et au prix exact de la commande, sont le même bijou (même titre). Sinon, rien n'est deviné.
 * Les commandes annulées et celles antérieures à la première annonce (achats personnels) sont ignorées.
 */
object ExportSales {
    /** Vente sans date d'une fiche vendue. */
    data class Slot(val articleId: Long, val title: String, val priceCents: Long, val listedAt: Long?)

    /** Date et montant trouvés pour la vente de [articleId]. [byTitle] : rattachement par le titre de la commande. */
    data class Dating(val articleId: Long, val soldAt: Long, val priceCents: Long, val byTitle: Boolean, val orderStatus: String = "")

    data class Result(val datings: List<Dating>, val unmatched: List<ExportOrder>, val ignored: Int)

    private const val DAY = 24L * 60 * 60 * 1000

    fun match(orders: List<ExportOrder>, slots: List<Slot>, firstListingAt: Long?): Result {
        val since = firstListingAt?.minus(DAY)
        val valid = orders.filter { !it.cancelled && it.orderedAt != null && (since == null || it.orderedAt >= since) }
            .sortedBy { it.orderedAt }
        val ignored = orders.size - valid.size
        val free = slots.sortedWith(compareBy<Slot> { it.listedAt ?: Long.MIN_VALUE }.thenBy { it.articleId }).toMutableList()
        val datings = mutableListOf<Dating>()
        val leftovers = mutableListOf<ExportOrder>()

        fun take(predicate: (Slot) -> Boolean): Slot? = free.firstOrNull(predicate)?.also { free.remove(it) }

        // 1. Commandes qui nomment leurs articles.
        for (order in valid) {
            val at = order.orderedAt!!
            if (order.items.isEmpty()) {
                leftovers += order
                continue
            }
            var dated = 0L
            for (item in order.items) {
                val key = StockLedger.normalize(item.title)
                val slot = take { StockLedger.normalize(it.title) == key && (it.listedAt == null || it.listedAt <= at) } ?: continue
                val price = item.priceCents ?: if (order.items.size == 1) order.amountCents ?: slot.priceCents else slot.priceCents
                datings += Dating(slot.articleId, at, price, byTitle = true, orderStatus = order.status)
                dated += price
            }
            // Lot dont Vinted ne nomme qu'une partie des articles : le reste est traité comme une commande sans titre.
            val rest = (order.amountCents ?: 0L) - dated
            if (dated == 0L) leftovers += order.copy(items = emptyList())
            else if (rest >= 100) leftovers += order.copy(amountCents = rest, items = emptyList())
        }

        // 2. Commandes sans titre : seulement quand il n'y a aucune ambiguïté.
        val unmatched = mutableListOf<ExportOrder>()
        for (order in leftovers.sortedBy { it.orderedAt }) {
            val at = order.orderedAt!!
            val amount = order.amountCents
            val candidates = if (amount == null) emptyList() else
                free.filter { it.priceCents == amount && (it.listedAt == null || it.listedAt <= at) }
            if (candidates.isNotEmpty() && candidates.map { StockLedger.normalize(it.title) }.distinct().size == 1) {
                val slot = candidates.first()
                free.remove(slot)
                datings += Dating(slot.articleId, at, amount!!, byTitle = false, orderStatus = order.status)
            } else {
                unmatched += order
            }
        }
        return Result(datings, unmatched, ignored)
    }

    /**
     * Annonces vendues d'après les commandes : chaque article nommé dans une commande est rattaché à
     * l'annonce de même titre mise en ligne avant elle (la plus ancienne si plusieurs annonces identiques).
     * [Dating.articleId] contient ici le numéro d'annonce Vinted.
     */
    fun soldListings(listings: List<ExportListing>, orders: List<ExportOrder>): Result {
        val slots = listings.mapNotNull { l -> l.itemId?.let { Slot(it, l.title, l.priceCents ?: 0L, l.createdAt) } }
        return match(orders, slots, listings.mapNotNull { it.createdAt }.minOrNull())
    }
}
