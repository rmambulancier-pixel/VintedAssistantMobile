package fr.vintedassistant.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private data class StockItem(val id: Long, val title: String, val cost: Double, val price: Double, val status: String = "Brouillon") {
    val margin: Double get() = price - cost
    fun json() = JSONObject().put("id", id).put("title", title).put("cost", cost).put("price", price).put("status", status)
    companion object {
        fun from(json: JSONObject) = StockItem(json.optLong("id"), json.optString("title"), json.optDouble("cost"), json.optDouble("price"), json.optString("status", "Brouillon"))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { InventoryScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vinted_assistant", Context.MODE_PRIVATE) }
    var items by remember { mutableStateOf(loadItems(prefs)) }
    var title by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val totalMargin = items.sumOf { it.margin }

    fun save(updated: List<StockItem>) {
        items = updated
        val array = JSONArray()
        updated.forEach { array.put(it.json()) }
        prefs.edit().putString("items", array.toString()).apply()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Vinted Assistant") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Tableau de bord", style = MaterialTheme.typography.headlineSmall)
                Text("Stock local • Version 0.1.0")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard("Articles", items.size.toString(), Modifier.weight(1f))
                    SummaryCard("Marge estimée", String.format("%.2f €", totalMargin), Modifier.weight(1f))
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ajouter un article", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Nom de l'article") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = costText, onValueChange = { costText = it }, label = { Text("Coût d'achat (€)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = priceText, onValueChange = { priceText = it }, label = { Text("Prix de vente visé (€)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            val cost = costText.replace(',', '.').toDoubleOrNull()
                            val price = priceText.replace(',', '.').toDoubleOrNull()
                            if (title.isBlank() || cost == null || price == null || cost < 0 || price < 0) {
                                error = "Renseigne un nom et deux montants valides."
                            } else {
                                save(items + StockItem(System.currentTimeMillis(), title.trim(), cost, price))
                                title = ""; costText = ""; priceText = ""; error = null
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer l'article") }
                    }
                }
            }
            item { Text("Mon stock", style = MaterialTheme.typography.titleLarge) }
            if (items.isEmpty()) {
                item { Text("Aucun article pour le moment. Ajoute ton premier article ci-dessus.") }
            } else {
                items(items, key = { it.id }) { stockItem ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stockItem.title, style = MaterialTheme.typography.titleMedium)
                            Text("Achat : %.2f €  •  Vente visée : %.2f €".format(stockItem.cost, stockItem.price))
                            Text("Marge brute estimée : %.2f €".format(stockItem.margin), color = if (stockItem.margin >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text("Statut : ${stockItem.status}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { save(items.filterNot { it.id == stockItem.id }) }) { Text("Supprimer") }
                        }
                    }
                }
            }
            item { Text("Cette version gère un inventaire local. Elle ne se connecte pas à Vinted et ne publie ni ne republie automatiquement d'annonces.", style = MaterialTheme.typography.bodySmall) }
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

private fun loadItems(prefs: android.content.SharedPreferences): List<StockItem> {
    val raw = prefs.getString("items", null) ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { StockItem.from(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
