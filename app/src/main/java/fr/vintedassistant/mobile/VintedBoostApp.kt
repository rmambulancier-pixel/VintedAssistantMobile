package fr.vintedassistant.mobile

import android.app.Application
import android.content.Context
import fr.vintedassistant.mobile.data.ArticleRepository
import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase

class VintedBoostApp : Application() {
    /** Créé à la première utilisation, une seule fois pour toute l'application. */
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * Assemblage des dépendances, fait à la main : plus lisible et plus léger
 * qu'un framework d'injection tant que l'application reste de cette taille.
 */
class AppContainer(context: Context) {
    private val database = VintedBoostDatabase.create(context)
    private val legacyPrefs = context.getSharedPreferences(LegacyInventoryImporter.PREFS_NAME, Context.MODE_PRIVATE)

    val articleRepository = ArticleRepository(
        dao = database.articleDao(),
        importer = LegacyInventoryImporter(legacyPrefs, database.articleDao()),
    )
}
