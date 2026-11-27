package fr.vintedassistant.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Bijou repéré chez le fournisseur, gardé pour un achat plus tard (« pépite ») : lien d'achat, photo,
 * prix fournisseur et estimation de revente au moment où il a été gardé.
 */
@Entity(tableName = "wish_items", indices = [Index(value = ["url"], unique = true)])
data class WishEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Page du bijou chez le fournisseur (lien d'achat). */
    val url: String,
    @ColumnInfo(name = "image_url") val imageUrl: String = "",
    /** Photo téléchargée dans le dossier privé « photos » (null si le téléchargement a échoué). */
    @ColumnInfo(name = "image_file") val imageFile: String? = null,
    @ColumnInfo(name = "price_cents") val priceCents: Long,
    @ColumnInfo(name = "cost_cents") val costCents: Long,
    @ColumnInfo(name = "sale_cents") val saleCents: Long? = null,
    val multiple: Double? = null,
    /** Base de l'estimation : ventes de modèles proches, ou prix habituel du type de bijou. */
    val basis: String = "",
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "bought_at") val boughtAt: Long? = null,
)

@Dao
interface WishDao {
    @Query("SELECT * FROM wish_items ORDER BY (bought_at IS NOT NULL), multiple DESC, saved_at DESC")
    fun observeAll(): Flow<List<WishEntity>>

    @Query("SELECT * FROM wish_items WHERE url = :url")
    suspend fun byUrl(url: String): WishEntity?

    @Query("SELECT * FROM wish_items WHERE id = :id")
    suspend fun getById(id: Long): WishEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: WishEntity): Long

    @Update
    suspend fun update(item: WishEntity): Int

    @Query("DELETE FROM wish_items WHERE id = :id")
    suspend fun delete(id: Long): Int
}
