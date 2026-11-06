package fr.vintedassistant.mobile.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.vintedassistant.mobile.VintedBoostApp
import fr.vintedassistant.mobile.ai.AiAvailability
import fr.vintedassistant.mobile.ai.ListingAssistant
import fr.vintedassistant.mobile.data.AppSettings
import fr.vintedassistant.mobile.data.ArticleRepository
import fr.vintedassistant.mobile.data.SettingsStore
import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter.Outcome
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.DashboardStats
import fr.vintedassistant.mobile.domain.RepublishPlan
import fr.vintedassistant.mobile.domain.RepublishPlanner
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.ShippingStatus
import fr.vintedassistant.mobile.domain.computeDashboard
import fr.vintedassistant.mobile.reminders.RepublishReminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** État partagé par les onglets : stock, ventes, tableau de bord, plan de republication, réglages. */
class MainViewModel(
    private val appContext: Context,
    private val repository: ArticleRepository,
    private val settingsStore: SettingsStore,
    private val assistant: ListingAssistant,
) : ViewModel() {

    private val clock = MutableStateFlow(System.currentTimeMillis())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _aiStatus = MutableStateFlow<AiAvailability?>(null)
    val aiStatus: StateFlow<AiAvailability?> = _aiStatus.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.settings

    val articles: StateFlow<List<Article>?> =
        repository.articles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sales: StateFlow<List<Sale>?> =
        repository.sales.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dashboard: StateFlow<DashboardStats?> =
        combine(repository.articles, repository.sales, settingsStore.settings, clock) { a, s, st, now ->
            computeDashboard(a, s, st.dashboard, now)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val republishPlan: StateFlow<RepublishPlan?> =
        combine(repository.articles, repository.republishedTimestamps, settingsStore.settings, clock) { a, t, st, now ->
            RepublishPlanner.plan(a, t, st.republish, now)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val outcome = try {
                repository.importLegacyIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed("La reprise des données de l'ancienne version a échoué. Elles sont conservées.")
            }
            describe(outcome)?.let { _message.value = it }
        }
    }

    /** Recalcule ce qui dépend de l'heure (articles qui stagnent, plan du jour). */
    fun refreshClock() {
        clock.value = System.currentTimeMillis()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun show(message: String) {
        _message.value = message
    }

    fun markRepublished(id: Long) = launchSafely("Impossible d'enregistrer la republication.") {
        repository.markRepublished(id)
        refreshClock()
    }

    fun applyPrice(id: Long, cents: Long) = launchSafely("Impossible de changer le prix.") {
        repository.applyPrice(id, cents)
        refreshClock()
        _message.value = "Prix mis à jour ici. Pense à le modifier aussi dans l'annonce Vinted."
    }

    fun setShippingStatus(saleId: Long, status: ShippingStatus) =
        launchSafely("Impossible de changer le statut d'expédition.") { repository.setShippingStatus(saleId, status) }

    fun attachLabel(saleId: Long, uri: Uri) = launchSafely("Bordereau non enregistré : PDF, JPEG ou PNG de 15 Mo maximum.") {
        repository.attachLabel(saleId, uri)
        _message.value = "Bordereau enregistré."
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = settingsStore.current()
        settingsStore.update(transform)
        val after = settingsStore.current()
        val reminderChanged = before.reminderEnabled != after.reminderEnabled || before.reminderHour != after.reminderHour
        if (reminderChanged) {
            if (after.reminderEnabled) RepublishReminder.schedule(appContext, after.reminderHour)
            else RepublishReminder.cancel(appContext)
        }
    }

    fun checkAi() = launchSafely("Impossible de vérifier l'IA.") {
        _aiStatus.value = assistant.availability()
    }

    fun downloadAi() = launchSafely("Téléchargement du modèle IA impossible.") {
        _aiStatus.value = AiAvailability.DOWNLOADING
        val ok = assistant.download()
        _aiStatus.value = assistant.availability()
        _message.value = if (ok) "Modèle IA téléchargé." else "Téléchargement non terminé. Réessaie en Wi-Fi."
    }

    private fun launchSafely(errorMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = errorMessage
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
                val c = app.container
                MainViewModel(app.applicationContext, c.articleRepository, c.settingsStore, c.assistant)
            }
        }
    }
}
