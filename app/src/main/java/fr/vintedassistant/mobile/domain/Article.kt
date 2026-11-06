package fr.vintedassistant.mobile.domain

/** Cycle de vie d'un article. Le libellé est aussi la valeur stockée en base. */
enum class ArticleStatus(val label: String) {
    DRAFT("Brouillon"),
    ONLINE("En ligne"),
    RESERVED("Réservé"),
    SOLD("Vendu"),
    WITHDRAWN("Retiré");

    /** Article encore en stock (ni vendu, ni retiré). */
    val inStock: Boolean get() = this == DRAFT || this == ONLINE || this == RESERVED

    companion object {
        fun fromLabel(label: String): ArticleStatus = entries.firstOrNull { it.label == label } ?: DRAFT
    }
}

/** États proposés par Vinted, repris tels quels pour faciliter la copie. */
object Conditions {
    val ALL = listOf("Neuf avec étiquette", "Neuf sans étiquette", "Très bon état", "Bon état", "Satisfaisant")
}

/** Article du stock, tel que l'application le manipule. */
data class Article(
    val id: Long,
    val title: String,
    val costCents: Long,
    val priceCents: Long,
    val status: ArticleStatus,
    val createdAt: Long,
    val floorPriceCents: Long? = null,
    val brand: String = "",
    val size: String = "",
    val category: String = "",
    val condition: String = "",
    val color: String = "",
    val description: String = "",
    val keywords: String = "",
    val firstPublishedAt: Long? = null,
    val lastPublishedAt: Long? = null,
    val photos: List<String> = emptyList(),
    val vintedUrl: String = "",
) {
    /** Marge brute : prix affiché moins coût d'achat (frais et cotisations non déduits). */
    val marginCents: Long get() = priceCents - costCents

    /** Date de la dernière mise en avant sur Vinted (publication ou republication). */
    val lastVisibleAt: Long? get() = lastPublishedAt ?: firstPublishedAt

    companion object {
        const val TITLE_MAX_LENGTH = 120
        const val TEXT_MAX_LENGTH = 2000
    }
}

/** Données d'un nouvel article minimal (formulaire rapide), déjà validées. */
data class NewArticle(
    val title: String,
    val costCents: Long,
    val priceCents: Long,
)

sealed interface ArticleValidation {
    data class Valid(val article: NewArticle) : ArticleValidation
    data class Invalid(val message: String) : ArticleValidation
}

/** Vérifie la saisie minimale : nom, coût, prix. */
fun validateNewArticle(title: String, costText: String, priceText: String): ArticleValidation {
    val cleanTitle = title.trim()
    if (cleanTitle.isEmpty()) {
        return ArticleValidation.Invalid("Indique le nom de l'article.")
    }
    if (cleanTitle.length > Article.TITLE_MAX_LENGTH) {
        return ArticleValidation.Invalid("Le nom ne doit pas dépasser ${Article.TITLE_MAX_LENGTH} caractères.")
    }
    val cost = Money.parseEuros(costText)
        ?: return ArticleValidation.Invalid("Coût d'achat invalide (exemple : 12,50).")
    val price = Money.parseEuros(priceText)
        ?: return ArticleValidation.Invalid("Prix de vente invalide (exemple : 25).")
    return ArticleValidation.Valid(NewArticle(cleanTitle, cost, price))
}

/** Saisie brute de la fiche article complète. */
data class ArticleForm(
    val title: String = "",
    val cost: String = "",
    val price: String = "",
    val floorPrice: String = "",
    val brand: String = "",
    val size: String = "",
    val category: String = "",
    val condition: String = "",
    val color: String = "",
    val description: String = "",
    val keywords: String = "",
    val vintedUrl: String = "",
)

/** Champs validés de la fiche article. */
data class ArticleFields(
    val title: String,
    val costCents: Long,
    val priceCents: Long,
    val floorPriceCents: Long?,
    val brand: String,
    val size: String,
    val category: String,
    val condition: String,
    val color: String,
    val description: String,
    val keywords: String,
    val vintedUrl: String = "",
)

sealed interface FormValidation {
    data class Valid(val fields: ArticleFields) : FormValidation
    data class Invalid(val message: String) : FormValidation
}

fun validateArticleForm(form: ArticleForm): FormValidation {
    val base = when (val v = validateNewArticle(form.title, form.cost, form.price)) {
        is ArticleValidation.Invalid -> return FormValidation.Invalid(v.message)
        is ArticleValidation.Valid -> v.article
    }
    val floor = if (form.floorPrice.isBlank()) {
        null
    } else {
        Money.parseEuros(form.floorPrice)
            ?: return FormValidation.Invalid("Prix plancher invalide (exemple : 18).")
    }
    if (floor != null && floor > base.priceCents) {
        return FormValidation.Invalid("Le prix plancher doit être inférieur ou égal au prix affiché.")
    }
    val description = form.description.trim()
    if (description.length > Article.TEXT_MAX_LENGTH) {
        return FormValidation.Invalid("La description ne doit pas dépasser ${Article.TEXT_MAX_LENGTH} caractères.")
    }
    val url = form.vintedUrl.trim().let { VintedLinks.extract(it) ?: it }
    if (url.isNotEmpty() && !VintedLinks.isVintedUrl(url)) {
        return FormValidation.Invalid("Le lien doit être une adresse Vinted (https://www.vinted.fr/…).")
    }
    fun short(s: String) = s.trim().take(Article.TITLE_MAX_LENGTH)
    return FormValidation.Valid(
        ArticleFields(
            title = base.title,
            costCents = base.costCents,
            priceCents = base.priceCents,
            floorPriceCents = floor,
            brand = short(form.brand),
            size = short(form.size),
            category = short(form.category),
            condition = short(form.condition),
            color = short(form.color),
            description = description,
            keywords = form.keywords.trim().take(Article.TEXT_MAX_LENGTH),
            vintedUrl = url.take(500),
        )
    )
}

/** Pré-remplit le formulaire à partir d'un article existant. */
fun Article.toForm() = ArticleForm(
    title = title,
    cost = Money.formatPlain(costCents),
    price = Money.formatPlain(priceCents),
    floorPrice = floorPriceCents?.let { Money.formatPlain(it) } ?: "",
    brand = brand,
    size = size,
    category = category,
    condition = condition,
    color = color,
    description = description,
    keywords = keywords,
    vintedUrl = vintedUrl,
)
