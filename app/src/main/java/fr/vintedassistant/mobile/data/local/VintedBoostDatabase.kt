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
 * - v3 (0.4.0) : lien de l'annonce Vinted, notifications Vinted reçues.
 * - v4 (0.5.0) : favoris et vues relevés sur Vinted.
 * - v5 (0.6.0) : ventes importées sans date connue.
 * - v6 (0.7.0) : achats fournisseur et coût de revient.
 * - v7 (0.9.0) : code produit fournisseur, commande reçue ou en cours de livraison.
 */
@Database(
    entities = [
        ArticleEntity::class, PhotoEntity::class, SaleEntity::class, ListingEventEntity::class,
        VintedNotificationEntity::class, PurchaseOrderEntity::class, PurchaseItemEntity::class,
    ],
    version = VintedBoostDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class VintedBoostDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
    abstract fun photoDao(): PhotoDao
    abstract fun saleDao(): SaleDao
    abstract fun listingEventDao(): ListingEventDao
    abstract fun vintedNotificationDao(): VintedNotificationDao
    abstract fun purchaseDao(): PurchaseDao

    companion object {
        const val FILE_NAME = "vinted_boost.db"
        const val VERSION = 7

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
