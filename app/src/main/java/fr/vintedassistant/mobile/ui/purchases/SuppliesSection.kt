package fr.vintedassistant.mobile.ui.purchases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.Supplies
import fr.vintedassistant.mobile.domain.Supply
import fr.vintedassistant.mobile.domain.SupplyKind
import fr.vintedassistant.mobile.ui.Field
import fr.vintedassistant.mobile.ui.SectionTitle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Fournitures : ce qui sert à vendre sans être revendu. Dépense déduite du bénéfice, ligne du registre des achats,
 * et stock estimé pour ce qui part dans chaque colis.
 */
@Composable
fun SuppliesCard(
    supplies: List<Supply>,
    sales: List<Sale>,
    onAdd: () -> Unit,
    onEdit: (Supply) -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE) }
    var expanded by remember { mutableStateOf(false) }
    val stocks = Supplies.stocks(supplies, sales)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fournitures", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (supplies.isEmpty()) "Pochettes, étiquettes, matériel photo : ce qui sert à vendre sans être revendu."
                        else "${supplies.size} achat${if (supplies.size > 1) "s" else ""} • ${Money.formatEuros(Supplies.spentCents(supplies))}, déduits du bénéfice",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onAdd) { Text("Ajouter") }
            }
            stocks.forEach { s ->
                Text(
                    "${s.label} : environ ${s.remaining} en stock sur ${s.bought} (${s.used} colis depuis le " +
                        "${dateFormat.format(Date(s.since))}) • ${Money.formatEuros(s.unitCents)} pièce",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (stocks.any { it.low }) {
                Text("Pensez à en racheter avant d'être à court.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (supplies.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Masquer le détail" else "Voir le détail") }
            }
            if (expanded) {
                supplies.forEach { s ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${dateFormat.format(Date(s.boughtAt))} • ${s.kind.label}" +
                                    (if (s.supplier.isNotBlank()) " • ${s.supplier}" else "") +
                                    " • ${s.quantity} × ${Money.formatEuros(s.unitCents)} = ${Money.formatEuros(s.amountCents)}" +
                                    (if (s.perParcel) " • une par colis" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onEdit(s) }) { Text("Modifier") }
                    }
                }
            }
        }
    }
}

/** Saisie d'un achat de fournitures. [initial] null : nouvel achat. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SupplyDialog(
    initial: Supply?,
    now: Long,
    onDismiss: () -> Unit,
    onSave: (Supply) -> Unit,
    onDelete: (Supply) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: SupplyKind.PACKAGING) }
    var supplier by remember { mutableStateOf(initial?.supplier ?: "") }
    var qty by remember { mutableStateOf(initial?.quantity?.toString() ?: "1") }
    var amount by remember { mutableStateOf(initial?.let { Money.formatPlain(it.amountCents) } ?: "") }
    var perParcel by remember { mutableStateOf(initial?.perParcel ?: true) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var date by remember { mutableStateOf(initial?.boughtAt ?: now) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.LONG, Locale.FRANCE) }

    if (pickDate) {
        OrderDateDialog(date, onDismiss = { pickDate = false }) { date = it; pickDate = false }
    }
    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cet achat ?") },
            text = { Text("${initial.label}, ${Money.formatEuros(initial.amountCents)}. Il sortira du bénéfice et du registre des achats.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Achat de fournitures" else "Modifier l'achat") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Quoi (ex. : pochettes à bulles 13×15)", label, { label = it })
                SectionTitle("Type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SupplyKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Quantité", qty, { qty = it }, Modifier.weight(1f), number = true)
                    Field("Payé, port compris (€)", amount, { amount = it }, Modifier.weight(1.4f), decimal = true)
                }
                Field("Fournisseur (facultatif)", supplier, { supplier = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Acheté le ${dateFormat.format(Date(date))}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { pickDate = true }) { Text("Changer") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Une dans chaque colis", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "L'appli décompte une pièce par vente à partir de la date d'achat pour estimer ce qu'il reste.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(checked = perParcel, onCheckedChange = { perParcel = it })
                }
                Field("Note (facultatif)", note, { note = it }, singleLine = false)
                val q = qty.trim().toIntOrNull()
                val cents = Money.parseEuros(amount)
                if (q != null && q > 0 && cents != null && cents > 0) {
                    Text("Soit ${Money.formatEuros((cents + q / 2) / q)} la pièce.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (initial != null) {
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Supprimer cet achat") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val q = qty.trim().toIntOrNull()
                val cents = Money.parseEuros(amount)
                when {
                    label.isBlank() -> error = "Indiquez ce que vous avez acheté."
                    q == null || q < 1 -> error = "Quantité invalide (exemple : 30)."
                    cents == null || cents <= 0 -> error = "Montant invalide (exemple : 2,84)."
                    else -> onSave(
                        Supply(initial?.id ?: 0, date, label.trim(), kind, supplier.trim(), q, cents, perParcel, note.trim()),
                    )
                }
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
