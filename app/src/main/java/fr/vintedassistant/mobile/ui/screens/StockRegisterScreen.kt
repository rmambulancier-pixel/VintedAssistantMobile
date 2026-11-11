@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package fr.vintedassistant.mobile.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.StockLedger
import fr.vintedassistant.mobile.domain.StockOverview
import fr.vintedassistant.mobile.domain.StockRegister
import fr.vintedassistant.mobile.domain.StockRegister.Group
import fr.vintedassistant.mobile.domain.StockRegister.IssueKind
import fr.vintedassistant.mobile.domain.StockRegister.State
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.ui.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
private fun groupColor(g: Group): Color = when (g) {
    Group.IN_HAND -> MaterialTheme.colorScheme.primary
    Group.RESTOCK -> MaterialTheme.colorScheme.tertiary
    Group.GONE -> MaterialTheme.colorScheme.secondary
    Group.LOST -> MaterialTheme.colorScheme.error
    Group.OUT -> MaterialTheme.colorScheme.outline
}

/** Le bilan écrit en clair, avec ses chiffres : achetées = chez moi + réassort + parties + manquantes + retirées. */
@Composable
private fun BalanceLine(bought: Int, byGroup: Map<Group, Int>, accounted: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Achetées : $bought", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Group.entries.forEach { g ->
                val n = byGroup[g] ?: 0
                if (n > 0 || g == Group.IN_HAND) {
                    Text("${g.label} $n", style = MaterialTheme.typography.bodyMedium, color = groupColor(g), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (accounted == bought) {
            Text("Bilan juste : chaque pièce achetée est comptée une fois.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else {
            Text(
                "Écart : ${accounted - bought} fiche(s) de plus que de pièces achetées. Voir les points à corriger.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Registre du stock : un bijou par ligne, avec ses pièces chez moi, en réassort, vendues ; le bilan global ;
 * les points à corriger ; la recherche par nom ou par code produit, et le scan de l'étiquette du sachet.
 */
@Composable
fun StockRegisterScreen(stock: StockOverview?, sales: List<Sale>, modifier: Modifier, onOpenSheet: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheets = remember(stock, sales) { stock?.let { StockRegister.build(it, sales) }.orEmpty() }
    val totals = remember(sheets) { StockRegister.totals(sheets) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    var scanInfo by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var pendingShot by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingShot
        pendingShot = null
        if (!ok || path == null) return@rememberLauncherForActivityResult
        scanning = true
        scope.launch {
            try {
                val codes = context.container().labelScanner.codes(File(path))
                val hit = sheets.firstOrNull { s -> s.skus.any { it in codes } }
                when {
                    hit != null -> onOpenSheet(hit.product.key)
                    codes.isEmpty() -> scanInfo = "Aucun code « NH… » lu. Cadre l'étiquette de plus près, bien éclairée, et réessaie."
                    else -> scanInfo = "Code ${codes.joinToString()} inconnu : ce bijou vient d'une commande pas encore importée."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanInfo = "Lecture impossible : ${e.message ?: e.javaClass.simpleName}. La première fois, Google Play services télécharge le module de lecture : réessaie dans une minute."
            } finally {
                scanning = false
                File(path).delete()
            }
        }
    }
    scanInfo?.let {
        AlertDialog(onDismissRequest = { scanInfo = null }, title = { Text("Étiquette") }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { scanInfo = null }) { Text("OK") } })
    }

    val words = StockLedger.normalize(query).split(' ').filter { it.isNotBlank() }
    val shown = sheets.filter { s ->
        val text = StockLedger.normalize(s.title + " " + s.product.name + " " + s.skus.joinToString(" "))
        words.all { text.contains(it) } && when (filter) {
            1 -> s.count(Group.IN_HAND) > 0
            2 -> s.count(Group.RESTOCK) > 0
            3 -> s.issues.isNotEmpty() || !s.balanced
            else -> true
        }
    }.sortedWith(compareByDescending<StockRegister.Sheet> { it.issues.isNotEmpty() || !it.balanced }.thenByDescending { it.count(Group.IN_HAND) }.thenBy { it.title })

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) { BalanceLine(totals.bought, totals.byGroup, totals.accounted) }
            }
        }
        if (totals.issues > 0) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().clickable { filter = 3 }) {
                    Text(
                        "${totals.issues} point(s) à corriger sur ${sheets.count { it.issues.isNotEmpty() || !it.balanced }} bijou(x). Touche pour les voir.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Nom ou code NH…") }, modifier = Modifier.weight(1f),
                )
                Button(enabled = !scanning, onClick = {
                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                    val file = File(dir, "etiquette-${System.currentTimeMillis()}.jpg")
                    pendingShot = file.path
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                }) { Text(if (scanning) "Lecture…" else "Scanner") }
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val labels = listOf("Tous (${sheets.size})", "Chez moi", "En réassort", "À corriger")
                items(labels.size) { i -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(labels[i]) }) }
            }
        }
        if (shown.isEmpty()) item { Text("Aucun bijou.", Modifier.padding(16.dp)) }
        items(shown, key = { it.product.key }) { s ->
            ElevatedCard(Modifier.fillMaxWidth().clickable { onOpenSheet(s.product.key) }) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhotoThumb(s.image, size = 60.dp)
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(
                            listOfNotNull(s.variant.takeIf { it.isNotBlank() }, s.skus.firstOrNull()).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Group.entries.forEach { g ->
                                val n = s.count(g)
                                if (n > 0) Text("${g.label} $n", style = MaterialTheme.typography.labelMedium, color = groupColor(g))
                            }
                        }
                        if (s.issues.isNotEmpty() || !s.balanced) {
                            Text("À corriger : ${s.issues.size.coerceAtLeast(1)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("${s.count(Group.IN_HAND)}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}

/**
 * Fiche stock d'un bijou : bilan, points à corriger avec leur correction, et chaque pièce avec son état,
 * sa fiche, sa vente et son historique. Toutes les corrections se font ici.
 */
@Composable
fun StockSheetScreen(
    key: String,
    stock: StockOverview?,
    sales: List<Sale>,
    modifier: Modifier,
    onOpenArticle: (Long) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = remember(stock, sales, key) { stock?.let { StockRegister.build(it, sales).firstOrNull { s -> s.product.key == key } } }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    if (sheet == null) {
        Column(modifier.padding(16.dp)) { Text("Bijou introuvable (commande supprimée ?).") }
        return
    }
    fun act(done: String, block: suspend () -> Unit) {
        scope.launch {
            try { block(); onMessage(done) } catch (e: CancellationException) { throw e } catch (e: Exception) { onMessage(e.message ?: "Correction impossible.") }
        }
    }
    confirm?.let { (text, block) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirmer") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; act("Stock corrigé.", block) }) { Text("Confirmer") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } },
        )
    }
    val purchases = context.container().purchaseRepository
    val byGroup = Group.entries.associateWith { sheet.count(it) }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhotoThumb(sheet.image, size = 88.dp)
                Column(Modifier.weight(1f)) {
                    Text(sheet.title, style = MaterialTheme.typography.titleMedium)
                    if (sheet.variant.isNotBlank()) Text("Variante achetée : ${sheet.variant}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    if (sheet.skus.isNotEmpty()) Text("Code étiquette : ${sheet.skus.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Text("Fournisseur : ${sheet.product.name}", style = MaterialTheme.typography.labelSmall, maxLines = 3)
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BalanceLine(sheet.bought, byGroup, sheet.accounted)
                    if (sheet.count(Group.IN_HAND) > 0) {
                        Text(
                            "Chez moi : " + listOf(State.NO_LISTING, State.DRAFT, State.ONLINE, State.RESERVED, State.TO_SHIP)
                                .mapNotNull { st -> sheet.count(st).takeIf { it > 0 }?.let { "$it ${st.label.lowercase()}" } }.joinToString(", ") +
                                ". Valeur d'achat ${Money.formatEuros(sheet.inHandCostCents)}.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (sheet.issues.isNotEmpty()) {
            item { Text("À corriger", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) }
            items(sheet.issues, key = { "${it.kind}-${it.saleId}-${it.articleId}-${it.itemId}" }) { issue ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(issue.text, style = MaterialTheme.typography.bodySmall)
                        when (issue.kind) {
                            IssueKind.ORPHAN_SALE -> {
                                val sale = sales.firstOrNull { it.id == issue.saleId }
                                val candidates = sheet.pieces.filter { it.state in setOf(State.NO_LISTING, State.DRAFT, State.ONLINE, State.RESERVED) }
                                if (sale != null && candidates.isNotEmpty()) {
                                    Text("La pièce vendue est :", style = MaterialTheme.typography.labelMedium)
                                    candidates.distinctBy { it.article?.id ?: -it.lot.itemId }.forEach { p ->
                                        TextButton(onClick = {
                                            confirm = "La vente du ${Money.formatEuros(sale.salePriceCents)} sera rattachée à cette pièce, qui sortira de « chez moi »." to suspend {
                                                val a = p.article
                                                if (a != null) purchases.attachSaleToArticle(sale.id, a.id) else purchases.attachSaleToPurchase(sale.id, p.lot.itemId)
                                            }
                                        }) {
                                            Text(p.article?.let { "${p.state.label} n° ${it.id} « ${it.title.take(40)} »" } ?: "Pièce sans annonce (commande ${p.lot.orderRef.takeLast(6)})")
                                        }
                                    }
                                }
                            }
                            IssueKind.SALE_NOT_SOLD -> issue.articleId?.let { id ->
                                TextButton(onClick = {
                                    confirm = "La fiche n° $id passera « Vendu »." to suspend { context.container().articleRepository.setStatus(id, fr.vintedassistant.mobile.domain.ArticleStatus.SOLD) }
                                }) { Text("Marquer la fiche vendue") }
                            }
                            IssueKind.SOLD_WITHOUT_SALE -> issue.articleId?.let { id -> TextButton(onClick = { onOpenArticle(id) }) { Text("Ouvrir la fiche pour enregistrer la vente") } }
                            IssueKind.EXTRA_LISTING -> Text(
                                "Les fiches de trop sont marquées « en trop » ci-dessous : ouvre-les et change leur achat, ou retire-les si la pièce n'existe pas.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            IssueKind.SUSPICIOUS_LINE -> Text("Elle sera retirée au prochain démarrage si aucune fiche n'y est rattachée.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Group.entries.forEach { g ->
            val list = sheet.pieces.filter { it.state.group == g }
            if (list.isEmpty()) return@forEach
            item(key = "g-$g") { Text("${g.label} (${list.size})", style = MaterialTheme.typography.titleSmall, color = groupColor(g), modifier = Modifier.padding(top = 6.dp)) }
            items(list.size, key = { i -> "p-$g-$i" }) { i ->
                val p = list[i]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PhotoThumb(p.article?.photos?.firstOrNull() ?: sheet.product.imageFile, size = 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.state.label + if (p.extra) " • en trop" else "",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (p.extra) MaterialTheme.colorScheme.error else groupColor(g),
                                    fontWeight = FontWeight.Bold,
                                )
                                p.article?.let { Text("Fiche n° ${it.id} « ${it.title.take(60)} » • ${Money.formatEuros(it.priceCents)}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        StockRegister.history(p).forEach { Text("· $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            p.article?.let { a -> TextButton(onClick = { onOpenArticle(a.id) }) { Text("Ouvrir la fiche") } }
                            when (p.state) {
                                State.NO_LISTING -> {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try { onOpenArticle(purchases.createDraft(p.lot.itemId)) } catch (e: CancellationException) { throw e } catch (e: Exception) { onMessage("Création impossible.") }
                                        }
                                    }) { Text("Créer la fiche") }
                                    TextButton(onClick = { confirm = "Cette pièce n'est pas chez toi : elle passera « manquante » (hors stock)." to suspend { purchases.pieceAbsent(p.lot.itemId, null) } }) { Text("Absente") }
                                }
                                State.DRAFT, State.ONLINE, State.RESERVED -> TextButton(onClick = {
                                    confirm = (if (p.state == State.DRAFT) "La pièce n'est pas chez toi : le brouillon n° ${p.article!!.id} passera « Retiré » (rien n'est supprimé)."
                                        else "La pièce n'est pas chez toi : supprime aussi l'annonce dans Vinted. La fiche n° ${p.article!!.id} passera « Retiré ».") to
                                        suspend { purchases.pieceAbsent(p.lot.itemId, p.article!!.id) }
                                }) { Text("Absente") }
                                State.MISSING -> TextButton(onClick = { act("Pièce remise en stock.") { purchases.pieceFound(p.lot.itemId) } }) { Text("Retrouvée") }
                                State.WITHDRAWN -> p.article?.let { a -> TextButton(onClick = { act("Fiche remise en brouillon.") { purchases.restoreWithdrawn(a.id) } }) { Text("Remettre en stock") } }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
        item { HorizontalDivider(); Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}
