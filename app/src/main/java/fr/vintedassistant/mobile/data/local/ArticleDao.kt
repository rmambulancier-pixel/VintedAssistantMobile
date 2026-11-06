package fr.vintedassistant.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(article: ArticleEntity): Long

    /** Insertion idempotente : un article déjà présent (même id) n'est ni dupliqué ni écrasé. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringExisting(articles: List<ArticleEntity>): List<Long>

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
