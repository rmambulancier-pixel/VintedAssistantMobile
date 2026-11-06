package fr.vintedassistant.mobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base locale de l'application.
 *
 * Règle : jamais de fallbackToDestructiveMigration. Chaque changement de schéma
 * passe par une migration écrite et testée, pour ne jamais effacer les données.
 */
@Database(entities = [ArticleEntity::class], version = 1, exportSchema = true)
abstract class VintedBoostDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao

    companion object {
        const val FILE_NAME = "vinted_boost.db"

        fun create(context: Context): VintedBoostDatabase =
            Room.databaseBuilder(context.applicationContext, VintedBoostDatabase::class.java, FILE_NAME)
                .build()
    }
}
