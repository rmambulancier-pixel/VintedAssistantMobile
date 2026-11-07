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

@Composable
fun DashboardScreen(
    stats: DashboardStats?,
    contributionRateBp: Int,
    message: String?,
    onDismissMessage: () -> Unit,
    modifier: Modifier,
    onOpenArticle: (Long) -> Unit,
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        message?.let { item { MessageCard(it, onDismissMessage) } }
        if (stats == null) {
            item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            return@LazyColumn
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
        item { SectionTitle("Stock") }
        item {
            StatRow(
                "En stock" to stats.stockCount.toString(),
                "En ligne" to stats.onlineCount.toString(),
                "Brouillons" to stats.draftCount.toString(),
            )
        }
        item {
            StatRow(
                "Valeur d'achat" to Money.formatEuros(stats.stockCostCents),
                "Marge potentielle" to Money.formatEuros(stats.potentialMarginCents),
            )
        }
        item {
            StatRow(
                "À expédier" to stats.toShipCount.toString(),
                "Délai moyen de vente" to (stats.averageDaysToSell?.let { String.format(Locale.FRANCE, "%.1f j", it) } ?: "—"),
            )
        }

        item { SectionTitle("Articles qui stagnent") }
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
