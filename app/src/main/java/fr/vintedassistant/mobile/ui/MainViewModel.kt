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
import fr.vintedassistant.mobile.data.PurchaseRepository
import fr.vintedassistant.mobile.domain.StockLedger
import fr.vintedassistant.mobile.domain.StockOverview
import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter.Outcome
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.DashboardStats
import fr.vintedassistant.mobile.domain.RepublishPlan
import fr.vintedassistant.mobile.domain.RepublishPlanner
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.ShippingStatus
import fr.vintedassistant.mobile.domain.VintedNotice
import fr.vintedassistant.mobile.data.importer.ExportReport
import fr.vintedassistant.mobile.data.importer.VintedExportParser
import kotlinx.coroutines.withContext
import fr.vintedassistant.mobile.domain.computeDashboard
import fr.vintedassistant.mobile.reminders.RepublishReminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** État partagé par les onglets : stock, ventes, tableau de bord, plan de republication, réglages. */
class MainViewModel(
    private val appContext: Context,
    private val repository: ArticleRepository,
    private val settingsStore: SettingsStore,
    private val assistant: ListingAssistant,
    private val purchases: PurchaseRepository,
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

    val notices: StateFlow<List<VintedNotice>> =
        repository.notices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _exportReport = MutableStateFlow<ExportReport?>(null)
    val exportReport: StateFlow<ExportReport?> = _exportReport.asStateFlow()

    val dashboard: StateFlow<DashboardStats?> =
        combine(repository.articles, repository.sales, settingsStore.settings, clock) { a, s, st, now ->
            computeDashboard(a, s, st.dashboard, now)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Stock pièce par pièce : achats fournisseur croisés avec les fiches. */
    val stock: StateFlow<StockOverview?> =
        combine(purchases.orders, repository.articles) { o, a -> StockLedger.build(o, a) }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Points à vérifier (doublons, fiches sans lien, ventes sans achat…), affichés sur l'accueil. */
    val findings: StateFlow<List<fr.vintedassistant.mobile.domain.DataAudit.Finding>> =
        combine(repository.articles, repository.sales, stock) { a, s, st ->
            fr.vintedassistant.mobile.domain.DataAudit.findings(a, s, st)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Bijoux à racheter : vendus au moins deux fois, presque plus de stock. */
    val restock: StateFlow<List<fr.vintedassistant.mobile.domain.Restock.Advice>> =
        combine(stock, repository.sales) { st, s -> if (st == null) emptyList() else fr.vintedassistant.mobile.domain.Restock.advise(st, s) }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            // Remise en ordre des imports des versions précédentes (sans effet si déjà fait).
            try {
                val r = repository.cleanUpImportedData()
                val parts = buildList {
                    if (r.salesCreated > 0) add("${r.salesCreated} vente(s) passée(s) ajoutée(s) au tableau de bord")
                    if (r.keywordsMoved > 0) add("mots-clés séparés de la description sur ${r.keywordsMoved} fiche(s)")
                    if (r.photosRemoved > 0) add("${r.photosRemoved} photo(s) de profil retirée(s) des fiches")
                }
                if (parts.isNotEmpty()) _message.value = parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non bloquant : l'application reste utilisable.
            }
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

    val orders: StateFlow<List<fr.vintedassistant.mobile.domain.PurchaseOrder>> =
        purchases.orders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun attachSaleToPurchase(saleId: Long, itemId: Long) = launchSafely("Rattachement impossible.") {
        purchases.attachSaleToPurchase(saleId, itemId)
        _message.value = "Vente rattachée : fiche « Vendu » créée pour la pièce envoyée, coût d'achat repris."
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

    fun markNoticeHandled(id: Long) = launchSafely("Action impossible.") { repository.markNoticeHandled(id) }

    fun markAllNoticesHandled() = launchSafely("Action impossible.") { repository.markAllNoticesHandled() }

    fun linkArticle(id: Long, url: String) = launchSafely("Lien non enregistré.") {
        repository.setVintedUrl(id, url)
        _message.value = "Lien Vinted associé à l'article."
    }

    fun markPublishedOrRepublished(article: Article) = launchSafely("Impossible d'enregistrer la publication.") {
        if (article.firstPublishedAt == null) repository.markPublished(article.id) else repository.markRepublished(article.id)
        refreshClock()
    }

    /** Lit l'archive ZIP de l'export RGPD et affiche ce qui a été trouvé, sans rien importer encore. */
    private var exportUri: Uri? = null

    fun readExport(uri: Uri) = launchSafely("Archive illisible. Choisis le fichier ZIP envoyé par Vinted.") {
        exportUri = uri
        val report = withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(uri)?.use { VintedExportParser.parse(it) }
        } ?: throw IllegalStateException("Fichier introuvable")
        _exportReport.value = report
    }

    fun confirmExportImport() = launchSafely("Import impossible.") {
        val report = _exportReport.value ?: return@launchSafely
        _exportReport.value = null
        if (report.isHtml) {
            val uri = exportUri
            _message.value = "Import de l'export Vinted en cours…"
            val r = repository.importVintedExport(report) { wanted ->
                val found = HashMap<String, ByteArray>()
                if (uri != null) withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { VintedExportParser.readEntries(it, wanted) { name, bytes -> found[name] = bytes } }
                }
                found
            }
            refreshClock()
            _message.value = buildString {
                append("Export Vinted importé : ${r.updated} fiche(s) mise(s) à jour, ${r.created} ajoutée(s)")
                if (r.markedSold > 0) append(", ${r.markedSold} passée(s) en « Vendu »")
                append(". Ventes : ${r.salesDated} datée(s), ${r.salesAdded} ajoutée(s).")
                if (r.photosAdded > 0) append(" ${r.photosAdded} photo(s) ajoutée(s).")
                if (r.unmatchedOrders > 0) append(" ${r.unmatchedOrders} commande(s) non rattachée(s) à une annonce.")
            }
            return@launchSafely
        }
        val added = repository.importFromExport(report.candidates)
        _message.value = "$added article(s) ajouté(s) en brouillon depuis l'export Vinted. Complète leur coût d'achat."
    }

    fun dismissExport() {
        _exportReport.value = null
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

    /**
     * Nouvelle fiche brouillon copiée de [templateId], rattachée au lot [itemId] s'il est donné.
     * [onCreated] reçoit l'identifiant de la nouvelle fiche.
     */
    fun relaunch(templateId: Long, itemId: Long?, onCreated: (Long) -> Unit) =
        launchSafely("Remise en vente impossible : plus de pièce libre pour ce bijou, ou fiche introuvable.") {
            onCreated(purchases.duplicateForSale(templateId, itemId))
        }

    /** Mise en vente express : une fiche brouillon par bijou en stock jamais mis en vente. */
    fun startExpress(onReady: (List<Long>) -> Unit) = launchSafely("Préparation impossible.") {
        val stock = StockLedger.build(purchases.orders.first(), repository.articles.first())
        val plan = fr.vintedassistant.mobile.domain.ExpressListing.plan(stock)
        if (plan.isEmpty()) {
            _message.value = "Aucune pièce reçue sans fiche."
            return@launchSafely
        }
        val sales = repository.sales.first()
        val ids = plan.map { purchases.prepareExpress(it, sales) }
        onReady(ids)
    }

    /** Fiche brouillon vierge depuis un produit acheté (photo et nom du fournisseur). */
    fun draftFromPurchase(itemId: Long, onCreated: (Long) -> Unit) =
        launchSafely("Création de la fiche impossible.") { onCreated(purchases.createDraft(itemId)) }

    /** Rattache une fiche à un lot d'achat : son coût d'achat devient le coût de revient du lot. */
    fun linkToPurchase(articleId: Long, itemId: Long, title: String, costCents: Long) =
        launchSafely("Rattachement impossible.") {
            purchases.link(articleId, itemId)
            _message.value = "« ${title.take(60)} » rattaché : coût d'achat ${fr.vintedassistant.mobile.domain.Money.formatEuros(costCents)}."
        }

    fun setOrderReceived(orderId: Long, received: Boolean) = launchSafely("Mise à jour de la commande impossible.") {
        purchases.setReceived(orderId, received)
        _message.value = if (received) "Commande reçue : ses pièces peuvent être mises en vente." else "Commande remise en cours de livraison."
    }

    fun unlinkPurchase(articleId: Long) = launchSafely("Détachement impossible.") { purchases.unlink(articleId) }

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
                MainViewModel(app.applicationContext, c.articleRepository, c.settingsStore, c.assistant, c.purchaseRepository)
            }
        }
    }
}
