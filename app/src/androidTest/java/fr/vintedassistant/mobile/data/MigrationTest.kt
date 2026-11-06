package fr.vintedassistant.mobile.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduit une base créée par la version 0.2.0 (schéma v1, SQL copié de app/schemas/…/1.json),
 * puis l'ouvre avec la version actuelle : la migration automatique 1 → 2 doit conserver chaque article.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun version1DatabaseIsMigratedWithoutLosingArticles() = runBlocking {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `articles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL, `cost_cents` INTEGER NOT NULL, `price_cents` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'faf628667075533e9cfc718f24218409')")
            db.execSQL(
                "INSERT INTO articles (id, title, cost_cents, price_cents, status, created_at, updated_at) VALUES " +
                    "(1760777000000, 'Veste en jean', 850, 2500, 'Brouillon', 1760777000000, 1760777000000), " +
                    "(1760777100000, 'Robe', 1999, 3510, 'Brouillon', 1760777100000, 1760777100000)"
            )
            db.version = 1
        }

        val database = VintedBoostDatabase.create(context, name)
        try {
            val articles = database.articleDao().getAll().sortedBy { it.id }
            assertEquals(2, articles.size)
            val veste = articles[0]
            assertEquals("Veste en jean", veste.title)
            assertEquals(850L, veste.costCents)
            assertEquals(2500L, veste.priceCents)
            assertEquals("", veste.brand)
            assertEquals("", veste.description)
            assertNull(veste.floorPriceCents)
            assertNull(veste.firstPublishedAt)
            assertEquals(1999L, articles[1].costCents)
            // Les nouvelles tables existent et sont vides.
            assertEquals(0, database.photoDao().forArticle(veste.id).size)
        } finally {
            database.close()
        }
    }
}
