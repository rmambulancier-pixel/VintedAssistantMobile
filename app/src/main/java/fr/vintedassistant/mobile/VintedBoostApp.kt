package fr.vintedassistant.mobile

import android.app.Application
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

    val purchaseRepository = fr.vintedassistant.mobile.data.PurchaseRepository(database, photoStore)

    val articleRepository = ArticleRepository(
        db = database,
        importer = LegacyInventoryImporter(legacyPrefs, database.articleDao()),
        photoStore = photoStore,
        labelStore = labelStore,
    )

    val corrections = fr.vintedassistant.mobile.data.Corrections(articleRepository, purchaseRepository)

    val dataExport = fr.vintedassistant.mobile.data.DataExport(context, database, articleRepository, purchaseRepository)
}
