package fr.vintedassistant.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.ExpressListing
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.StockLedger
import fr.vintedassistant.mobile.domain.StockOverview

/** Origine d'une fiche : photo et nom du produit acheté (pour reconnaître la pièce, pas pour la publier). */
data class PieceOrigin(val supplierImage: String?, val supplierName: String, val variant: String)

fun pieceOrigins(stock: StockOverview?): Map<Long, PieceOrigin> {
    if (stock == null) return emptyMap()
    val out = HashMap<Long, PieceOrigin>()
    stock.products.forEach { p ->
        val origin = PieceOrigin(p.imageFile, p.name, ExpressListing.variant(p.name))
        p.articles.forEach { out[it.id] = origin }
    }
    return out
}

/**
 * Choix d'une fiche avec sa photo : celle de la fiche, sinon celle du fournisseur (marquée « photo fournisseur »).
 * Groupes : prêtes à publier, à compléter (sans photo ou sans prix), déjà en ligne. Recherche par mot.
 */
@Composable
fun ArticlePickerDialog(
    articles: List<Article>,
    origins: Map<Long, PieceOrigin>,
    onDismiss: () -> Unit,
    onPick: (Article) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val words = StockLedger.normalize(query).split(' ').filter { it.isNotBlank() }
    fun matches(a: Article): Boolean {
        val text = StockLedger.normalize(a.title + " " + (origins[a.id]?.variant ?: "") + " " + a.color)
        return words.all { text.contains(it) }
    }
    val shown = articles.filter { it.status.inStock && matches(it) }
    val ready = shown.filter { it.status == ArticleStatus.DRAFT && it.photos.isNotEmpty() && it.priceCents > 0 }
    val incomplete = shown.filter { it.status == ArticleStatus.DRAFT && it !in ready }
    val online = shown.filter { it.status != ArticleStatus.DRAFT }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quelle fiche ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Chercher (ange, doré, croix…)") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    fun group(title: String, list: List<Article>) {
                        if (list.isEmpty()) return
                        item(key = "h-$title") {
                            Text("$title (${list.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                        items(list, key = { it.id }) { a -> PickerRow(a, origins[a.id]) { onPick(a) } }
                    }
                    group("Prêtes à publier", ready)
                    group("À compléter", incomplete)
                    group("En ligne", online)
                    if (shown.isEmpty()) item { Text("Aucune fiche.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun PickerRow(a: Article, origin: PieceOrigin?, onClick: () -> Unit) {
    val own = a.photos.firstOrNull()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PhotoThumb(own ?: origin?.supplierImage, size = 64.dp)
            if (own == null && origin?.supplierImage != null) {
                Text("fournisseur", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            origin?.variant?.takeIf { it.isNotBlank() }?.let {
                Text("Achat : $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                listOfNotNull(
                    a.status.label,
                    if (a.priceCents > 0) Money.formatEuros(a.priceCents) else "sans prix",
                    if (a.photos.isEmpty()) "sans photo" else "${a.photos.size} photo(s)",
                    "fiche n° ${a.id}",
                ).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = if (a.photos.isEmpty() || a.priceCents <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}
