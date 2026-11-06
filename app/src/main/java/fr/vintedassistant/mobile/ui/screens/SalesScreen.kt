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
) {
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
        val toShip = sales.filter { it.shippingStatus == ShippingStatus.TO_SHIP }
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
                date = dateFormat.format(Date(sale.soldAt)),
                onStatus = { onStatus(sale.id, it) },
                onAddLabel = {
                    labelForSale = sale.id
                    pickLabel.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                },
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
    onStatus: (ShippingStatus) -> Unit,
    onAddLabel: () -> Unit,
    onOpenLabel: () -> Unit,
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
