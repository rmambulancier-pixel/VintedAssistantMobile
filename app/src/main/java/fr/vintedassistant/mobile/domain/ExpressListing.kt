package fr.vintedassistant.mobile.domain

/**
 * Mise en vente express du stock jamais publié : une fiche par bijou qui a des pièces reçues sans fiche.
 * Le texte vient d'abord d'une fiche existante du même modèle (même couleur si possible, sinon une autre
 * couleur du même modèle fournisseur) ; sans modèle, la fiche part du nom fournisseur et l'IA peut la rédiger.
 */
object ExpressListing {

    data class Item(
        val itemId: Long,
        val productName: String,
        val supplierImage: String?,
        /** Fiche à copier, null si aucun modèle existant. */
        val templateId: Long?,
        /** Vrai si le modèle est le même bijou (même code et même couleur) : ses photos conviennent. */
        val sameVariant: Boolean,
    )

    /** Nom fournisseur sans la variante finale : « Bracelet Cœur … (Argent + bleu) » donne « bracelet coeur … ». */
    fun modelName(name: String): String =
        StockLedger.normalize(name.replace(Regex("""\s*\(([^()]*(?:\([^()]*\))?[^()]*)\)\s*$"""), ""))

    /** Variante lue entre parenthèses à la fin du nom fournisseur (« Vert Rose »), vide sinon. */
    fun variant(name: String): String =
        Regex("""\(([^()]*)\)\s*$""").find(name)?.groupValues?.get(1)?.split('|')?.last()?.trim().orEmpty()

    fun plan(stock: StockOverview): List<Item> = stock.products
        .filter { it.notListed > 0 && it.nextLot != null }
        .map { p ->
            val own = p.template?.takeIf { it.description.isNotBlank() || it.photos.isNotEmpty() }
            val family = if (own != null) null else stock.products
                .filter { it.key != p.key && modelName(it.name) == modelName(p.name) }
                .flatMap { it.articles }
                .filter { it.status != ArticleStatus.WITHDRAWN && it.description.isNotBlank() }
                .maxByOrNull { it.createdAt }
            Item(
                itemId = p.nextLot!!.itemId,
                productName = p.name,
                supplierImage = p.imageFile,
                templateId = (own ?: family)?.id,
                sameVariant = own != null,
            )
        }

    /**
     * Prix conseillé d'une nouvelle fiche sans modèle : prix médian des ventes de bijoux proches
     * (même type, titre semblable), sinon null (prix à saisir).
     */
    fun suggestPrice(name: String, sales: List<Sale>): Long? {
        val category = JewelryCategory.guess(name)
        val prices = sales.filter { it.counted && it.salePriceCents > 0 }
            .filter { category == null || JewelryCategory.of(it.category, it.title) == category }
            .filter { StockLedger.similarity(it.title, name) >= 0.5 }
            .map { it.salePriceCents }.sorted()
        if (prices.isEmpty()) return null
        val n = prices.size
        val median = if (n % 2 == 1) prices[n / 2] else (prices[n / 2 - 1] + prices[n / 2]) / 2
        return median - median % 50
    }
}
