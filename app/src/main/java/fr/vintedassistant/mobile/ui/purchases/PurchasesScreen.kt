package fr.vintedassistant.mobile.ui.purchases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.vintedassistant.mobile.data.importer.ParsedOrder
import fr.vintedassistant.mobile.data.importer.ParsedOrderLine
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.PurchaseItem
import fr.vintedassistant.mobile.domain.PurchaseOrder
import fr.vintedassistant.mobile.ui.Field
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.ui.SectionTitle
import fr.vintedassistant.mobile.ui.StatRow
import fr.vintedassistant.mobile.ui.container
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PurchasesScreen(
    articles: List<Article>?,
    modifier: Modifier,
    onOpenSupplier: () -> Unit,
    onOpenArticle: (Long) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val repo = context.container().purchaseRepository
    val orders by repo.orders.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var linkFor by remember { mutableStateOf<PurchaseItem?>(null) }
    var missingFor by remember { mutableStateOf<PurchaseItem?>(null) }
    var manual by remember { mutableStateOf(false) }
    var deleteOrder by remember { mutableStateOf<PurchaseOrder?>(null) }
    var dateFor by remember { mutableStateOf<PurchaseOrder?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE) }
    var info by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_VARIABLE") val external = onMessage
    val onMessage: (String) -> Unit = { info = it }

    fun run(error: String, block: suspend () -> Unit) {
        scope.launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { onMessage(error) } }
    }

    missingFor?.let { item ->
        var count by remember(item.id) { mutableStateOf(if (item.missing > 0) item.missing else item.quantity - item.linkedCount) }
        AlertDialog(
            onDismissRequest = { missingFor = null },
            title = { Text("Pièces jamais reçues") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text(
                        "Commandées : ${item.quantity}. Indique combien ne sont jamais arrivées (rupture, colis incomplet) : " +
                            "elles sortent du stock et les frais de port sont répartis sur les pièces reçues.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { count = (count - 1).coerceAtLeast(0) }) { Text("−") }
                        Text("$count", style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(onClick = { count = (count + 1).coerceAtMost(item.quantity - item.linkedCount) }) { Text("+") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = item
                    missingFor = null
                    run("Mise à jour impossible.") {
                        repo.setMissing(target.id, count)
                        onMessage(if (count == 0) "Toutes les pièces sont comptées comme reçues." else "$count pièce(s) jamais reçue(s) : retirée(s) du stock.")
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { missingFor = null }) { Text("Annuler") } },
        )
    }

    val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val c = context.container()
            val links = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { c.dataExport.readLinks(it) }
                } ?: throw java.io.IOException("fichier inaccessible")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Lecture du fichier impossible (${e.javaClass.simpleName} : ${e.message ?: "sans détail"}). Choisis un export « vinted-assistant-….zip ».")
                return@launch
            }
            try {
                val n = repo.restoreLinks(links)
                onMessage("$n rattachement(s) rétabli(s) sur ${links.size} trouvé(s) dans l'export.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Rétablissement interrompu (${e.javaClass.simpleName} : ${e.message ?: "sans détail"}).")
            }
        }
    }


    var pendingSteps by remember { mutableStateOf<List<fr.vintedassistant.mobile.data.Corrections.Step>?>(null) }
    val pickCorrections = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw java.io.IOException("fichier inaccessible")
                pendingSteps = context.container().corrections.plan(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Fichier de corrections illisible (${e.javaClass.simpleName} : ${e.message ?: "sans détail"}).")
            }
        }
    }
    pendingSteps?.let { steps ->
        val valid = steps.count { it.ok }
        AlertDialog(
            onDismissRequest = { pendingSteps = null },
            title = { Text("Corrections à appliquer") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEach { s ->
                        Text(
                            (if (s.ok) "✓ " else "✗ ") + s.label + (s.problem?.let { " : $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text("Les lignes ✗ sont ignorées. La suppression d'une fiche est définitive.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(enabled = valid > 0, onClick = {
                    pendingSteps = null
                    run("Corrections interrompues.") {
                        val n = context.container().corrections.apply(steps)
                        onMessage("$n correction(s) appliquée(s).")
                    }
                }) { Text("Appliquer ($valid)") }
            },
            dismissButton = { TextButton(onClick = { pendingSteps = null }) { Text("Annuler") } },
        )
    }

    linkFor?.let { item ->
        LinkDialog(
            item = item,
            articles = articles.orEmpty(),
            onDismiss = { linkFor = null },
            onPick = { a ->
                linkFor = null
                run("Rattachement impossible.") {
                    repo.link(a.id, item.id)
                    onMessage("Coût d'achat de « ${a.title} » : ${euros(item.landedUnitCents)}.")
                }
            },
        )
    }
    dateFor?.let { o ->
        OrderDateDialog(
            initial = o.orderedAt,
            onDismiss = { dateFor = null },
            onPick = { at ->
                dateFor = null
                run("Date non enregistrée.") { repo.setOrderedAt(o.id, at); onMessage("Date de la commande ${o.orderRef} : ${dateFormat.format(Date(at))}.") }
            },
        )
    }
    deleteOrder?.let { o ->
        AlertDialog(
            onDismissRequest = { deleteOrder = null },
            title = { Text("Supprimer cette commande ?") },
            text = { Text("Les articles rattachés gardent leur coût d'achat.") },
            confirmButton = { TextButton(onClick = { deleteOrder = null; run("Suppression impossible.") { repo.deleteOrder(o.id) } }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleteOrder = null }) { Text("Annuler") } },
        )
    }
    if (manual) {
        ManualOrderDialog(
            onDismiss = { manual = false },
            onSave = { order ->
                manual = false
                run("Enregistrement impossible.") {
                    repo.saveOrder("Saisie manuelle", order, downloadImages = false, received = true)
                    onMessage("Achat enregistré.")
                }
            },
        )
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        info?.let { msg -> item { fr.vintedassistant.mobile.ui.MessageCard(msg) { info = null } } }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSupplier, modifier = Modifier.weight(1f)) { Text("Importer de Nihaojewelry") }
                OutlinedButton(onClick = { manual = true }, modifier = Modifier.weight(1f)) { Text("Achat à la main") }
            }
        }
        val list = orders
        if (list == null) return@LazyColumn
        val allItems = list.flatMap { it.items }
        item {
            StatRow(
                "Dépensé" to euros(list.sumOf { it.totalCents }),
                "Pièces" to allItems.sumOf { it.quantity }.toString(),
                "En livraison" to list.filter { !it.received }.sumOf { o -> o.items.sumOf { it.quantity } }.toString(),
            )
        }
        item {
            OutlinedButton(onClick = { pickBackup.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Rétablir les rattachements d'un export")
            }
        }
        item {
            OutlinedButton(onClick = { pickCorrections.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Appliquer un fichier de corrections")
            }
        }
        item {
            Text(
                "Coût de revient = prix payé + part du port et des frais, répartie selon la valeur de chaque produit.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (list.isEmpty()) {
            item { Text("Aucun achat. Importe une commande Nihaojewelry ou saisis un achat.", Modifier.padding(vertical = 16.dp)) }
        }
        items(list, key = { it.id }) { order ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${order.supplier} • ${dateFormat.format(Date(order.orderedAt))}", style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { dateFor = order }) { Text("Modifier") }
                            }
                            Text(
                                "Réf. ${order.orderRef} • total ${euros(order.totalCents)} (port ${euros(order.shippingCents)}" +
                                    (if (order.feesCents > 0) ", frais ${euros(order.feesCents)}" else "") +
                                    (if (order.feesCents < 0) ", remise ${euros(-order.feesCents)}" else "") + ")",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { deleteOrder = order }) { Text("Supprimer") }
                    }
                    if (order.received) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reçue", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { run("Mise à jour impossible.") { repo.setReceived(order.id, false) } }) {
                                Text("Pas encore reçue ?")
                            }
                        }
                    } else {
                        Text(
                            "En cours de livraison" + (if (order.deliveryEta.isNotBlank()) ", prévue ${order.deliveryEta}" else "") +
                                ". Les pièces ne pourront être mises en vente qu'à réception.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Button(onClick = { run("Mise à jour impossible.") { repo.setReceived(order.id, true); onMessage("Commande reçue.") } }) {
                            Text("J'ai reçu cette commande")
                        }
                    }
                    order.items.forEach { item ->
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PhotoThumb(item.imageFile, size = 56.dp)
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(
                                    "×${item.quantity} • payé ${euros(item.unitPriceCents)} • revient ${euros(item.landedUnitCents)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Rattachés : ${item.linkedCount} / ${item.delivered}" +
                                        if (item.missing > 0) " • ${item.missing} jamais reçue(s)" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.remaining > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                )
                                if (order.received) {
                                    TextButton(onClick = { missingFor = item }) {
                                        Text(if (item.missing > 0) "Corriger les pièces non reçues" else "Pas reçu ?")
                                    }
                                }
                                if (item.remaining > 0 && order.received) {
                                    Row {
                                        TextButton(onClick = { linkFor = item }) { Text("Rattacher") }
                                        TextButton(onClick = {
                                            run("Création impossible.") { onOpenArticle(repo.createDraft(item.id)) }
                                        }) { Text("Créer la fiche") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun LinkDialog(item: PurchaseItem, articles: List<Article>, onDismiss: () -> Unit, onPick: (Article) -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    // D'abord les fiches sans coût, puis celles déjà chiffrées.
    val choices = articles
        .filter { q.isEmpty() || it.title.lowercase().contains(q) }
        .sortedWith(compareBy<Article> { it.costCents != 0L }.thenBy { it.title })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rattacher à quelle fiche ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoThumb(item.imageFile, size = 48.dp)
                    Text("${item.name}\nRevient ${euros(item.landedUnitCents)}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Rechercher") }, singleLine = true)
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PhotoThumb(a.photos.firstOrNull(), size = 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(a.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(
                                    "${a.status.label} • coût ${if (a.costCents == 0L) "non saisi" else euros(a.costCents)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

private data class LineDraft(val name: String = "", val price: String = "", val qty: String = "1")

@Composable
private fun ManualOrderDialog(onDismiss: () -> Unit, onSave: (ParsedOrder) -> Unit) {
    var shipping by remember { mutableStateOf("") }
    var fees by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf(LineDraft()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Achat saisi à la main") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEachIndexed { i, l ->
                    SectionTitle("Produit ${i + 1}")
                    Field("Nom", l.name, { v -> lines[i] = l.copy(name = v) })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("Prix unitaire (€)", l.price, { v -> lines[i] = l.copy(price = v) }, Modifier.weight(1f), decimal = true)
                        Field("Quantité", l.qty, { v -> lines[i] = l.copy(qty = v) }, Modifier.weight(1f), number = true)
                    }
                }
                TextButton(onClick = { lines.add(LineDraft()) }) { Text("Ajouter un produit") }
                Field("Frais de port (€)", shipping, { shipping = it }, decimal = true)
                Field("Autres frais (€)", fees, { fees = it }, decimal = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = lines.filter { it.name.isNotBlank() || it.price.isNotBlank() }.map { l ->
                    val price = Money.parseEuros(l.price)
                    val qty = l.qty.trim().toIntOrNull()
                    if (l.name.isBlank() || price == null || price <= 0 || qty == null || qty < 1) null
                    else ParsedOrderLine(l.name.trim(), price, qty, "", "")
                }
                val ship = if (shipping.isBlank()) 0L else Money.parseEuros(shipping)
                val other = if (fees.isBlank()) 0L else Money.parseEuros(fees)
                when {
                    parsed.isEmpty() || parsed.any { it == null } -> error = "Chaque produit a besoin d'un nom, d'un prix et d'une quantité."
                    ship == null || other == null -> error = "Frais invalides (exemple : 4,90)."
                    else -> onSave(
                        ParsedOrder(
                            orderRef = "MANUEL-${System.currentTimeMillis()}",
                            orderedAt = null,
                            lines = parsed.filterNotNull(),
                            shippingCents = ship,
                            feesCents = other,
                            totalCents = null,
                            url = "",
                        )
                    )
                }
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Calendrier pour corriger la date d'une commande (midi, heure du téléphone). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OrderDateDialog(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val zone = java.time.ZoneId.systemDefault()
    val utcMidnight = java.time.Instant.ofEpochMilli(initial).atZone(zone).toLocalDate()
        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = utcMidnight)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis ?: return@TextButton
                val day = java.time.Instant.ofEpochMilli(picked).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                onPick(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}
