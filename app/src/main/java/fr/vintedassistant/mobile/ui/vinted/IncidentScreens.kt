package fr.vintedassistant.mobile.ui.vinted

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.vintedassistant.mobile.ui.container
import fr.vintedassistant.mobile.domain.IncidentLog
import fr.vintedassistant.mobile.ui.screens.appVersion
import kotlinx.coroutines.launch

private val GREEN = Color(0xFF34C38F)
private val AMBER = Color(0xFFF5B54A)
private val ROSE = Color(0xFFF2647A)

/**
 * Signalement d'un souci : un motif, un mot si besoin. L'état de la page (champs et boutons, sans leur contenu)
 * et l'étape de la série sont joints automatiquement par l'appelant.
 */
@Composable
fun ReportDialog(onDismiss: () -> Unit, onSend: (reason: String, note: String) -> Unit) {
    var reason by remember { mutableStateOf(IncidentLog.REASONS.first()) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signaler un souci") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                IncidentLog.REASONS.forEach { r ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { reason = r }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    note, { note = it.take(500) },
                    label = { Text("Ce qui s'est passé (facultatif)") },
                    placeholder = { Text("Ex. : État remis sur « Très bon état »") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), minLines = 2,
                )
                Text(
                    "L'état de la page est joint automatiquement : noms des champs et des boutons, sans pseudo ni message.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSend(reason, note.trim()) }) { Text("Noter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Journal de Vinted intégré : verdict de sortie de bêta, réussite par champ, incidents, envoi en un fichier. */
@Composable
fun IncidentJournalDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = context.container().incidents
    val entries by store.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val r = remember(entries) { IncidentLog.readiness(entries, now) }
    val incidents = remember(entries) { entries.filter { !it.ok }.sortedByDescending { it.at } }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Vider le journal ?") },
            text = { Text("Les ${entries.size} événements notés seront effacés, et la série sans faute repartira de zéro. Envoie-le d'abord si tu ne l'as pas fait.") },
            confirmButton = { TextButton(onClick = { scope.launch { store.clear() }; confirmClear = false }) { Text("Vider") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Journal de Vinted intégré", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Fermer") }
                    }
                }
                item { ReadinessCard(r) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = entries.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    try {
                                        val file = store.export(appVersion(context))
                                        context.startActivity(context.container().dataExport.shareIntent(file))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Envoi impossible", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Envoyer le journal") }
                        OutlinedButton(enabled = entries.isNotEmpty(), onClick = { confirmClear = true }) { Text("Vider") }
                    }
                }
                if (r.byField.isNotEmpty()) {
                    item { FieldCard(r.byField) }
                }
                item {
                    Text(
                        if (incidents.isEmpty()) "Aucun incident noté." else "Incidents (${incidents.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(incidents.take(100), key = { "${it.at}-${it.kind}-${it.message.hashCode()}" }) { e -> IncidentRow(e) }
                item { Box(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ReadinessCard(r: IncidentLog.Readiness) {
    val color = when {
        r.ready -> GREEN
        r.fills == 0 -> MaterialTheme.colorScheme.outline
        else -> AMBER
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (r.ready) "Prêt pour la sortie de bêta" else "Bêta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(r.verdict(), style = MaterialTheme.typography.bodyMedium)
            Text("Série sans faute : ${r.streak.coerceAtMost(IncidentLog.STREAK_GOAL)}/${IncidentLog.STREAK_GOAL}", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(
                progress = { (r.streak.toFloat() / IncidentLog.STREAK_GOAL).coerceIn(0f, 1f) },
                color = color, trackColor = color.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Row(Modifier.fillMaxWidth()) {
                Stat("${r.fills}", "remplissages", Modifier.weight(1f))
                Stat(r.cleanRate?.let { "$it %" } ?: "—", "sans faute", Modifier.weight(1f))
                Stat("${r.incidents7d}", "incidents (7 j)", Modifier.weight(1f), if (r.incidents7d > 0) ROSE else null)
            }
            r.lastIncidentAt?.let {
                Text("Dernier incident : ${IncidentLog.stamp(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, color: Color? = null) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Réussite de chaque champ, du plus fragile au plus sûr. */
@Composable
private fun FieldCard(byField: Map<String, Pair<Int, Int>>) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Réussite par champ", style = MaterialTheme.typography.titleMedium)
            byField.entries.sortedBy { (_, v) -> v.first.toDouble() / v.second }.forEach { (name, v) ->
                val rate = v.first.toFloat() / v.second
                val c = when { rate >= 0.95f -> GREEN; rate >= 0.8f -> AMBER; else -> ROSE }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(rate.coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
                    }
                    Text("${v.first}/${v.second}", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c)
                }
            }
        }
    }
}

@Composable
private fun IncidentRow(e: IncidentLog.Entry) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = (if (e.kind == IncidentLog.Kind.REPORT) AMBER else ROSE).copy(alpha = 0.18f)) {
                Text(e.kind.label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text("${IncidentLog.stamp(e.at)} • ${e.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (e.message.isNotBlank()) Text(e.message, style = MaterialTheme.typography.bodySmall)
        if (e.note.isNotBlank()) Text("« ${e.note} »", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}
