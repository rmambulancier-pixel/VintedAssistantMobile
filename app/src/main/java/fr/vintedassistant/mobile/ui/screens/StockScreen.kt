@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.ui.PhotoThumb

private enum class StockFilter(val label: String, val accepts: (Article) -> Boolean) {
    IN_STOCK("En stock", { it.status.inStock }),
    DRAFT("Brouillons", { it.status == ArticleStatus.DRAFT }),
    ONLINE("En ligne", { it.status == ArticleStatus.ONLINE }),
    RESERVED("Réservés", { it.status == ArticleStatus.RESERVED }),
    SOLD("Vendus", { it.status == ArticleStatus.SOLD }),
    ALL("Tous", { true }),
}

@Composable
fun StockScreen(articles: List<Article>?, modifier: Modifier, onOpen: (Long) -> Unit, onOpenPurchases: () -> Unit = {}) {
    var filter by rememberSaveable { mutableStateOf(StockFilter.IN_STOCK) }
    var query by rememberSaveable { mutableStateOf("") }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            androidx.compose.material3.OutlinedButton(onClick = onOpenPurchases, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Achats fournisseur et coûts d'achat")
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Rechercher (nom, marque, catégorie)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(StockFilter.entries) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        if (articles == null) {
            item { CircularProgressIndicator() }
            return@LazyColumn
        }
        val q = query.trim().lowercase()
        val shown = articles.filter(filter.accepts).filter {
            q.isEmpty() || listOf(it.title, it.brand, it.category, it.size).any { s -> s.lowercase().contains(q) }
        }
        item {
            Text(
                "${shown.size} article(s) • coût ${Money.formatEuros(shown.sumOf { it.costCents })} • " +
                    "prix affichés ${Money.formatEuros(shown.sumOf { it.priceCents })}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (articles.isEmpty()) "Aucun article. Appuie sur + pour créer ta première fiche." else "Aucun article pour ce filtre.",
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(shown, key = { it.id }) { article -> ArticleRow(article) { onOpen(article.id) } }
        item { Text(" ", Modifier.padding(bottom = 72.dp)) }
    }
}

@Composable
fun ArticleRow(article: Article, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(article.photos.firstOrNull())
            Column(Modifier.weight(1f)) {
                Text(article.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                val details = listOf(article.brand, article.size, article.condition).filter { it.isNotBlank() }.joinToString(" • ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(
                    "${Money.formatEuros(article.priceCents)} • marge ${Money.formatEuros(article.marginCents)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (article.marginCents >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                val stats = listOfNotNull(
                    article.favoritesCount?.let { "$it favori(s)" },
                    article.viewsCount?.let { "$it vue(s)" },
                ).joinToString(" • ")
                Text(
                    article.status.label + if (stats.isNotEmpty()) " • $stats" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            trailing?.invoke()
        }
    }
}
