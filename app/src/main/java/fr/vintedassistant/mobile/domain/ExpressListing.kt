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

    /** Mots de coloris d'un titre, avec leurs qualificatifs courants (« rose poudré », « vert émeraude »). */
    private val TONE = java.util.regex.Pattern.compile(
        """\b(dor[ée]e?s?|or|argent[ée]?e?s?|rose|vert|bleu|noir|blanc|rouge|violet)(\s+(poudr[ée]e?|[ée]meraude|onyx|rubis|saphir|nuit|fonc[ée]e?|clair|gold|ros[ée]e?))?\b""",
        // Unicode : « é » compte comme une lettre pour \b (sinon « rose poudré » n'est pas reconnu en bloc).
        java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE or java.util.regex.Pattern.UNICODE_CHARACTER_CLASS,
    ).toRegex()

    /** Coloris d'une ligne fournisseur en mots de titre : « Argent + Rose » donne « argenté et rose ». */
    fun toneWords(variant: String): String {
        val parts = variant.split('+', '/', '&', ',').map { StockLedger.normalize(it) }.filter { it.isNotBlank() }
        return parts.joinToString(" et ") { w ->
            when {
                w == "or" || w.startsWith("or ") || w.contains("dore") -> "doré"
                w.startsWith("argent") -> "argenté"
                w.contains("blanc d or") -> "doré"
                else -> w
            }
        }
    }

    /** Couleur Vinted correspondant au coloris (« Doré, Rose »), pour le champ Couleur de la fiche. */
    fun colorField(variant: String): String = toneWords(variant).split(" et ")
        .map { it.replaceFirstChar { c -> c.uppercase() } }.distinct().take(2).joinToString(", ")

    /**
     * Titre d'une fiche copiée sur un modèle d'une autre couleur : les mots de coloris du modèle sont remplacés par
     * ceux de la ligne achetée, à la même place. Le 19 novembre, une fiche « rose poudré et or » avait été créée pour
     * un bracelet « Argent + Rose ». Titre inchangé s'il ne contredit pas la ligne.
     */
    fun retitle(title: String, lineName: String): String {
        val variant = variant(lineName)
        if (variant.isBlank() || !Coherence.contradicts(title, lineName)) return title
        val words = toneWords(variant)
        if (words.isBlank()) return title
        // Le bloc de coloris du titre : du premier au dernier mot de coloris, liaisons « et » comprises.
        val hits = TONE.findAll(title).toList()
        if (hits.isEmpty()) return title
        val start = hits.first().range.first
        val end = hits.last().range.last + 1
        val block = title.substring(start, end)
        // On ne remplace qu'un bloc compact (coloris et liaisons) ; sinon chaque mot isolément.
        val compact = block.replace(TONE, "").replace(java.util.regex.Pattern.compile("""\b(et|&)\b""", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).toRegex(), "").isBlank()
        val out = if (compact) title.substring(0, start) + words + title.substring(end)
        else {
            var first = true
            TONE.replace(title) { if (first) { first = false; words } else "" }
        }
        return out.replace(Regex("""\s{2,}"""), " ").trim().take(Article.TITLE_MAX_LENGTH)
    }
}
