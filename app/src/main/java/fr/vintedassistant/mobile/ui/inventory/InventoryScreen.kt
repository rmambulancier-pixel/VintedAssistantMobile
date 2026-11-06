package fr.vintedassistant.mobile.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(viewModel: InventoryViewModel = viewModel(factory = InventoryViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // rememberSaveable : la saisie survit à la rotation et à l'arrêt du processus par Android.
    var title by rememberSaveable { mutableStateOf("") }
    var costText by rememberSaveable { mutableStateOf("") }
    var priceText by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    val pendingDelete = state.items.firstOrNull { it.id == pendingDeleteId }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Supprimer l'article ?") },
            text = { Text("« ${pendingDelete.title} » sera supprimé définitivement.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteArticle(pendingDelete.id)
                    pendingDeleteId = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Annuler") }
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Vinted Assistant") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Tableau de bord", style = MaterialTheme.typography.headlineSmall)
                Text("Stock local • Version 0.2.0")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard("Articles", state.items.size.toString(), Modifier.weight(1f))
                    SummaryCard("Marge estimée", Money.formatEuros(state.totalMarginCents), Modifier.weight(1f))
                }
            }
            state.message?.let { msg ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(msg)
                            TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ajouter un article", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("Nom de l'article") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = costText, onValueChange = { costText = it },
                            label = { Text("Coût d'achat (€)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = priceText, onValueChange = { priceText = it },
                            label = { Text("Prix de vente visé (€)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(
                            onClick = {
                                error = viewModel.addArticle(title, costText, priceText)
                                if (error == null) {
                                    title = ""; costText = ""; priceText = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Enregistrer l'article") }
                    }
                }
            }
            item { Text("Mon stock", style = MaterialTheme.typography.titleLarge) }
            when {
                state.loading -> item { CircularProgressIndicator() }
                state.items.isEmpty() -> item {
                    Text("Aucun article pour le moment. Ajoute ton premier article ci-dessus.")
                }
                else -> items(state.items, key = { it.id }) { article ->
                    ArticleCard(article, onDelete = { pendingDeleteId = article.id })
                }
            }
            item {
                Text(
                    "Cette version gère un inventaire enregistré dans une base de données sur le téléphone. " +
                        "Elle ne se connecte pas à Vinted et ne publie ni ne republie d'annonces.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ArticleCard(article: Article, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(article.title, style = MaterialTheme.typography.titleMedium)
            Text("Achat : ${Money.formatEuros(article.costCents)}  •  Vente visée : ${Money.formatEuros(article.priceCents)}")
            Text(
                "Marge brute estimée : ${Money.formatEuros(article.marginCents)}",
                color = if (article.marginCents >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text("Statut : ${article.status}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDelete) { Text("Supprimer") }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
