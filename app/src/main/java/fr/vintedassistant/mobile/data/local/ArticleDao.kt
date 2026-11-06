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

    @Query("SELECT * FROM article_photos")
    suspend fun getAll(): List<PhotoEntity>

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

    @Query("SELECT DISTINCT article_id FROM sales WHERE article_id IS NOT NULL")
    suspend fun articleIdsWithSales(): List<Long>

    /** Garde le coût des ventes importées (date inconnue) aligné sur la fiche. */
    @Query("UPDATE sales SET cost_cents = :costCents WHERE article_id = :articleId AND date_known = 0")
    suspend fun syncCostForImported(articleId: Long, costCents: Long): Int

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

@Dao
interface VintedNotificationDao {
    @Query("SELECT * FROM vinted_notifications ORDER BY posted_at DESC LIMIT 300")
    fun observeRecent(): Flow<List<VintedNotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: VintedNotificationEntity): Long

    @Query("UPDATE vinted_notifications SET handled = 1 WHERE id = :id")
    suspend fun markHandled(id: Long): Int

    @Query("SELECT * FROM vinted_notifications")
    suspend fun getAll(): List<VintedNotificationEntity>

    @Query("UPDATE vinted_notifications SET kind = :kind, article_id = :articleId WHERE id = :id")
    suspend fun reclassify(id: Long, kind: String, articleId: Long?): Int

    @Query("UPDATE vinted_notifications SET handled = 1")
    suspend fun markAllHandled(): Int

    @Query("DELETE FROM vinted_notifications WHERE posted_at < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
