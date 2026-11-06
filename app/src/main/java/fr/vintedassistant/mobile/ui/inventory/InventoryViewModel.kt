package fr.vintedassistant.mobile.ui.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.vintedassistant.mobile.VintedBoostApp
import fr.vintedassistant.mobile.data.ArticleRepository
import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter.Outcome
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleValidation
import fr.vintedassistant.mobile.domain.validateNewArticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InventoryUiState(
    val items: List<Article> = emptyList(),
    val totalMarginCents: Long = 0,
    val message: String? = null,
    val loading: Boolean = true,
)

class InventoryViewModel(private val repository: ArticleRepository) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<InventoryUiState> =
        combine(repository.articles, message) { items, msg ->
            InventoryUiState(
                items = items,
                totalMarginCents = items.sumOf { it.marginCents },
                message = msg,
                loading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUiState())

    init {
        viewModelScope.launch {
            val outcome = try {
                repository.importLegacyIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed("La reprise des données de l'ancienne version a échoué. Elles sont conservées.")
            }
            message.value = describe(outcome)
        }
    }

    /** Renvoie un message d'erreur de saisie, ou null si l'article est accepté. */
    fun addArticle(title: String, costText: String, priceText: String): String? =
        when (val validation = validateNewArticle(title, costText, priceText)) {
            is ArticleValidation.Invalid -> validation.message
            is ArticleValidation.Valid -> {
                runSafely("Enregistrement impossible. Réessaie.") { repository.add(validation.article) }
                null
            }
        }

    fun deleteArticle(id: Long) {
        runSafely("Suppression impossible. Réessaie.") { repository.delete(id) }
    }

    fun dismissMessage() {
        message.value = null
    }

    private fun runSafely(errorMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = errorMessage
            }
        }
    }

    private fun describe(outcome: Outcome): String? = when (outcome) {
        Outcome.AlreadyDone, Outcome.NothingToImport -> null
        is Outcome.Failed -> outcome.reason
        is Outcome.Imported -> buildString {
            if (outcome.count > 0) append("${outcome.count} article(s) repris de l'ancienne version.")
            if (outcome.skipped > 0) {
                if (isNotEmpty()) append(' ')
                append("${outcome.skipped} entrée(s) incomplète(s) ignorée(s).")
            }
        }.ifEmpty { null }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VintedBoostApp
                InventoryViewModel(app.container.articleRepository)
            }
        }
    }
}
