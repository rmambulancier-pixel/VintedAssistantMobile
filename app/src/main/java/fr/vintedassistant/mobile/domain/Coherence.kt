package fr.vintedassistant.mobile.domain

/**
 * Contrôle de cohérence des données, refait à chaque changement. Il repère les trois erreurs rencontrées en
 * novembre, avant qu'elles ne faussent le stock ou les ventes :
 * - **pièce en double** : une fiche sans achat ressemble à un brouillon sans annonce qui tient la pièce
 *   (bracelet rose rubis argenté, 25 novembre) ;
 * - **vente en double** : deux ventes du même bijou à quelques jours d'écart, dont une créée par l'import
 *   (bracelet ange argenté, 19 novembre) ;
 * - **coloris contradictoire** : le titre de la fiche annonce un métal ou une couleur que la ligne d'achat n'a
 *   pas (« rose poudré et or » sur une ligne « Argent + Rose »).
 */
object Coherence {
    enum class Kind { DUPLICATE_PIECE, DUPLICATE_SALE, VARIANT_MISMATCH }

    data class Issue(
        val kind: Kind,
        val text: String,
        /** Fiche à ouvrir ou à corriger. */
        val articleId: Long?,
        /** DUPLICATE_PIECE : brouillon qui tient la pièce ; DUPLICATE_SALE : vente à supprimer. */
        val otherId: Long?,
        /** DUPLICATE_PIECE : ligne d'achat à récupérer. */
        val itemId: Long? = null,
    )

    /** Ligne d'achat : identifiant et nom du fournisseur (avec le coloris entre parenthèses). */
    data class Line(val itemId: Long, val name: String)

    private val METALS = listOf("dore" to "or", "or" to "or", "argente" to "argent", "argent" to "argent")
    private val COLORS = listOf("rose", "vert", "bleu", "noir", "blanc", "rouge", "violet")

    private fun words(text: String) = StockLedger.normalize(text).split(' ').filter { it.isNotBlank() }.toSet()

    /** Métaux et couleurs cités. « or » seul est ambigu en français (« trésor ») : seulement en mot entier. */
    private fun tones(text: String): Pair<Set<String>, Set<String>> {
        val w = words(text)
        val metals = METALS.filter { it.first in w }.map { it.second }.toSet()
        val colors = COLORS.filter { c -> w.any { it == c || it == c + "s" || it == c + "e" || it == c + "es" } }.toSet()
        return metals to colors
    }

    /** Coloris d'une ligne fournisseur : le texte entre les dernières parenthèses (« Argent + Rose »). */
    fun variantOf(lineName: String): String = Regex("""\(([^()]*)\)\s*$""").find(lineName)?.groupValues?.get(1).orEmpty()

    /**
     * Vrai si le titre contredit le coloris de la ligne : un métal du titre absent de la ligne alors que la ligne
     * en nomme un autre, ou une couleur du titre absente d'une ligne qui nomme d'autres couleurs.
     */
    fun contradicts(title: String, lineName: String): Boolean {
        val variant = variantOf(lineName)
        if (variant.isBlank()) return false
        val (tm, tc) = tones(title)
        val (vm, vc) = tones(variant)
        val metal = tm.isNotEmpty() && vm.isNotEmpty() && (tm - vm).isNotEmpty() && (vm - tm).isNotEmpty()
        val color = tc.isNotEmpty() && vc.isNotEmpty() && tc.intersect(vc).isEmpty()
        return metal || color
    }

    fun check(articles: List<Article>, sales: List<Sale>, lines: List<Line>, now: Long): List<Issue> {
        val out = mutableListOf<Issue>()
        val lineOf = lines.associateBy { it.itemId }
        val withSale = sales.mapNotNull { it.articleId }.toSet()

        // 1. Pièce en double : fiche en stock sans achat ↔ brouillon sans annonce ni vente, titre proche.
        val loose = articles.filter { it.status.inStock && it.purchaseItemId == null }
        val holders = articles.filter {
            it.status == ArticleStatus.DRAFT && it.purchaseItemId != null && it.vintedUrl.isBlank() && it.id !in withSale
        }
        val usedHolders = HashSet<Long>()
        for (a in loose) {
            // Le brouillon doit tenir une ligne compatible avec la fiche (pas un autre coloris).
            val h = holders.filter { it.id !in usedHolders && lineOf[it.purchaseItemId]?.let { l -> !contradicts(a.title, l.name) } != false }
                .map { it to StockLedger.similarity(a.title, it.title) }
                .filter { it.second >= 0.55 }
                .maxByOrNull { it.second }?.first ?: continue
            usedHolders += h.id
            out += Issue(
                Kind.DUPLICATE_PIECE,
                "« ${a.title.take(50)} » n'a pas d'achat, et le brouillon « ${h.title.take(50)} » tient sans doute sa pièce.",
                a.id, h.id, h.purchaseItemId,
            )
        }

        // 2. Vente en double : même titre, 10 jours d'écart au plus, l'une créée à l'import du dressing.
        val counted = sales.filter { it.counted }
        val seen = HashSet<Long>()
        for (s in counted.filter { it.notes.startsWith("Vue vendue à l'import") }) {
            val key = StockLedger.normalize(s.title)
            val twin = counted.firstOrNull {
                it.id != s.id && it.id !in seen && StockLedger.normalize(it.title) == key &&
                    kotlin.math.abs(it.soldAt - s.soldAt) <= 10L * DAY_MS && !it.notes.startsWith("Vue vendue à l'import")
            } ?: continue
            seen += s.id; seen += twin.id
            out += Issue(
                Kind.DUPLICATE_SALE,
                "« ${s.title.take(50)} » : vente créée à l'import, en plus de la vente du même bijou quelques jours avant.",
                s.articleId, s.id,
            )
        }

        // 3. Coloris contradictoire entre le titre et la ligne d'achat (fiches encore en stock).
        for (a in articles.filter { it.status.inStock && it.purchaseItemId != null }) {
            val line = lineOf[a.purchaseItemId] ?: continue
            if (contradicts(a.title, line.name)) {
                out += Issue(
                    Kind.VARIANT_MISMATCH,
                    "« ${a.title.take(50)} » est rattaché à la ligne « ${variantOf(line.name)} » : coloris à vérifier.",
                    a.id, null, a.purchaseItemId,
                )
            }
        }
        return out
    }
}
