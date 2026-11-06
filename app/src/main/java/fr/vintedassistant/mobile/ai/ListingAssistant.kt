package fr.vintedassistant.mobile.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ListingDraft
import fr.vintedassistant.mobile.domain.ListingTemplates
import fr.vintedassistant.mobile.domain.Negotiator
import fr.vintedassistant.mobile.domain.ReplyTemplates
import kotlinx.coroutines.CancellationException

enum class AiAvailability(val label: String) {
    READY("IA prête (sur le téléphone, hors ligne)"),
    DOWNLOADABLE("Modèle IA à télécharger (une fois, gratuit)"),
    DOWNLOADING("Téléchargement du modèle IA en cours"),
    UNAVAILABLE("IA indisponible sur cet appareil : modèles de texte utilisés"),
}

/** Résultat d'une rédaction : [usedAi] indique si l'IA a réellement servi ou si le modèle de texte a pris le relais. */
data class AssistantResult<T>(val value: T, val usedAi: Boolean, val note: String? = null)

/**
 * Assistant de rédaction. Utilise Gemini Nano sur le téléphone (ML Kit Prompt API, gratuit,
 * hors ligne) quand il est disponible, sinon les modèles de texte. Ne fonctionne qu'avec
 * l'application au premier plan : c'est une contrainte d'Android, pas un choix.
 */
class ListingAssistant(@Suppress("unused") private val context: Context) {

    private val model: GenerativeModel by lazy { Generation.getClient() }

    suspend fun availability(): AiAvailability = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> AiAvailability.READY
            FeatureStatus.DOWNLOADABLE -> AiAvailability.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> AiAvailability.DOWNLOADING
            else -> AiAvailability.UNAVAILABLE
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AiAvailability.UNAVAILABLE
    }

    /** Télécharge le modèle. Renvoie true une fois terminé avec succès. */
    suspend fun download(): Boolean = try {
        var completed = false
        model.download().collect { status ->
            if (status is DownloadStatus.DownloadCompleted) completed = true
            if (status is DownloadStatus.DownloadFailed) completed = false
        }
        completed
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun draftListing(article: Article, photo: Bitmap?, aiEnabled: Boolean): AssistantResult<ListingDraft> {
        val fallback = ListingTemplates.draft(article)
        if (!aiEnabled) return AssistantResult(fallback, usedAi = false)
        if (availability() != AiAvailability.READY) {
            return AssistantResult(fallback, usedAi = false, note = "IA non prête : annonce rédigée avec le modèle de texte.")
        }
        return try {
            val prompt = AiPrompts.listing(article, hasPhoto = photo != null)
            val request = if (photo != null) {
                generateContentRequest(ImagePart(photo), TextPart(prompt)) { temperature = 0.3f }
            } else {
                generateContentRequest(TextPart(prompt)) { temperature = 0.3f }
            }
            val text = model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
            val parsed = AiPrompts.parseListing(text, article)
            if (parsed != null) {
                AssistantResult(parsed, usedAi = true, note = "Rédigé par l'IA : relis avant de publier.")
            } else {
                AssistantResult(fallback, usedAi = false, note = "Réponse de l'IA inexploitable : modèle de texte utilisé.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AssistantResult(fallback, usedAi = false, note = "Erreur de l'IA : modèle de texte utilisé.")
        }
    }

    suspend fun reply(article: Article, buyerMessage: String, aiEnabled: Boolean): AssistantResult<String> {
        val fallback = ReplyTemplates.reply(buyerMessage, article)
        if (!aiEnabled || availability() != AiAvailability.READY) return AssistantResult(fallback, usedAi = false)
        return try {
            val floor = Negotiator.effectiveFloor(article.priceCents, article.floorPriceCents)
            val request = generateContentRequest(TextPart(AiPrompts.reply(article, buyerMessage, floor))) { temperature = 0.4f }
            val text = model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
            val cleaned = AiPrompts.cleanReply(text)
            if (cleaned != null) {
                AssistantResult(cleaned, usedAi = true, note = "Proposé par l'IA : relis avant d'envoyer.")
            } else {
                AssistantResult(fallback, usedAi = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AssistantResult(fallback, usedAi = false, note = "Erreur de l'IA : modèle de texte utilisé.")
        }
    }
}
