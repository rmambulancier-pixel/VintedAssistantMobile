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
                            if (f.lines.size > 30) Text("… et ${f.lines.size - 30} autre(s).", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Appuie pour voir le détail.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
        item { SectionTitle("Ce mois-ci") }
        item {
            StatRow(
                "Ventes" to stats.monthSalesCount.toString(),
                "Chiffre d'affaires" to Money.formatEuros(stats.monthRevenueCents),
                "Bénéfice net" to Money.formatEuros(stats.monthNetCents),
            )
        }
        item { SectionTitle("Depuis le début") }
        item {
            StatRow(
                "Ventes" to stats.totalSalesCount.toString(),
                "Chiffre d'affaires" to Money.formatEuros(stats.totalRevenueCents),
                "Bénéfice net" to Money.formatEuros(stats.totalNetCents),
            )
        }
        if (monthlyGoalEuros > 0) {
            item {
                val goal = monthlyGoalEuros * 100L
                val ratio = (stats.monthRevenueCents.toFloat() / goal).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (stats.monthRevenueCents >= goal) "Objectif du mois atteint : ${Money.formatEuros(stats.monthRevenueCents)} sur ${Money.formatEuros(goal)}."
                        else "Objectif du mois : ${Money.formatEuros(stats.monthRevenueCents)} sur ${Money.formatEuros(goal)}, il manque ${Money.formatEuros(goal - stats.monthRevenueCents)}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (stats.monthSalesCount > 0) {
            item {
                val p = Restock.projection(sales, now)
                Text(
                    "À ce rythme, le mois finira vers ${Money.formatEuros(p.projectedCents)} de chiffre d'affaires" +
                        (if (p.lastMonthCents > 0) " (mois dernier : ${Money.formatEuros(p.lastMonthCents)})." else "."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (stats.undatedSalesCount > 0) {
            item {
                Text(
                    "Dont ${stats.undatedSalesCount} vente(s) importée(s) de Vinted sans date connue : " +
                        "comptées depuis le début, pas dans le mois.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (stats.missingCostCount > 0) {
            item {
                Text(
                    "${stats.missingCostCount} article(s) sans coût d'achat : marges et bénéfices surestimés tant qu'il n'est pas saisi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Text(
                "Bénéfice net = prix de vente − coût d'achat − frais − cotisations (" +
                    Finance.formatRate(contributionRateBp) + ", modifiable dans les réglages).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (restock.isNotEmpty()) {
            item { SectionTitle("À racheter (${restock.size})") }
            item {
                Text(
                    "Tes bijoux vendus au moins deux fois et presque épuisés, les plus rentables d'abord.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(restock.take(6), key = { "restock-${it.key}" }) { r ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PhotoThumb(r.imageFile, size = 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(
                                "${r.sold} vendus" + (r.averageDays?.let { " en ${String.format(Locale.FRANCE, "%.0f", it)} j en moyenne" } ?: "") +
                                    " • reste ${r.remaining}" + if (r.inTransit > 0) " (en réassort)" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Acheté ${Money.formatEuros(r.averageCostCents)}, vendu ${Money.formatEuros(r.averagePriceCents)} : " +
                                    "marge ${Money.formatEuros(r.marginCents)} par pièce" +
                                    (if (r.multiple >= 2) ", mise ×${String.format(Locale.FRANCE, "%.0f", r.multiple)}" else "") + ".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = onOpenPurchases, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir les achats fournisseur") } }
        }
        item { SectionTitle("Stock") }
        if (board != null) {
            item {
                StatRow(
                    "Chez moi" to board.inHand.toString(),
                    "En vente" to (board.online + board.reserved).toString(),
                    "En réassort" to board.restocking.toString(),
                )
            }
            item {
                StatRow(
                    "À mettre en vente" to (board.toList + board.drafts).toString(),
                    "En route vers l'acheteur" to board.shipping.toString(),
                )
            }
        } else {
            item {
                StatRow(
                    "Fiches en stock" to stats.stockCount.toString(),
                    "En ligne" to stats.onlineCount.toString(),
                    "Brouillons" to stats.draftCount.toString(),
                )
            }
        }
        item {
            StatRow(
                "Valeur d'achat en main" to Money.formatEuros(board?.inHandCostCents ?: stats.stockCostCents),
                "Marge potentielle" to Money.formatEuros(stats.potentialMarginCents),
            )
        }
        item {
            StatRow(
                "Colis à préparer" to stats.toShipCount.toString(),
                "Délai moyen de vente" to (stats.averageDaysToSell?.let { String.format(Locale.FRANCE, "%.1f j", it) } ?: "—"),
            )
        }

        item { SectionTitle("Articles qui stagnent (${stats.stagnant.size})") }
        if (stats.stagnant.isEmpty()) {
            item { Text("Aucun article en ligne depuis trop longtemps.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(stats.stagnant.take(10), key = { "stagnant-${it.article.id}" }) { s ->
                ArticleRow(s.article, trailing = {
                    Column {
                        Text(daysLabel(s.daysOnline), style = MaterialTheme.typography.labelMedium)
                        s.suggestedPriceCents?.let {
                            Text("→ ${Money.formatEuros(it)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }) { onOpenArticle(s.article.id) }
                s.marketPriceCents?.let {
                    Text(
                        "Modèles proches vendus ${Money.formatEuros(it)} (prix médian, ${s.marketSales} ventes).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                OutlinedButton(onClick = onOpenRepublish, modifier = Modifier.fillMaxWidth()) {
                    Text("Republier ou baisser les prix")
                }
            }
        }

        item { SectionTitle("Ce qui se vend le mieux") }
        if (stats.categories.isEmpty()) {
            item { Text("Les statistiques apparaîtront après tes premières ventes.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(stats.categories.take(8), key = { "cat-${it.category}" }) { c ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(c.category, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${c.salesCount} vente(s) • CA ${Money.formatEuros(c.revenueCents)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column {
                            Text("Net moyen ${Money.formatEuros(c.averageNetCents)}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                c.averageDaysToSell?.let { "Vendu en ${String.format(Locale.FRANCE, "%.1f", it)} j" } ?: "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Version ${appVersion(androidx.compose.ui.platform.LocalContext.current)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}
