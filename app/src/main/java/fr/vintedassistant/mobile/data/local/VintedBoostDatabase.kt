package fr.vintedassistant.mobile.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base locale de l'application.
 *
 * Règle : jamais de fallbackToDestructiveMigration. Chaque changement de schéma
 * passe par une migration écrite (ou automatique) et testée, pour ne jamais effacer les données.
 *
 * Historique :
 * - v1 (0.2.0) : articles (titre, coût, prix, statut).
 * - v2 (0.3.0) : fiche complète, photos, ventes, journal des publications.
 */
@Database(
    entities = [ArticleEntity::class, PhotoEntity::class, SaleEntity::class, ListingEventEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class VintedBoostDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
    abstract fun photoDao(): PhotoDao
    abstract fun saleDao(): SaleDao
    abstract fun listingEventDao(): ListingEventDao

    companion object {
        const val FILE_NAME = "vinted_boost.db"

        fun create(context: Context, name: String? = FILE_NAME): VintedBoostDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, VintedBoostDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, VintedBoostDatabase::class.java, name)
            }
            return builder.build()
        }
    }
}
