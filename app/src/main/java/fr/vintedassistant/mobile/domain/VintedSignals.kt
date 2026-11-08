package fr.vintedassistant.mobile.domain

import java.net.URI
import java.text.Normalizer

/** Identifiant de l'application Vinted sur Android (Google Play). */
const val VINTED_PACKAGE = "fr.vinted"

object VintedLinks {
    private val URL = Regex("""https?://[^\s<>"']+""")

    /** Vrai pour une adresse https d'un domaine Vinted (vinted.fr, www.vinted.fr, vinted.com…). */
    fun isVintedUrl(url: String): Boolean = try {
        val uri = URI(url.trim())
        val host = uri.host?.lowercase() ?: return false
        uri.scheme.equals("https", ignoreCase = true) &&
            Regex("""(^|\.)vinted\.[a-z.]{2,6}$""").containsMatchIn(host)
    } catch (e: Exception) {
        false
    }

    /** Numéro de l'annonce dans un lien « …/items/123456-titre ». */
    fun itemId(url: String): Long? =
        if (!isVintedUrl(url)) null else Regex("""/items/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()

    /** Titre lisible déduit du lien : « /items/123-collier-strass-argent » donne « Collier strass argent ». */
    fun titleFromUrl(url: String): String? {
        val slug = Regex("""/items/\d+-([^/?#]+)""").find(url)?.groupValues?.get(1) ?: return null
        val words = slug.split('-').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    /** Extrait le premier lien Vinted d'un texte partagé (« Regarde cet article : https://… »). */
    fun extract(text: String): String? =
        URL.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.firstOrNull { isVintedUrl(it) }
}

/** Notification Vinted reçue sur le téléphone, telle qu'affichée dans l'application. */
data class VintedNotice(
    val id: Long,
    val postedAt: Long,
    val title: String,
    val text: String,
    val kind: VintedEventKind,
    val articleId: Long?,
    val amountCents: Long?,
    val handled: Boolean,
)

enum class VintedEventKind(val label: String) {
    SALE("Vente"),
    SHIPPING("Expédition"),
    OFFER("Offre"),
    MESSAGE("Message"),
    FAVORITE("Favori"),
    OTHER("Autre");

    companion object {
        fun fromName(name: String): VintedEventKind = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * Classe une notification Vinted à partir de son texte. Le contenu exact des notifications
 * n'est pas documenté par Vinted : mots-clés larges, et « Autre » quand rien ne correspond.
 */
object VintedNotificationClassifier {
    // Ordre important : « s'est vendu… attend le colis » est une vente, pas une expédition.
    private val rules = listOf(
        VintedEventKind.SALE to Regex("s'est vendu|s’est vendu|a été vendu|vendu !|a acheté|a été acheté|nouvelle commande|paiement reçu"),
        VintedEventKind.SHIPPING to Regex("bordereau|étiquette d'envoi|expédi|dépose ton colis|point relais|mondial relay|relais colis|colissimo|chronopost|a été livré|colis livré|livraison"),
        VintedEventKind.OFFER to Regex("offre|propos[eé] .*€|contre-offre"),
        VintedEventKind.FAVORITE to Regex("favori|a aimé|aime ton|aiment ton|❤|coup de cœur"),
        VintedEventKind.MESSAGE to Regex("message|t'a écrit|a écrit|nouvelle conversation|répondu"),
    )

    fun classify(title: String, text: String): VintedEventKind {
        // Message de conversation : « pseudo: texte », le titre étant le pseudo de l'autre membre.
        val pseudo = title.trim()
        if (pseudo.isNotEmpty() && !pseudo.contains(' ') && text.trim().startsWith("$pseudo:")) {
            val body = text.trim().removePrefix("$pseudo:").lowercase()
            return if (Regex("offre|propos[eé] .*€").containsMatchIn(body)) VintedEventKind.OFFER else VintedEventKind.MESSAGE
        }
        val content = (title + " " + text).lowercase()
        return rules.firstOrNull { (_, regex) -> regex.containsMatchIn(content) }?.first ?: VintedEventKind.OTHER
    }

    /** Montant en euros mentionné dans le texte (« 15 € », « 12,50€ »), en centimes. */
    fun amountCents(text: String): Long? {
        val match = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s?€""").find(text) ?: return null
        return Money.parseEuros(match.groupValues[1])
    }

    /** Article dont le nom apparaît dans la notification ; le nom le plus long l'emporte. */
    fun matchArticle(text: String, articles: List<Article>): Article? = matchArticles(text, articles).firstOrNull()

    /**
     * Fiches dont le nom apparaît dans la notification, la plus sûre d'abord. Vinted coupe souvent les titres
     * longs (« Collier Chaîne Tennis Strass Argenté 45cm… ») : un début de titre d'au moins 20 caractères suffit.
     */
    fun matchArticles(text: String, articles: List<Article>): List<Article> = matchScored(text, articles).map { it.first }

    /** Comme [matchArticles], avec le score (titre complet : 1000 + longueur ; début de titre : longueur retrouvée). */
    fun matchScored(text: String, articles: List<Article>): List<Pair<Article, Int>> {
        val haystack = normalize(text).replace("…", " ").replace("...", " ")
        return articles.mapNotNull { a ->
            val t = normalize(a.title)
            when {
                t.length >= 4 && haystack.contains(t) -> a to 1000 + t.length
                t.length >= 20 -> {
                    var n = t.length - 1
                    while (n >= 20 && !haystack.contains(t.substring(0, n))) n--
                    if (n >= 20) a to n else null
                }
                else -> null
            }
        }.sortedByDescending { it.second }
    }

    /**
     * Fiche vendue d'après une notification : la mieux reconnue. Plusieurs fiches au même titre (pièces
     * identiques) : la plus anciennement en ligne. Deux titres différents reconnus aussi bien : on ne devine pas.
     */
    fun soldArticle(text: String, inStock: List<Article>): Article? {
        val scored = matchScored(text, inStock)
        val best = scored.firstOrNull() ?: return null
        val key = normalize(best.first.title)
        if (scored.any { it.second == best.second && normalize(it.first.title) != key }) return null
        return scored.filter { normalize(it.first.title) == key }.map { it.first }
            .minByOrNull { it.lastVisibleAt ?: it.createdAt }
    }

    /** Prix de vente lu dans la notification s'il est plausible (entre la moitié et le prix affiché), sinon null. */
    fun plausiblePrice(text: String, listedCents: Long): Long? =
        amountCents(text)?.takeIf { it in (listedCents + 1) / 2..listedCents }

    internal fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Dates relatives affichées par Vinted (« Il y a une semaine », « il y a 3 jours »). */
object RelativeDates {
    private val UNITS = listOf(
        Regex("minute") to 60_000L,
        Regex("heure") to 3_600_000L,
        Regex("jour") to DAY_MS,
        Regex("semaine") to 7 * DAY_MS,
        Regex("mois") to 30 * DAY_MS,
        Regex("an") to 365 * DAY_MS,
    )

    /** Date estimée (millisecondes), ou null si le texte n'est pas reconnu. */
    fun parse(text: String, now: Long): Long? {
        val t = text.lowercase().trim()
        if (!t.contains("il y a")) return if (t.contains("aujourd")) now else null
        val amount = Regex("""\d+""").find(t)?.value?.toLongOrNull()
            ?: if (Regex("""\b(une?|quelques)\b""").containsMatchIn(t)) 1L else return null
        val unit = UNITS.firstOrNull { (r, _) -> r.containsMatchIn(t.substringAfter("il y a")) }?.second ?: return null
        return now - amount * unit
    }
}
