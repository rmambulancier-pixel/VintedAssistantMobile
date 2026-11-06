package fr.vintedassistant.mobile.domain

import java.text.Normalizer

data class ListingDraft(
    val title: String,
    val description: String,
    val keywords: String,
)

/**
 * Rédaction d'annonce sans IA, à partir des champs de la fiche.
 * Sert de base fiable et de repli quand l'IA embarquée n'est pas disponible.
 */
object ListingTemplates {
    const val TITLE_LIMIT = 80

    fun draft(article: Article): ListingDraft {
        val sizePart = article.size.takeIf { it.isNotBlank() }?.let { "taille $it" }
        val title = listOf(article.title, article.brand, article.color, sizePart)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .take(TITLE_LIMIT)
            .trim()

        val lines = buildList {
            add(listOf(article.title, article.brand).filter { it.isNotBlank() }.joinToString(" "))
            if (article.condition.isNotBlank()) add("État : ${article.condition}")
            if (article.size.isNotBlank()) add("Taille : ${article.size}")
            if (article.color.isNotBlank()) add("Couleur : ${article.color}")
            add("")
            add("Envoi rapide et soigné.")
            add("Une question, une envie de lot ? Écris-moi !")
        }
        val keywords = hashtags(article)
        val description = (lines + if (keywords.isNotBlank()) listOf("", keywords) else emptyList())
            .joinToString("\n")
            .trim()
        return ListingDraft(title, description, keywords)
    }

    /** Mots-clés en hashtags : saisis par l'utilisateur, sinon déduits de la fiche. */
    fun hashtags(article: Article): String {
        val source = article.keywords.ifBlank {
            listOf(article.brand, article.category, article.color, article.title).joinToString(" ")
        }
        return source.split(Regex("[\\s,;#]+"))
            .map { slug(it) }
            .filter { it.length >= 3 }
            .distinct()
            .take(10)
            .joinToString(" ") { "#$it" }
    }

    internal fun slug(word: String): String =
        Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]"), "")
}

enum class BuyerTopic { AVAILABILITY, SIZE, CONDITION, SHIPPING, PRICE, PHOTOS }

/**
 * Réponses aux acheteurs à partir de modèles. Détecte le ou les sujets du message
 * et assemble une réponse avec les informations de la fiche. Rien n'est envoyé :
 * le vendeur copie, relit et envoie lui-même.
 */
object ReplyTemplates {
    private val patterns = mapOf(
        BuyerTopic.AVAILABILITY to Regex("dispo|toujours là|encore là|vendu|available"),
        BuyerTopic.SIZE to Regex("taill|mesure|longueur|largeur|tour de|cm\\b|porté|grand|petit"),
        BuyerTopic.CONDITION to Regex("état|etat|défaut|defaut|tache|trou|usé|use\\b|abîm|abim"),
        BuyerTopic.SHIPPING to Regex("envoi|envoy|livr|expédi|expedi|relais|colis|quand"),
        BuyerTopic.PRICE to Regex("prix|baiss|réduc|reduc|lot|offre|négo|nego|moins cher|dernier prix|€"),
        BuyerTopic.PHOTOS to Regex("photo|image|voir"),
    )

    fun topics(message: String): Set<BuyerTopic> {
        val text = message.lowercase()
        return patterns.filterValues { it.containsMatchIn(text) }.keys
    }

    fun reply(message: String, article: Article): String {
        val found = topics(message)
        val parts = mutableListOf("Bonjour !")
        if (BuyerTopic.AVAILABILITY in found || found.isEmpty()) {
            parts += if (article.status == ArticleStatus.SOLD || article.status == ArticleStatus.WITHDRAWN) {
                "Désolé, l'article n'est plus disponible."
            } else {
                "Oui, l'article est toujours disponible."
            }
        }
        if (BuyerTopic.SIZE in found) {
            parts += if (article.size.isNotBlank()) {
                "Il s'agit d'une taille ${article.size}. Je peux te donner des mesures précises si besoin."
            } else {
                "Je te prends les mesures et je reviens vers toi."
            }
        }
        if (BuyerTopic.CONDITION in found) {
            parts += if (article.condition.isNotBlank()) {
                "Il est en ${article.condition.lowercase()}, tous les détails sont visibles sur les photos."
            } else {
                "Tous les détails de l'état sont visibles sur les photos."
            }
        }
        if (BuyerTopic.PHOTOS in found) parts += "Je peux t'envoyer d'autres photos si tu veux."
        if (BuyerTopic.SHIPPING in found) parts += "J'envoie sous 1 à 2 jours après l'achat, bien emballé."
        if (BuyerTopic.PRICE in found) {
            parts += "Tu peux me faire une offre directement sur l'annonce, et je fais un prix sur les lots."
        }
        parts += "Bonne journée !"
        return parts.joinToString(" ")
    }

    /** Message pour une personne qui a mis l'article en favori. Envoi manuel uniquement. */
    fun favoriteMessage(article: Article, offerCents: Long?): String {
        val offer = offerCents?.takeIf { it in 1 until article.priceCents }
        return buildString {
            append("Bonjour ! J'ai vu que « ${article.title} » te plaît.")
            if (offer != null) {
                append(" Si ça t'intéresse toujours, je peux te le faire à ${Money.formatEuros(offer)}.")
            } else {
                append(" Si tu as une question, n'hésite pas.")
            }
            append(" Bonne journée !")
        }
    }
}
