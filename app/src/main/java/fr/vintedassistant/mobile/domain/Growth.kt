package fr.vintedassistant.mobile.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Leviers de chiffre d'affaires, calculés sur les ventes réelles (relevé du 26 novembre : 36 ventes, médiane de
 * 2 jours entre mise en ligne et vente, 43 pièces hors vitrine). Tout est indicatif : l'utilisateur décide.
 */
object Growth {
    private val ZONE = ZoneId.of("Europe/Paris")

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val s = values.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun medianD(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Délai entre mise en ligne et vente, en jours (ventes datées seulement). */
    private fun days(s: Sale): Double? = if (!s.dateKnown) null else s.listedAt?.let { (s.soldAt - it).coerceAtLeast(0).toDouble() / DAY_MS }

    // ---------- 1. Vitrine ----------

    data class Showcase(val notListed: Int, val drafts: Int, val inTransit: Int, val online: Int, val medianPriceCents: Long, val medianDays: Double?) {
        val waiting: Int get() = notListed + drafts + inTransit
        val waitingValueCents: Long get() = waiting * medianPriceCents
    }

    fun showcase(stock: StockOverview, articles: List<Article>, sales: List<Sale>, now: Long): Showcase {
        val recent = sales.filter { it.counted && now - it.soldAt <= 90 * DAY_MS }
        return Showcase(
            notListed = stock.notListed,
            drafts = articles.count { it.status == ArticleStatus.DRAFT },
            inTransit = stock.inTransit,
            online = articles.count { it.status == ArticleStatus.ONLINE || it.status == ArticleStatus.RESERVED },
            medianPriceCents = median(recent.map { it.salePriceCents }) ?: 0,
            medianDays = medianD(recent.mapNotNull(::days)),
        )
    }

    // ---------- 2. Prix ----------

    data class PriceAdvice(
        val model: String,
        val title: String,
        val sold: Int,
        val medianDays: Double,
        val medianPriceCents: Long,
        val suggestedCents: Long,
        /** Brouillons de ce modèle à qui appliquer le nouveau prix (pas encore publiés). */
        val draftIds: List<Long>,
    ) {
        val gainPerSaleCents: Long get() = suggestedCents - medianPriceCents
    }

    /** Hausse proposée : +1 € jusqu'à 10 €, +10 % au-delà (arrondi à 0,50 €). */
    fun raise(cents: Long): Long = if (cents < 1000) cents + 100 else (cents * 110 / 100).let { it - it % 50 }

    /**
     * Modèles vendus au moins 3 fois avec un délai médian de 3 jours au plus, sans pièce qui stagne en ligne :
     * la demande dépasse probablement le prix. Groupement par modèle fournisseur (toutes couleurs), sinon par titre.
     */
    fun priceAdvice(stock: StockOverview, articles: List<Article>, sales: List<Sale>, now: Long, stagnantDays: Int = 21): List<PriceAdvice> {
        val modelOf = HashMap<Long, String>()
        stock.products.forEach { p -> p.articles.forEach { modelOf[it.id] = ExpressListing.modelName(p.name) } }
        fun model(articleId: Long?, title: String) = articleId?.let { modelOf[it] } ?: ("t:" + StockLedger.normalize(title))
        return sales.filter { it.counted && now - it.soldAt <= 120 * DAY_MS }
            .groupBy { model(it.articleId, it.title) }
            .mapNotNull { (m, list) ->
                if (list.size < 3) return@mapNotNull null
                val d = medianD(list.mapNotNull(::days)) ?: return@mapNotNull null
                if (d > 3.0) return@mapNotNull null
                val modelArticles = articles.filter { model(it.id, it.title) == m }
                val stuck = modelArticles.any { a ->
                    a.status == ArticleStatus.ONLINE && (a.lastVisibleAt ?: now) < now - stagnantDays * DAY_MS
                }
                if (stuck) return@mapNotNull null
                val price = median(list.map { it.salePriceCents }) ?: return@mapNotNull null
                val target = raise(price)
                PriceAdvice(
                    model = m,
                    title = list.maxByOrNull { it.soldAt }!!.title,
                    sold = list.size,
                    medianDays = d,
                    medianPriceCents = price,
                    suggestedCents = target,
                    draftIds = modelArticles.filter { it.status == ArticleStatus.DRAFT && it.priceCents in 1 until target }.map { it.id },
                )
            }
            .sortedByDescending { it.sold }
    }

    // ---------- 3. Rachat ----------

    data class RestockLine(
        val productKey: String,
        val title: String,
        val imageFile: String?,
        val sold: Int,
        /** Ventes par mois (sur les 60 derniers jours). */
        val perMonth: Double,
        val available: Int,
        val quantity: Int,
        val unitCostCents: Long,
        val priceCents: Long,
    ) {
        val budgetCents: Long get() = quantity * unitCostCents
        val expectedProfitCents: Long get() = quantity * (priceCents - unitCostCents)
        val multiple: Double get() = if (unitCostCents > 0) priceCents.toDouble() / unitCostCents else 0.0
    }

    /**
     * Quantité à recommander par bijou : rythme de vente des 60 derniers jours × (délai fournisseur + 30 jours de
     * vente), moins ce qui est déjà en main ou en route. 10 pièces au plus par ligne.
     */
    fun restockPlan(stock: StockOverview, sales: List<Sale>, now: Long, leadDays: Int = 21, coverDays: Int = 30): List<RestockLine> {
        val byArticle = sales.filter { it.counted && it.articleId != null }.groupBy { it.articleId!! }
        return stock.products.mapNotNull { p ->
            val own = p.articles.flatMap { byArticle[it.id].orEmpty() }
            val recent = own.filter { now - it.soldAt <= 60 * DAY_MS }
            if (recent.size < 2) return@mapNotNull null
            val perDay = recent.size / 60.0
            val available = p.notListed + p.inTransit + p.drafts + p.forSale
            val need = ceil(perDay * (leadDays + coverDays)).toInt() - available
            if (need <= 0) return@mapNotNull null
            val cost = p.lots.lastOrNull()?.landedUnitCents ?: return@mapNotNull null
            RestockLine(
                productKey = p.key,
                title = p.template?.title?.takeIf { it.isNotBlank() } ?: p.name,
                imageFile = p.imageFile,
                sold = own.size,
                perMonth = perDay * 30,
                available = available,
                quantity = need.coerceAtMost(10),
                unitCostCents = cost,
                priceCents = median(own.map { it.salePriceCents }) ?: 0,
            )
        }.sortedByDescending { it.expectedProfitCents }
    }

    // ---------- 4. Relances ----------

    /** Message à envoyer à qui a mis le bijou en favori : petite remise et rappel des lots. À copier, envoyé par l'utilisateur. */
    fun favoriteOffer(a: Article): String {
        val offer = (a.priceCents * 85 / 100).let { it - it % 50 }.coerceAtLeast(100)
        return "Bonjour ! J'ai vu que « ${a.title.take(60)} » vous plaisait. Je peux vous le faire à ${Money.formatEuros(offer)} " +
            "au lieu de ${Money.formatEuros(a.priceCents)}. Et si vous prenez plusieurs bijoux, la remise sur les lots s'applique " +
            "automatiquement. Envoi rapide et soigné."
    }

    // ---------- Saison ----------

    /** Période des cadeaux de fin d'année : du 15 novembre au 24 décembre. */
    fun giftSeason(date: LocalDate): Boolean =
        (date.monthValue == 11 && date.dayOfMonth >= 15) || (date.monthValue == 12 && date.dayOfMonth <= 24)

    const val GIFT_LINE = "Idée cadeau pour Noël : envoi rapide et soigné."
    const val GIFT_TAGS = "#ideecadeau #cadeaunoel #cadeaufemme"

    /** Description enrichie pendant la saison des cadeaux (une seule fois). */
    fun seasonal(description: String, date: LocalDate): String =
        if (!giftSeason(date) || description.contains(GIFT_LINE)) description else "$GIFT_LINE\n\n$description"

    fun seasonalTags(tags: String, date: LocalDate): String =
        if (!giftSeason(date) || tags.contains("#cadeaunoel")) tags else (tags.trim() + " " + GIFT_TAGS).trim()

    fun today(now: Long): LocalDate = java.time.Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
}
