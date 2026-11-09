@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import fr.vintedassistant.mobile.domain.Finance
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.ShippingStatus
import fr.vintedassistant.mobile.domain.ShippingDeadline
import fr.vintedassistant.mobile.domain.PurchaseOrder
import fr.vintedassistant.mobile.domain.StockLedger
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import java.time.format.DateTimeFormatter
import fr.vintedassistant.mobile.ui.container
import fr.vintedassistant.mobile.ui.openFile
import fr.vintedassistant.mobile.ui.shareFiles
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SalesScreen(
    sales: List<Sale>?,
    contributionRateBp: Int,
    modifier: Modifier,
    onStatus: (Long, ShippingStatus) -> Unit,
    onAttachLabel: (Long, Uri) -> Unit,
    orders: List<PurchaseOrder> = emptyList(),
    onAttachPurchase: (Long, Long) -> Unit = { _, _ -> },
) {
    var attachFor by remember { mutableStateOf<Sale?>(null) }
    attachFor?.let { sale ->
        AttachPurchaseDialog(sale, orders, onDismiss = { attachFor = null }) { itemId ->
            attachFor = null
            onAttachPurchase(sale.id, itemId)
        }
    }
    val context = LocalContext.current
    var onlyToShip by rememberSaveable { mutableStateOf(false) }
    var labelForSale by rememberSaveable { mutableStateOf<Long?>(null) }
    val pickLabel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val saleId = labelForSale
        if (uri != null && saleId != null) onAttachLabel(saleId, uri)
        labelForSale = null
    }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE) }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sales == null) {
            item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            return@LazyColumn
        }
        val now = System.currentTimeMillis()
        val toShip = sales.filter { it.shippingStatus == ShippingStatus.TO_SHIP }
            .sortedBy { ShippingDeadline.due(it, now)?.lastDay?.toEpochDay() ?: Long.MAX_VALUE }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                item { FilterChip(selected = !onlyToShip, onClick = { onlyToShip = false }, label = { Text("Toutes (${sales.size})") }) }
                item { FilterChip(selected = onlyToShip, onClick = { onlyToShip = true }, label = { Text("À expédier (${toShip.size})") }) }
            }
        }
        val labels = toShip.mapNotNull { it.labelPath }
        if (labels.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = {
                        val store = context.container().labelStore
                        val pdf = labels.all { store.mimeType(it) == "application/pdf" }
                        shareFiles(
                            context,
                            labels.map { store.shareUri(it) },
                            if (pdf) "application/pdf" else "*/*",
                            "Imprimer ou envoyer les bordereaux…",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Partager les ${labels.size} bordereau(x) à expédier") }
            }
        }
        val shown = if (onlyToShip) toShip else sales
        if (shown.isEmpty()) {
            item {
                Text(
                    if (sales.isEmpty()) "Aucune vente. Ouvre une fiche article et appuie sur « Marquer comme vendu »."
                    else "Rien à expédier.",
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(shown, key = { it.id }) { sale ->
            SaleCard(
                sale = sale,
                netCents = Finance.netCents(sale, contributionRateBp),
                date = if (sale.dateKnown) dateFormat.format(Date(sale.soldAt)) else "Date inconnue (import Vinted)",
                due = ShippingDeadline.due(sale, now),
                onStatus = { onStatus(sale.id, it) },
                onAddLabel = {
                    labelForSale = sale.id
                    pickLabel.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                },
                onAttachPurchase = if (sale.articleId == null && orders.isNotEmpty()) ({ attachFor = sale }) else null,
                onOpenLabel = {
                    val store = context.container().labelStore
                    sale.labelPath?.let { openFile(context, store.shareUri(it), store.mimeType(it)) }
                },
            )
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun SaleCard(
    sale: Sale,
    netCents: Long,
    date: String,
    due: ShippingDeadline.Due?,
    onStatus: (ShippingStatus) -> Unit,
    onAddLabel: () -> Unit,
    onOpenLabel: () -> Unit,
    onAttachPurchase: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sale.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "$date • ${Money.formatEuros(sale.salePriceCents)}" + if (sale.buyer.isNotBlank()) " • ${sale.buyer}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (sale.counted) "Bénéfice net : ${Money.formatEuros(netCents)}" else "Vente annulée : non comptée",
                color = if (!sale.counted) MaterialTheme.colorScheme.outline
                else if (netCents >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            due?.let { d ->
                val day = d.lastDay.format(DUE_FORMAT)
                Text(
                    when {
                        d.late -> "Délai Vinted dépassé (dernier jour : $day). Dépose vite le colis ou préviens l'acheteur."
                        d.workingDaysLeft == 1 -> "À déposer au plus tard le $day, dernier jour."
                        else -> "À déposer au plus tard le $day (${d.workingDaysLeft} jours ouvrés)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onAttachPurchase != null) {
                Text(
                    "Vente sans fiche : coût d'achat inconnu. Indique la pièce sortie du stock (lot, pièce envoyée à la place d'une autre…).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onAttachPurchase, modifier = Modifier.fillMaxWidth()) { Text("Rattacher à un achat") }
            }
            if (sale.notes.isNotBlank()) Text(sale.notes, style = MaterialTheme.typography.bodySmall)
            if (sale.shippingStatus == ShippingStatus.TO_SHIP) {
                Button(onClick = { onStatus(ShippingStatus.SHIPPED) }, modifier = Modifier.fillMaxWidth()) { Text("Colis déposé : expédiée") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(sale.shippingStatus.label) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ShippingStatus.entries.forEach { status ->
                            DropdownMenuItem(text = { Text(status.label) }, onClick = { menu = false; onStatus(status) })
                        }
                    }
                }
                if (sale.labelPath != null) {
                    TextButton(onClick = onOpenLabel) { Text("Voir le bordereau") }
                    TextButton(onClick = onAddLabel) { Text("Remplacer") }
                } else {
                    TextButton(onClick = onAddLabel) { Text("Ajouter le bordereau") }
                }
            }
        }
    }
}

private val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)

@Composable
private fun AttachPurchaseDialog(sale: Sale, orders: List<PurchaseOrder>, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.SHORT, Locale.FRANCE) }
    val lots = remember(orders) {
        orders.filter { it.received }.flatMap { o -> o.items.filter { it.remaining > 0 }.map { o to it } }
    }
    val words = StockLedger.normalize(filter).split(' ').filter { it.isNotBlank() }
    val shown = lots
        .filter { (_, i) -> val n = StockLedger.normalize(i.name); words.all { n.contains(it) } }
        .sortedByDescending { (_, i) -> StockLedger.similarity(i.name, sale.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quelle pièce est partie ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Vente « ${sale.title.take(60)} » ${Money.formatEuros(sale.salePriceCents)}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Chercher (ex. ange)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (shown.isEmpty()) item { Text("Aucune pièce disponible.", style = MaterialTheme.typography.bodySmall) }
                    items(shown, key = { it.second.id }) { (o, i) ->
                        TextButton(onClick = { onPick(i.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${i.name.take(90)}\nCommande du ${dateFormat.format(Date(o.orderedAt))} • reste ${i.remaining} • coût ${Money.formatEuros(i.landedUnitCents)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
