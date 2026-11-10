package fr.vintedassistant.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import fr.vintedassistant.mobile.domain.Inventory
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.StockOverview
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.ui.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Inventaire : un bijou par ligne avec sa photo et le nombre attendu en main. On ajuste au nombre réellement
 * compté ; à la validation, les écarts sont corrigés (pièces manquantes, brouillons retirés) et le reste signalé.
 */
@Composable
fun InventoryScreen(stock: StockOverview?, sales: List<Sale>, modifier: Modifier, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember(stock, sales) { stock?.let { Inventory.lines(it, sales) }.orEmpty() }
    val counted = remember { mutableStateMapOf<String, Int>() }
    var confirm by remember { mutableStateOf(false) }
    val diffs = lines.filter { (counted[it.key] ?: it.expected) != it.expected }

    if (confirm) {
        val report = diffs.map { l ->
            val c = counted[l.key] ?: l.expected
            if (c > l.expected) l to "${c - l.expected} en plus : importe la commande d'où elles viennent, ou crée une fiche."
            else {
                val s = Inventory.shortfall(l, c)
                l to buildList {
                    val m = s.missing.sumOf { it.second }
                    if (m > 0) add("$m pièce(s) sans fiche déclarée(s) manquante(s)")
                    if (s.withdrawDrafts.isNotEmpty()) add("${s.withdrawDrafts.size} brouillon(s) retiré(s)")
                    if (s.unresolved > 0) add("${s.unresolved} annonce(s) en ligne à vérifier : elle(s) n'a/ont plus de pièce")
                }.joinToString(", ") + "."
            }
        }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Corriger ${diffs.size} écart(s) ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    report.forEach { (l, txt) -> Text("${l.name.take(50)}${if (l.variant.isNotBlank()) " (${l.variant})" else ""} : $txt", style = MaterialTheme.typography.bodySmall) }
                    Text("Rien n'est supprimé : les brouillons passent « Retiré » et se rétablissent depuis la fiche.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        try {
                            val repo = context.container().purchaseRepository
                            var unresolved = 0
                            diffs.forEach { l ->
                                val c = counted[l.key] ?: l.expected
                                if (c < l.expected) {
                                    val s = Inventory.shortfall(l, c)
                                    repo.applyShortfall(s)
                                    unresolved += s.unresolved
                                }
                            }
                            onDone("Inventaire enregistré." + if (unresolved > 0) " $unresolved annonce(s) en ligne sans pièce : retire-les dans Vinted, puis passe la fiche à « Retiré »." else "")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onDone("Inventaire non enregistré : ${e.message ?: e.javaClass.simpleName}.")
                        }
                    }
                }) { Text("Corriger") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Compte ce que tu as vraiment en main, y compris les bijoux en vente et les colis pas encore partis. " +
                    "Le chiffre de départ est celui de l'application ; ajuste-le, puis valide.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            val total = lines.sumOf { counted[it.key] ?: it.expected }
            Text(
                "Attendu : ${lines.sumOf { it.expected }} pièce(s) • compté : $total • écarts : ${diffs.size}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        items(lines, key = { it.key }) { l ->
            val c = counted[l.key] ?: l.expected
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoThumb(l.image, size = 64.dp)
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        if (l.variant.isNotBlank()) Text("Achat : ${l.variant}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                        Text(
                            listOfNotNull(
                                l.freeLots.sumOf { it.second }.takeIf { it > 0 }?.let { "$it sans fiche" },
                                l.drafts.size.takeIf { it > 0 }?.let { "$it brouillon(s)" },
                                l.online.size.takeIf { it > 0 }?.let { "$it en vente" },
                                l.toShip.size.takeIf { it > 0 }?.let { "$it à expédier" },
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalIconButton(onClick = { counted[l.key] = (c - 1).coerceAtLeast(0) }) { Text("−") }
                    Text(
                        "$c", style = MaterialTheme.typography.titleLarge,
                        fontWeight = if (c != l.expected) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            c < l.expected -> MaterialTheme.colorScheme.error
                            c > l.expected -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    FilledTonalIconButton(onClick = { counted[l.key] = c + 1 }) { Text("+") }
                }
            }
        }
        item {
            Button(onClick = { confirm = true }, enabled = diffs.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(if (diffs.isEmpty()) "Tout correspond" else "Valider l'inventaire (${diffs.size} écart(s))")
            }
        }
    }
}
