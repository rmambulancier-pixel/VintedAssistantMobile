package fr.vintedassistant.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Achat de fournitures (emballages, matériel photo…) : dépense de l'activité, hors stock à vendre (v10). */
@Entity(tableName = "supplies")
data class SupplyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bought_at") val boughtAt: Long,
    val label: String,
    /** Nom de [fr.vintedassistant.mobile.domain.SupplyKind]. */
    val kind: String,
    val supplier: String,
    val quantity: Int,
    /** Montant payé, port compris. */
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    /** Il en part une dans chaque colis. */
    @ColumnInfo(name = "per_parcel") val perParcel: Boolean,
    val note: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface SupplyDao {
    @Query("SELECT * FROM supplies ORDER BY bought_at DESC, id DESC")
    fun observeAll(): Flow<List<SupplyEntity>>

    @Query("SELECT * FROM supplies WHERE id = :id")
    suspend fun getById(id: Long): SupplyEntity?

    @Insert
    suspend fun insert(item: SupplyEntity): Long

    @Update
    suspend fun update(item: SupplyEntity)

    @Query("DELETE FROM supplies WHERE id = :id")
    suspend fun delete(id: Long)
}
