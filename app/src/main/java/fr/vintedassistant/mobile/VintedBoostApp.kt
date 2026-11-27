package fr.vintedassistant.mobile

import android.app.Application
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import android.content.Context
import fr.vintedassistant.mobile.ai.ListingAssistant
import fr.vintedassistant.mobile.data.ArticleRepository
import fr.vintedassistant.mobile.data.SettingsStore
import fr.vintedassistant.mobile.data.files.LabelStore
import fr.vintedassistant.mobile.data.files.PhotoStore
import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import fr.vintedassistant.mobile.reminders.RepublishReminder

class VintedBoostApp : Application() {
    /** Créé à la première utilisation, une seule fois pour toute l'application. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        RepublishReminder.createChannel(this)
        fr.vintedassistant.mobile.reminders.ShippingReminder.schedule(this)
        fr.vintedassistant.mobile.reminders.AutoBackup.schedule(this)
        watchWidget()
    }

    /** Le widget suit les données : une vente, un colis déposé, une fiche publiée le mettent à jour (2 s après). */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchWidget() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        scope.launch {
            kotlinx.coroutines.flow.combine(
                container.articleRepository.articles,
                container.articleRepository.sales,
                container.purchaseRepository.orders,
                container.settingsStore.settings,
            ) { _, _, _, _ -> Unit }
                .debounce(2_000)
                .collect { fr.vintedassistant.mobile.widget.SalesWidget.refresh(this@VintedBoostApp) }
        }
    }
}

/**
 * Assemblage des dépendances, fait à la main : plus lisible et plus léger
 * qu'un framework d'injection tant que l'application reste de cette taille.
 */
class AppContainer(context: Context) {
    private val database = VintedBoostDatabase.create(context)
    private val legacyPrefs = context.getSharedPreferences(LegacyInventoryImporter.PREFS_NAME, Context.MODE_PRIVATE)

    val photoStore = PhotoStore(context)
    val labelStore = LabelStore(context)
    val settingsStore = SettingsStore(context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE))
    val assistant = ListingAssistant(context)
    val photoEnhancer = fr.vintedassistant.mobile.data.files.PhotoEnhancer(context)
    val labelScanner = fr.vintedassistant.mobile.data.files.LabelScanner(context)

    val purchaseRepository = fr.vintedassistant.mobile.data.PurchaseRepository(database, photoStore)

    val articleRepository = ArticleRepository(
        db = database,
        importer = LegacyInventoryImporter(legacyPrefs, database.articleDao()),
        photoStore = photoStore,
        labelStore = labelStore,
    )

    val corrections = fr.vintedassistant.mobile.data.Corrections(articleRepository, purchaseRepository)

    val wishes = fr.vintedassistant.mobile.data.WishRepository(database, photoStore)

    val incidents = fr.vintedassistant.mobile.data.IncidentStore(context)

    val dataExport = fr.vintedassistant.mobile.data.DataExport(context, database, articleRepository, purchaseRepository, incidents)

    val backup = fr.vintedassistant.mobile.data.Backup(
        context = context,
        db = database,
        settingsPrefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
        closeDatabase = { database.close() },
    )
}
