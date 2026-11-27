package fr.vintedassistant.mobile.ui.purchases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.data.local.WishEntity
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.ui.container
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val GREEN = Color(0xFF34C38F)

/**
 * « À acheter plus tard » : les pépites gardées depuis Nihaojewelry, avec leur photo, leur lien d'achat et
 * l'estimation de revente du jour où elles ont été gardées. Rien n'est acheté d'ici : « Ouvrir » affiche la
 * page du bijou, l'achat se fait sur le site.
 */
@Composable
fun WishlistScreen(modifier: Modifier, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val repo = context.container().wishes
    val items by repo.items.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val list = items ?: run { Text("Chargement…", modifier.padding(16.dp)); return }
    val todo = list.filter { it.boughtAt == null }
    val done = list.filter { it.boughtAt != null }
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE)

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = GREEN.copy(alpha = 0.14f)), shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${todo.size} bijou(x) à acheter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (todo.isNotEmpty()) {
                        Text(
                            "Une pièce de chaque : ${Money.formatEuros(todo.sumOf { it.costCents })} port compris, " +
                                "revente estimée ${Money.formatEuros(todo.sumOf { it.saleCents ?: 0 })}.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        if (list.isEmpty()) "Dans Nihaojewelry, ouvre « Pépites » et touche « Garder pour plus tard » sur un bijou."
                        else "Estimations du jour où le bijou a été gardé. « Ouvrir » affiche sa page sur Nihaojewelry : l'achat se fait sur le site.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        items(todo, key = { it.id }) { w -> WishRow(w, dateFormat, onOpen, onBought = { scope.launch { repo.setBought(w.id, true) } }, onRemove = { scope.launch { repo.remove(w.id) } }) }
        if (done.isNotEmpty()) {
            item { Text("Déjà achetés (${done.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(done, key = { it.id }) { w -> WishRow(w, dateFormat, onOpen, onBought = { scope.launch { repo.setBought(w.id, false) } }, onRemove = { scope.launch { repo.remove(w.id) } }) }
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun WishRow(w: WishEntity, dateFormat: DateFormat, onOpen: (String) -> Unit, onBought: () -> Unit, onRemove: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PhotoThumb(w.imageFile, size = 88.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(w.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    w.multiple?.let {
                        Text("×${String.format(Locale.FRANCE, "%.0f", it)} la mise", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = GREEN)
                    }
                    Text(
                        "Revient à ${Money.formatEuros(w.costCents)}" + (w.saleCents?.let { " • se vend vers ${Money.formatEuros(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        (if (w.basis.isNotBlank()) "${w.basis} • " else "") + "gardé le ${dateFormat.format(Date(w.savedAt))}" +
                            (w.boughtAt?.let { " • acheté le ${dateFormat.format(Date(it))}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                TextButton(onClick = { onOpen(w.url) }) { Text("Ouvrir") }
                TextButton(onClick = onBought) { Text(if (w.boughtAt == null) "Acheté" else "Pas encore acheté") }
                TextButton(onClick = onRemove) { Text("Retirer") }
            }
        }
    }
}
