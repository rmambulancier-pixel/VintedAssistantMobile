package fr.vintedassistant.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import fr.vintedassistant.mobile.domain.Article

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "cost_cents") val costCents: Long,
    @ColumnInfo(name = "price_cents") val priceCents: Long,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

fun ArticleEntity.toDomain() = Article(
    id = id,
    title = title,
    costCents = costCents,
    priceCents = priceCents,
    status = status,
    createdAt = createdAt,
)
