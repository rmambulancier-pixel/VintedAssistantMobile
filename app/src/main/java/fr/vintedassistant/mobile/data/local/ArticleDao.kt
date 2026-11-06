package fr.vintedassistant.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles ORDER BY created_at DESC, id DESC")
    suspend fun getAll(): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getById(id: Long): ArticleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(article: ArticleEntity): Long

    /** Insertion idempotente : un article déjà présent (même id) n'est ni dupliqué ni écrasé. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringExisting(articles: List<ArticleEntity>): List<Long>

    @Update
    suspend fun update(article: ArticleEntity): Int

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM article_photos ORDER BY article_id, position, id")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM article_photos WHERE article_id = :articleId ORDER BY position, id")
    suspend fun forArticle(articleId: Long): List<PhotoEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM article_photos WHERE article_id = :articleId")
    suspend fun maxPosition(articleId: Long): Int

    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    @Query("DELETE FROM article_photos WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM article_photos WHERE article_id = :articleId")
    suspend fun deleteForArticle(articleId: Long): Int

    @Query("SELECT * FROM article_photos WHERE article_id = :articleId AND file_name = :fileName LIMIT 1")
    suspend fun find(articleId: Long, fileName: String): PhotoEntity?
}

@Dao
interface SaleDao {
    @Query("SELECT * FROM sales ORDER BY sold_at DESC, id DESC")
    fun observeAll(): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun getById(id: Long): SaleEntity?

    @Insert
    suspend fun insert(sale: SaleEntity): Long

    @Update
    suspend fun update(sale: SaleEntity): Int

    @Query("UPDATE sales SET article_id = NULL WHERE article_id = :articleId")
    suspend fun detachArticle(articleId: Long): Int
}

@Dao
interface ListingEventDao {
    @Query("SELECT at FROM listing_events WHERE type = :type ORDER BY at DESC")
    fun observeTimestamps(type: String): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM listing_events WHERE type = :type AND at >= :since")
    suspend fun countSince(type: String, since: Long): Int

    @Insert
    suspend fun insert(event: ListingEventEntity): Long

    @Query("DELETE FROM listing_events WHERE article_id = :articleId")
    suspend fun deleteForArticle(articleId: Long): Int
}
