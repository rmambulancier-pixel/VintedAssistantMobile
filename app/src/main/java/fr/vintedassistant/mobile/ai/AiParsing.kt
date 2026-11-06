package fr.vintedassistant.mobile.ai

import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ListingDraft
import fr.vintedassistant.mobile.domain.ListingTemplates
import fr.vintedassistant.mobile.domain.Money

/** Construction des consignes et lecture des réponses de l'IA. Sans dépendance Android : testable. */
object AiPrompts {

    private fun facts(article: Article): String = buildList {
        add("name: ${article.title}")
        if (article.brand.isNotBlank()) add("brand: ${article.brand}")
        if (article.size.isNotBlank()) add("size: ${article.size}")
        if (article.condition.isNotBlank()) add("condition: ${article.condition}")
        if (article.color.isNotBlank()) add("color: ${article.color}")
        if (article.category.isNotBlank()) add("category: ${article.category}")
    }.joinToString("\n")

    /*
     * Consignes en anglais (langue validée par Google pour ce modèle), réponse demandée en français.
     */
    fun listing(article: Article, hasPhoto: Boolean): String = """
        You help a private seller write a listing for Vinted France.
        Write everything in French. Use "tu" if you address the buyer.
        Use only facts given below${if (hasPhoto) " or clearly visible in the photo" else ""}.
        Never invent a brand, size, material or measurement.
        Item facts:
        ${facts(article)}
        Answer with exactly these three lines and nothing else:
        TITRE: <French title, at most 60 characters>
        DESCRIPTION: <3 to 5 short French sentences: what it is, condition, size, a friendly closing>
        MOTS-CLES: <5 to 8 French keywords separated by spaces, no # sign>
    """.trimIndent()

    fun reply(article: Article, buyerMessage: String, floorCents: Long): String = """
        You help a private seller answer a buyer on Vinted France.
        The buyer wrote (in French): "${buyerMessage.take(800).replace("\"", "'")}"
        Item facts:
        ${facts(article)}
        listed price: ${Money.formatEuros(article.priceCents)}
        status: ${article.status.label}
        Rules: reply in French using "tu", at most 3 short sentences, polite and warm.
        Never invent details that are not in the item facts.
        Never agree to a price below ${Money.formatEuros(floorCents)}; if asked for a discount,
        invite the buyer to make an offer on the listing.
        Output only the reply text.
    """.trimIndent()

    /**
     * Lit la réponse au format TITRE / DESCRIPTION / MOTS-CLES.
     * Renvoie null si le format n'est pas respecté (l'appelant bascule alors sur les modèles).
     */
    fun parseListing(text: String, article: Article): ListingDraft? {
        val lines = text.lines().map { it.trim() }
        fun valueOf(prefix: Regex): String? {
            val index = lines.indexOfFirst { prefix.containsMatchIn(it) }
            if (index < 0) return null
            val first = lines[index].replaceFirst(prefix, "").trim()
            // La description peut tenir sur plusieurs lignes jusqu'au champ suivant.
            val rest = lines.drop(index + 1).takeWhile { line -> FIELDS.none { it.containsMatchIn(line) } }
            return (listOf(first) + rest).joinToString("\n").trim().trim('*').trim()
        }
        val title = valueOf(TITLE)?.lineSequence()?.firstOrNull()?.take(ListingTemplates.TITLE_LIMIT)?.trim()
        val description = valueOf(DESCRIPTION)
        if (title.isNullOrBlank() || description.isNullOrBlank()) return null
        val keywordsRaw = valueOf(KEYWORDS).orEmpty()
        val keywords = ListingTemplates.hashtags(article.copy(keywords = keywordsRaw.ifBlank { article.keywords }))
        return ListingDraft(title, description, keywords)
    }

    fun cleanReply(text: String): String? =
        text.trim().trim('"', '«', '»').trim().takeIf { it.isNotEmpty() }?.take(1000)

    private val TITLE = Regex("^\\**\\s*TITRE\\s*\\**\\s*:", RegexOption.IGNORE_CASE)
    private val DESCRIPTION = Regex("^\\**\\s*DESCRIPTION\\s*\\**\\s*:", RegexOption.IGNORE_CASE)
    private val KEYWORDS = Regex("^\\**\\s*MOTS[- ]?CL[EÉ]S\\s*\\**\\s*:", RegexOption.IGNORE_CASE)
    private val FIELDS = listOf(TITLE, DESCRIPTION, KEYWORDS)
}
