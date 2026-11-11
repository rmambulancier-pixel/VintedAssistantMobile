package fr.vintedassistant.mobile.domain

/**
 * Registre du stock, pièce par pièce. Chaque pièce achetée a un seul état à un instant donné, et le bilan
 * de chaque bijou se vérifie d'un coup d'œil :
 *
 *   achetées = manquantes + en réassort + chez moi + vendues parties + retirées
 *
 * « Chez moi » regroupe tout ce qui est physiquement en main (sans annonce, brouillon, en vente, réservée,
 * vendue mais colis pas encore parti). Le registre ne modifie rien : il lit les achats, les fiches et les ventes,
 * et signale ce qui ne colle pas.
 */
object StockRegister {

    enum class Group(val label: String) {
        IN_HAND("Chez moi"),
        RESTOCK("En réassort"),
        GONE("Vendues, parties"),
        LOST("Manquantes"),
        OUT("Retirées"),
    }

    enum class State(val label: String, val group: Group) {
        NO_LISTING("Sans annonce", Group.IN_HAND),
        DRAFT("Brouillon", Group.IN_HAND),
        ONLINE("En vente", Group.IN_HAND),
        RESERVED("Réservée", Group.IN_HAND),
        TO_SHIP("Vendue, colis à préparer", Group.IN_HAND),
        RESTOCK("En réassort", Group.RESTOCK),
        SHIPPING("Vendue, en route vers l'acheteur", Group.GONE),
        DELIVERED("Vendue, livrée", Group.GONE),
        MISSING("Jamais reçue ou perdue", Group.LOST),
        WITHDRAWN("Retirée", Group.OUT),
    }

    data class Piece(
        val lot: StockLot,
        val state: State,
        val article: Article? = null,
        val sale: Sale? = null,
        /** Fiche rattachée au-delà du nombre de pièces reçues : une fiche de trop. */
        val extra: Boolean = false,
    )

    enum class IssueKind { EXTRA_LISTING, SOLD_WITHOUT_SALE, SALE_NOT_SOLD, ORPHAN_SALE, SUSPICIOUS_LINE }

    data class Issue(
        val kind: IssueKind,
        val text: String,
        val articleId: Long? = null,
        val saleId: Long? = null,
        val itemId: Long? = null,
    )

    data class Sheet(
        val product: StockProduct,
        val title: String,
        val variant: String,
        val skus: List<String>,
        val pieces: List<Piece>,
        val issues: List<Issue>,
    ) {
        /** Pièces commandées, manquantes comprises. */
        val bought: Int get() = product.lots.sumOf { it.quantity + it.missing }
        fun count(g: Group): Int = pieces.count { it.state.group == g }
        fun count(s: State): Int = pieces.count { it.state == s }
        /** Somme des états : doit être égale à [bought]. Un écart veut dire des fiches de trop. */
        val accounted: Int get() = pieces.size
        val balanced: Boolean get() = accounted == bought
        val image: String? get() = pieces.firstNotNullOfOrNull { it.article?.photos?.firstOrNull() } ?: product.imageFile
        val inHandCostCents: Long get() = pieces.filter { it.state.group == Group.IN_HAND }.sumOf { it.article?.costCents ?: it.lot.landedUnitCents }
    }

    fun build(stock: StockOverview, sales: List<Sale>): List<Sheet> {
        val saleOf = sales.filter { it.articleId != null && it.counted }.groupBy { it.articleId!! }
            .mapValues { (_, l) -> l.maxByOrNull { it.soldAt }!! }
        val orphans = sales.filter { it.counted && it.articleId == null }
        return stock.products.map { p -> sheet(p, saleOf, orphans) }
    }

    private fun sheet(p: StockProduct, saleOf: Map<Long, Sale>, orphans: List<Sale>): Sheet {
        val pieces = mutableListOf<Piece>()
        val issues = mutableListOf<Issue>()
        p.lots.forEach { lot ->
            val linked = p.articles.filter { it.purchaseItemId == lot.itemId }
                .sortedWith(compareBy<Article> { order(it, saleOf[it.id]) }.thenBy { it.createdAt })
            repeat(lot.missing) { pieces += Piece(lot, State.MISSING) }
            linked.forEachIndexed { i, a ->
                val sale = saleOf[a.id]
                pieces += Piece(lot, stateOf(a, sale), a, sale, extra = i >= lot.quantity)
                if (a.status == ArticleStatus.SOLD && sale == null) {
                    issues += Issue(IssueKind.SOLD_WITHOUT_SALE, "Fiche n° ${a.id} marquée vendue sans vente enregistrée.", articleId = a.id)
                }
                if (sale != null && a.status != ArticleStatus.SOLD) {
                    issues += Issue(IssueKind.SALE_NOT_SOLD, "Fiche n° ${a.id} « ${a.status.label} » alors qu'une vente du ${dateText(sale.soldAt)} y est rattachée.", articleId = a.id, saleId = sale.id)
                }
            }
            val free = (lot.quantity - linked.size).coerceAtLeast(0)
            repeat(free) { pieces += Piece(lot, if (lot.received) State.NO_LISTING else State.RESTOCK) }
            val over = linked.size - lot.quantity
            if (over > 0) {
                issues += Issue(
                    IssueKind.EXTRA_LISTING,
                    "$over fiche(s) de plus que de pièces reçues dans la commande du ${dateText(lot.orderedAt)} : une fiche est rattachée au mauvais achat ou en double.",
                    itemId = lot.itemId,
                )
            }
            if (SupplierLines.suspicious(p.name)) {
                issues += Issue(IssueKind.SUSPICIOUS_LINE, "Ligne d'achat douteuse « ${p.name.take(40)} » : ce n'est sans doute pas un bijou.", itemId = lot.itemId)
            }
        }
        val title = p.articles.firstOrNull { it.status.inStock }?.title ?: p.template?.title ?: p.name
        orphans.filter { s -> StockLedger.similarity(s.title, title) >= 0.6 || StockLedger.similarity(s.title, p.name) >= 0.6 }
            .forEach { s ->
                issues += Issue(
                    IssueKind.ORPHAN_SALE,
                    "Vente du ${dateText(s.soldAt)} à ${Money.formatEuros(s.salePriceCents)} sans fiche (« ${s.title.take(40)} ») : si la pièce vendue vient d'ici, rattache-la.",
                    saleId = s.id,
                )
            }
        return Sheet(p, title, ExpressListing.variant(p.name), p.lots.map { it.sku }.filter { it.isNotBlank() }.distinct(), pieces, issues)
    }

    /** Ordre d'affichage dans un lot : les vendues d'abord, puis ce qui est en main. */
    private fun order(a: Article, sale: Sale?): Int = when {
        sale != null || a.status == ArticleStatus.SOLD -> 0
        a.status == ArticleStatus.ONLINE || a.status == ArticleStatus.RESERVED -> 1
        a.status == ArticleStatus.DRAFT -> 2
        else -> 3
    }

    fun stateOf(a: Article, sale: Sale?): State = when (a.status) {
        ArticleStatus.DRAFT -> State.DRAFT
        ArticleStatus.ONLINE -> State.ONLINE
        ArticleStatus.RESERVED -> State.RESERVED
        ArticleStatus.WITHDRAWN -> State.WITHDRAWN
        ArticleStatus.SOLD -> when (sale?.shippingStatus) {
            ShippingStatus.TO_SHIP -> State.TO_SHIP
            ShippingStatus.SHIPPED -> State.SHIPPING
            else -> State.DELIVERED
        }
    }

    /** Historique lisible d'une pièce, du plus ancien au plus récent. */
    fun history(p: Piece): List<String> = buildList {
        add("Commandée le ${dateText(p.lot.orderedAt)} (${p.lot.orderRef}), revient ${Money.formatEuros(p.lot.landedUnitCents)}")
        when {
            p.state == State.MISSING -> add("Jamais reçue ou perdue : hors stock")
            !p.lot.received -> add("En réassort" + (p.lot.deliveryEta.takeIf { it.isNotBlank() }?.let { ", arrivée annoncée $it" } ?: ""))
            else -> add("Reçue")
        }
        p.article?.let { a ->
            add("Fiche n° ${a.id} créée le ${dateText(a.createdAt)}")
            a.firstPublishedAt?.let { add("Mise en ligne le ${dateText(it)}") }
            a.lastPublishedAt?.takeIf { it != a.firstPublishedAt }?.let { add("Republiée le ${dateText(it)}") }
        }
        p.sale?.let { s ->
            add("Vendue le ${dateText(s.soldAt)} à ${Money.formatEuros(s.salePriceCents)}" + if (s.buyer.isNotBlank()) " (${s.buyer})" else "")
            add("Colis : ${s.shippingStatus.label.lowercase()}")
        }
        if (p.state == State.WITHDRAWN) add("Retirée du stock")
    }

    private fun dateText(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.FRANCE))

    /** Total du stock, toutes fiches confondues, pour le contrôle global. */
    data class Totals(val bought: Int, val accounted: Int, val byGroup: Map<Group, Int>, val issues: Int)

    fun totals(sheets: List<Sheet>): Totals = Totals(
        bought = sheets.sumOf { it.bought },
        accounted = sheets.sumOf { it.accounted },
        byGroup = Group.entries.associateWith { g -> sheets.sumOf { it.count(g) } },
        issues = sheets.sumOf { it.issues.size },
    )
}

/** Lignes d'achat qui ne sont pas des bijoux (récapitulatifs de page lus par erreur). */
object SupplierLines {
    private val SUMMARY = Regex(
        """^(items?\s*total|order\s*total|total|sous[- ]total|subtotal|quantit[eé]\s*(de\s*produit)?|frais|shipping|livraison|poids)\b.*[:\d]""",
        RegexOption.IGNORE_CASE,
    )

    fun suspicious(name: String): Boolean {
        return SUMMARY.containsMatchIn(name.trim())
    }
}
