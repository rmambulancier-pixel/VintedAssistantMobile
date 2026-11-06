package fr.vintedassistant.mobile.data

import fr.vintedassistant.mobile.data.legacy.LegacyInventoryImporter
import fr.vintedassistant.mobile.data.local.ArticleDao
import fr.vintedassistant.mobile.data.local.ArticleEntity
import fr.vintedassistant.mobile.data.local.toDomain
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.NewArticle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Point d'accès unique au stock pour les écrans. */
class ArticleRepository(
    private val dao: ArticleDao,
    private val importer: LegacyInventoryImporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val articles: Flow<List<Article>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun importLegacyIfNeeded(): LegacyInventoryImporter.Outcome = importer.importIfNeeded()

    suspend fun add(article: NewArticle): Long {
        val now = clock()
        return dao.insert(
            ArticleEntity(
                title = article.title,
                costCents = article.costCents,
                priceCents = article.priceCents,
                status = Article.STATUS_DRAFT,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun delete(id: Long): Int = dao.deleteById(id)
}
