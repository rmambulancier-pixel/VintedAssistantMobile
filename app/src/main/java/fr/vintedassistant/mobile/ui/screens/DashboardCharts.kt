package fr.vintedassistant.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.domain.Charts
import fr.vintedassistant.mobile.domain.DashboardStats
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.Restock
import fr.vintedassistant.mobile.ui.PhotoThumb
import java.util.Locale

/** Carte principale : chiffre d'affaires du mois en grand, bénéfice, ventes, objectif et projection. */
@Composable
fun HeroCard(stats: DashboardStats, goalEuros: Int, projection: Restock.Projection?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ce mois-ci", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                Money.formatEuros(stats.monthRevenueCents),
                style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HeroFigure("Bénéfice net", Money.formatEuros(stats.monthNetCents))
                HeroFigure("Ventes", stats.monthSalesCount.toString())
                if (stats.monthRevenueCents > 0) HeroFigure("Marge", "${stats.monthNetCents * 100 / stats.monthRevenueCents} %")
            }
            if (goalEuros > 0) {
                val goal = goalEuros * 100L
                LinearProgressIndicator(
                    progress = { (stats.monthRevenueCents.toFloat() / goal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
                Text(
                    if (stats.monthRevenueCents >= goal) "Objectif de ${Money.formatEuros(goal)} atteint."
                    else "Objectif ${Money.formatEuros(goal)} : il manque ${Money.formatEuros(goal - stats.monthRevenueCents)}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (projection != null && stats.monthSalesCount > 0) {
                Text(
                    "À ce rythme : ${Money.formatEuros(projection.projectedCents)} en fin de mois" +
                        if (projection.lastMonthCents > 0) " (mois dernier ${Money.formatEuros(projection.lastMonthCents)})." else ".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun HeroFigure(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Chiffre d'affaires par jour (7 ou 30 jours) ou par mois (6 mois), en barres. */
@Composable
fun RevenueChart(daily30: List<Charts.Day>, monthly: List<Charts.Month>) {
    var mode by rememberSaveable { mutableIntStateOf(1) }
    val values: List<Long>
    val labels: List<String>
    when (mode) {
        0 -> { val d = daily30.takeLast(7); values = d.map { it.revenueCents }; labels = d.map { it.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.FRANCE) } }
        1 -> { values = daily30.map { it.revenueCents }; labels = daily30.mapIndexed { i, d -> if (i % 5 == 4 || i == daily30.lastIndex) d.date.dayOfMonth.toString() else "" } }
        else -> { values = monthly.map { it.revenueCents }; labels = monthly.map { it.label } }
    }
    val total = values.sum()
    val best = values.maxOrNull() ?: 0L
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chiffre d'affaires", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${Money.formatEuros(total)} • meilleur ${if (mode == 2) "mois" else "jour"} ${Money.formatEuros(best)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("7 j", "30 j", "6 mois").forEachIndexed { i, l -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(l) }) }
            }
            val bar = MaterialTheme.colorScheme.primary
            val last = MaterialTheme.colorScheme.tertiary
            val empty = MaterialTheme.colorScheme.surfaceVariant
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val n = values.size.coerceAtLeast(1)
                val gap = size.width / n * 0.25f
                val w = size.width / n - gap
                val max = best.coerceAtLeast(1).toFloat()
                values.forEachIndexed { i, v ->
                    val h = if (v <= 0) 3f else (v / max * size.height).coerceAtLeast(6f)
                    drawRoundRect(
                        color = if (v <= 0) empty else if (i == values.lastIndex) last else bar,
                        topLeft = Offset(i * (w + gap) + gap / 2, size.height - h),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w.coerceAtMost(12f) / 2),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** Ligne à barre horizontale : libellé, valeur à droite, barre proportionnelle. */
@Composable
fun BarRow(title: String, detail: String, value: String, fraction: Float, image: String? = null, showImage: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showImage) PhotoThumb(image, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
            }
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** « Ce qui rapporte vraiment » : bijoux classés par bénéfice net total. */
@Composable
fun TopProductsCard(top: List<Charts.Top>) {
    if (top.isEmpty()) return
    val max = top.maxOf { it.netCents }.coerceAtLeast(1)
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ce qui rapporte vraiment", style = MaterialTheme.typography.titleSmall)
            top.forEach { t ->
                BarRow(
                    title = t.title,
                    detail = "${t.sold} vendu(s) • ${Money.formatEuros(t.netCents / t.sold)} net par pièce" +
                        (t.averageDays?.let { " • part en ${String.format(Locale.FRANCE, "%.0f", it)} j" } ?: ""),
                    value = Money.formatEuros(t.netCents),
                    fraction = t.netCents.toFloat() / max,
                    image = t.image, showImage = true,
                )
            }
        }
    }
}
