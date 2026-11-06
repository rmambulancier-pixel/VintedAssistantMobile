package fr.vintedassistant.mobile.domain

/** Article du stock, tel que l'application le manipule. */
data class Article(
    val id: Long,
    val title: String,
    val costCents: Long,
    val priceCents: Long,
    val status: String,
    val createdAt: Long,
) {
    /** Marge brute : prix de vente visé moins coût d'achat (frais non déduits à ce stade). */
    val marginCents: Long get() = priceCents - costCents

    companion object {
        const val STATUS_DRAFT = "Brouillon"
        const val TITLE_MAX_LENGTH = 120
    }
}

/** Données d'un nouvel article, déjà validées. */
data class NewArticle(
    val title: String,
    val costCents: Long,
    val priceCents: Long,
)

sealed interface ArticleValidation {
    data class Valid(val article: NewArticle) : ArticleValidation
    data class Invalid(val message: String) : ArticleValidation
}

/** Vérifie la saisie du formulaire « Ajouter un article ». */
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
