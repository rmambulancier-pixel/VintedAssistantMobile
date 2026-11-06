package fr.vintedassistant.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.ShippingStatus

/*
 * Schéma v2. Les colonnes ajoutées depuis la v1 ont une valeur par défaut :
 * la migration automatique 1 → 2 conserve toutes les lignes existantes.
 */

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "cost_cents") val costCents: Long,
    @ColumnInfo(name = "price_cents") val priceCents: Long,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // --- v2 ---
    @ColumnInfo(name = "floor_price_cents") val floorPriceCents: Long? = null,
    @ColumnInfo(defaultValue = "") val brand: String = "",
    @ColumnInfo(defaultValue = "") val size: String = "",
    @ColumnInfo(defaultValue = "") val category: String = "",
    @ColumnInfo(name = "item_condition", defaultValue = "") val condition: String = "",
    @ColumnInfo(defaultValue = "") val color: String = "",
    @ColumnInfo(defaultValue = "") val description: String = "",
    @ColumnInfo(defaultValue = "") val keywords: String = "",
    @ColumnInfo(name = "first_published_at") val firstPublishedAt: Long? = null,
    @ColumnInfo(name = "last_published_at") val lastPublishedAt: Long? = null,
)

@Entity(
    tableName = "article_photos",
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["article_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("article_id")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "article_id") val articleId: Long,
    /** Nom du fichier dans le dossier privé « photos » de l'application. */
    @ColumnInfo(name = "file_name") val fileName: String,
    val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "sales",
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["article_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("article_id"), Index("sold_at")],
)
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "article_id") val articleId: Long?,
    val title: String,
    val category: String,
    @ColumnInfo(name = "sale_price_cents") val salePriceCents: Long,
    @ColumnInfo(name = "cost_cents") val costCents: Long,
    @ColumnInfo(name = "fees_cents") val feesCents: Long,
    @ColumnInfo(name = "sold_at") val soldAt: Long,
    @ColumnInfo(name = "listed_at") val listedAt: Long?,
    val buyer: String,
    @ColumnInfo(name = "shipping_status") val shippingStatus: String,
    @ColumnInfo(name = "shipped_at") val shippedAt: Long?,
    /** Nom du fichier du bordereau dans le dossier privé « labels ». */
    @ColumnInfo(name = "label_file") val labelFile: String?,
    val notes: String,
)

/** Journal des publications, republications et changements de prix. */
@Entity(
    tableName = "listing_events",
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["article_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("article_id"), Index("type", "at")],
)
data class ListingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "article_id") val articleId: Long,
    val type: String,
    val at: Long,
    @ColumnInfo(name = "price_cents") val priceCents: Long?,
) {
    companion object {
        const val PUBLISHED = "PUBLISHED"
        const val REPUBLISHED = "REPUBLISHED"
        const val PRICE_CHANGED = "PRICE_CHANGED"
    }
}

fun ArticleEntity.toDomain(photos: List<String> = emptyList()) = Article(
    id = id,
    title = title,
    costCents = costCents,
    priceCents = priceCents,
    status = ArticleStatus.fromLabel(status),
    createdAt = createdAt,
    floorPriceCents = floorPriceCents,
    brand = brand,
    size = size,
    category = category,
    condition = condition,
    color = color,
    description = description,
    keywords = keywords,
    firstPublishedAt = firstPublishedAt,
    lastPublishedAt = lastPublishedAt,
    photos = photos,
)

fun SaleEntity.toDomain() = Sale(
    id = id,
    articleId = articleId,
    title = title,
    category = category,
    salePriceCents = salePriceCents,
    costCents = costCents,
    feesCents = feesCents,
    soldAt = soldAt,
    listedAt = listedAt,
    buyer = buyer,
    shippingStatus = ShippingStatus.fromLabel(shippingStatus),
    labelPath = labelFile,
    notes = notes,
)
