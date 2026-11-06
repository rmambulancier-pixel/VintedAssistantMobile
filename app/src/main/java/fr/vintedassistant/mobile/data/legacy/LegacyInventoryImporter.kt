package fr.vintedassistant.mobile.data.legacy

import android.content.SharedPreferences
import fr.vintedassistant.mobile.data.local.ArticleDao
import fr.vintedassistant.mobile.data.local.ArticleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * Reprend une seule fois les articles de la version 0.1 dans la base Room.
 *
 * Sécurités :
 * - les anciennes données ne sont jamais effacées (copie de secours conservée) ;
 * - l'insertion est idempotente : relancer l'import ne crée pas de doublon ;
 * - en cas de JSON illisible, rien n'est marqué comme fait : une version corrigée pourra réessayer.
 */
class LegacyInventoryImporter(
    private val prefs: SharedPreferences,
    private val dao: ArticleDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        data object AlreadyDone : Outcome
        data object NothingToImport : Outcome
        data class Imported(val count: Int, val skipped: Int) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun importIfNeeded(): Outcome = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_DONE, false)) return@withContext Outcome.AlreadyDone

        val raw = prefs.getString(KEY_ITEMS, null)
        if (raw.isNullOrBlank()) {
            markDone()
            return@withContext Outcome.NothingToImport
        }

        val parsed = try {
            LegacyInventoryParser.parse(raw)
        } catch (e: JSONException) {
            return@withContext Outcome.Failed(
                "Les données de l'ancienne version sont illisibles. Elles sont conservées telles quelles."
            )
        }

        val now = clock()
        val entities = parsed.items.map {
            ArticleEntity(
                id = it.id,
                title = it.title,
                costCents = it.costCents,
                priceCents = it.priceCents,
                status = it.status,
                // En v0.1, l'identifiant était l'heure de création.
                createdAt = it.id,
                updatedAt = now,
            )
        }
        val inserted = dao.insertAllIgnoringExisting(entities).count { it != -1L }
        markDone()
        Outcome.Imported(count = inserted, skipped = parsed.skipped)
    }

    private fun markDone() {
        prefs.edit().putBoolean(KEY_DONE, true).commit()
    }

    companion object {
        const val PREFS_NAME = "vinted_assistant"
        const val KEY_ITEMS = "items"
        const val KEY_DONE = "legacy_imported_to_room_v1"
    }
}
