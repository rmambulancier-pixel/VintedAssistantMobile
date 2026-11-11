package fr.vintedassistant.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.domain.DashboardStats
import fr.vintedassistant.mobile.domain.Finance
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.ui.MessageCard
import fr.vintedassistant.mobile.ui.SectionTitle
import fr.vintedassistant.mobile.ui.StatRow
import fr.vintedassistant.mobile.ui.daysLabel
import java.util.Locale
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.vintedassistant.mobile.domain.DataAudit
import fr.vintedassistant.mobile.domain.Charts
import fr.vintedassistant.mobile.domain.Restock
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.ShippingDeadline
import fr.vintedassistant.mobile.reminders.ShippingReminder

private val FICHE_ID = Regex("#(\\d+)")

@Composable
fun DashboardScreen(
    stats: DashboardStats?,
    contributionRateBp: Int,
    message: String?,
    onDismissMessage: () -> Unit,
    modifier: Modifier,
    onOpenArticle: (Long) -> Unit,
    findings: List<DataAudit.Finding> = emptyList(),
    sales: List<Sale> = emptyList(),
    onOpenSales: () -> Unit = {},
    onOpenRepublish: () -> Unit = {},
    restock: List<Restock.Advice> = emptyList(),
    onOpenPurchases: () -> Unit = {},
    monthlyGoalEuros: Int = 0,
    board: fr.vintedassistant.mobile.domain.StockBoard? = null,
    stock: fr.vintedassistant.mobile.domain.StockOverview? = null,
    onOpenStock: () -> Unit = {},
) {
    var openFinding by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val dues = sales.mapNotNull { ShippingDeadline.due(it, now) }
    val urgent = dues.count { it.urgent }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        message?.let { item { MessageCard(it, onDismissMessage) } }
        if (stats == null) {
            item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            return@LazyColumn
        }
        if (urgent > 0) {
            item {
                Card(
                    onClick = onOpenSales,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        ShippingReminder.message(urgent, dues.count { it.late }) ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        val actionable = findings.filterNot { it.title.startsWith("Ventes sans date") }
        if (actionable.isNotEmpty()) {
            item { SectionTitle("À vérifier (${actionable.sumOf { it.lines.size }})") }
            items(actionable, key = { "finding-${it.title}" }) { f ->
                ElevatedCard(onClick = { openFinding = if (openFinding == f.title) null else f.title }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${f.title} : ${f.lines.size}", style = MaterialTheme.typography.titleSmall)
                        if (openFinding == f.title) {
                            f.lines.take(30).forEach { line ->
                                val id = FICHE_ID.find(line)?.groupValues?.get(1)?.toLongOrNull()
                                if (id != null) {
                                    TextButton(onClick = { onOpenArticle(id) }) { Text(line, style = MaterialTheme.typography.bodySmall) }
                                } else {
                                    Text(line, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (f.title.startsWith("Ventes sans fiche")) {
                                OutlinedButton(onClick = onOpenSales, modifier = Modifier.fillMaxWidth()) { Text("Corriger dans Ventes : « Rattacher à un achat »") }
                            }
                            if (f.lines.size > 30) Text("… et ${f.lines.size - 30} autre(s).", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Appuie pour voir le détail.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
        item { HeroCard(stats, monthlyGoalEuros, sales, now) }
        item {
            val daily = remember(sales, now / 3_600_000) { Charts.daily(sales, 30, now) }
            val monthly = remember(sales, contributionRateBp, now / 3_600_000) { Charts.monthly(sales, contributionRateBp, 6, now) }
            val prevWeek = remember(sales, now / 3_600_000) { Charts.daily(sales, 14, now).take(7).sumOf { it.revenueCents } }
            val prevMonth = remember(sales, now / 3_600_000) { Charts.daily(sales, 60, now).take(30).sumOf { it.revenueCents } }
            RevenueChart(daily, monthly, prevWeek, prevMonth)
        }
        item {
            val top = remember(sales, stock, contributionRateBp) { Charts.topProducts(stock, sales, contributionRateBp) }
            TopProductsCard(top, stats.totalNetCents, restock, onOpenPurchases)
        }
        if (stats.categories.isNotEmpty()) item { CategoryDonut(stats.categories) }
        if (board != null) item { StockBar(board, stats.potentialMarginCents, stats.stagnant.size, onOpenStock) }
        if (stats.stagnant.isNotEmpty()) {
            item { SectionTitle("Annonces qui stagnent (${stats.stagnant.size})") }
            items(stats.stagnant.take(10), key = { "stagnant-${it.article.id}" }) { s ->
                ArticleRow(s.article, trailing = {
                    Column {
                        Text(daysLabel(s.daysOnline), style = MaterialTheme.typography.labelMedium)
                        s.suggestedPriceCents?.let {
                            Text("→ ${Money.formatEuros(it)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }) { onOpenArticle(s.article.id) }
            }
            item {
                OutlinedButton(onClick = onOpenRepublish, modifier = Modifier.fillMaxWidth()) { Text("Republier ou baisser les prix") }
            }
        }
        item {
            Text(
                "Version ${appVersion(androidx.compose.ui.platform.LocalContext.current)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}
