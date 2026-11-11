package fr.vintedassistant.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vintedassistant.mobile.domain.CategoryStats
import fr.vintedassistant.mobile.domain.Charts
import fr.vintedassistant.mobile.domain.DashboardStats
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.Restock
import fr.vintedassistant.mobile.domain.StockBoard
import fr.vintedassistant.mobile.ui.PhotoThumb
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CARD = RoundedCornerShape(24.dp)

/**
 * Couleurs fixes des graphiques. Les couleurs dynamiques suivent le fond d'écran : avec un fond gris,
 * tout devenait gris et les parts ne se distinguaient plus. Ces teintes restent lisibles en clair et en sombre.
 */
private object Viz {
    val BLUE = Color(0xFF5B9BFF)
    val GREEN = Color(0xFF34C38F)
    val AMBER = Color(0xFFF5B54A)
    val ROSE = Color(0xFFF2647A)
    val VIOLET = Color(0xFFA78BFA)
    val INK = Color(0xFF14161A)
    val series = listOf(BLUE, GREEN, AMBER, ROSE, VIOLET)
    val HERO = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF1D3B8F), Color(0xFF4A2A8C)))
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n > 1) many else one

private fun days(d: Double): String = if (d < 1) "le jour même" else String.format(Locale.FRANCE, "en %.0f j", d)

/** « 28 € » sans centimes pour les étiquettes de graphiques, « 7,50 € » au-dessous de 10 €. */
private fun short(cents: Long): String = if (cents >= 1000 || cents % 100 == 0L) "${(cents + 50) / 100} €" else Money.formatEuros(cents)

private fun pct(v: Double): String = String.format(Locale.FRANCE, "%.0f %%", v)

/** Badge de variation : « +37 % » vert, « −12 % » rouge. */
@Composable
private fun Delta(change: Int?, against: String) {
    if (change == null) return
    val up = change >= 0
    val color = if (up) Viz.GREEN else Viz.ROSE
    // Au-delà de +200 %, « ×9,7 » se lit mieux que « +867 % ».
    val value = when {
        change >= 200 -> String.format(Locale.FRANCE, "×%.1f", 1 + change / 100.0)
        up -> "+$change %"
        else -> "−${-change} %"
    }
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.18f)) {
        Text(
            (if (up) "▲ " else "▼ ") + "$value $against",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1,
        )
    }
}

/** Anneau de progression avec le pourcentage au centre. */
@Composable
private fun Ring(fraction: Float, label: String, sub: String, color: Color, track: Color, textColor: Color) {
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(96.dp)) {
            val stroke = 11.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = textColor)
        }
    }
}

/**
 * Carte du mois : chiffre d'affaires, variation par rapport au mois dernier à la même date, bénéfice, ventes,
 * marge, délai de vente, anneau d'objectif, projection et cumul depuis le début. Tout ce qui concerne l'argent est ici.
 */
@Composable
fun HeroCard(stats: DashboardStats, goalEuros: Int, sales: List<fr.vintedassistant.mobile.domain.Sale>, now: Long) {
    val (cur, prev) = remember(sales, now / 3_600_000) { Charts.monthToDate(sales, now) }
    val projection = remember(sales, now / 3_600_000) { Restock.projection(sales, now) }
    val on = Color.White
    val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val lastMonth = today.minusMonths(1).month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.FRANCE)
    val against = if (today.dayOfMonth == 1) "vs le 1er $lastMonth" else "vs 1er–${today.dayOfMonth} $lastMonth"
    Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(Viz.HERO).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Chiffre d'affaires du mois", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
                    Text(Money.formatEuros(stats.monthRevenueCents), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = on)
                    Delta(Charts.change(cur.revenueCents, prev.revenueCents), against)
                }
                if (goalEuros > 0) {
                    val goal = goalEuros * 100L
                    val f = stats.monthRevenueCents.toFloat() / goal
                    Ring(f, pct(f * 100.0), "de ${goalEuros} €", if (f >= 1f) Viz.GREEN else Viz.AMBER, on.copy(alpha = 0.18f), on)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Kpi("Bénéfice net", Money.formatEuros(stats.monthNetCents), Color(0xFF8FF0C4), Modifier.weight(1f), on.copy(alpha = 0.75f))
                Kpi("Ventes", stats.monthSalesCount.toString(), on, Modifier.weight(0.7f), on.copy(alpha = 0.75f))
                Kpi("Marge", if (stats.monthRevenueCents > 0) "${stats.monthNetCents * 100 / stats.monthRevenueCents} %" else "—", on, Modifier.weight(0.7f), on.copy(alpha = 0.75f))
                Kpi("Vendu en", stats.averageDaysToSell?.let { String.format(Locale.FRANCE, "%.0f j", it) } ?: "—", on, Modifier.weight(0.7f), on.copy(alpha = 0.75f))
            }
            if (stats.monthSalesCount > 0) {
                Text(
                    buildString {
                        append("Projection fin de mois : ${Money.formatEuros(projection.projectedCents)}")
                        if (goalEuros > 0) {
                            val goal = goalEuros * 100L
                            append(if (stats.monthRevenueCents >= goal) " • objectif atteint" else " • reste ${Money.formatEuros(goal - stats.monthRevenueCents)} pour l'objectif")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.9f),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(on.copy(alpha = 0.15f)))
            Text(
                "Depuis le début : ${stats.totalSalesCount} ventes • ${Money.formatEuros(stats.totalRevenueCents)} • ${Money.formatEuros(stats.totalNetCents)} de bénéfice",
                style = MaterialTheme.typography.labelMedium, color = on.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, color: Color, modifier: Modifier, labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
    }
}

/**
 * Chiffre d'affaires en barres, avec le montant sur les barres, la moyenne en pointillés, et la comparaison
 * avec la période précédente. Toucher une barre affiche son détail.
 */
@Composable
fun RevenueChart(daily: List<Charts.Day>, monthly: List<Charts.Month>, previousWeekCents: Long, previousMonthCents: Long) {
    var mode by rememberSaveable { mutableIntStateOf(1) }
    var selected by remember(mode) { mutableStateOf<Int?>(null) }
    data class B(val value: Long, val secondary: Long?, val axis: String, val caption: String)
    val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)
    val bars: List<B> = when (mode) {
        0 -> daily.takeLast(7).map { d ->
            B(d.revenueCents, null, d.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.FRANCE).take(3), "${d.date.format(dayFmt)} : ${Money.formatEuros(d.revenueCents)}, ${d.sales} vente(s)")
        }
        1 -> daily.mapIndexed { i, d ->
            B(d.revenueCents, null, if (i % 5 == 4 || i == daily.lastIndex) d.date.dayOfMonth.toString() else "", "${d.date.format(dayFmt)} : ${Money.formatEuros(d.revenueCents)}, ${d.sales} vente(s)")
        }
        else -> monthly.map { m ->
            B(m.revenueCents, m.netCents, m.label, "${m.label} : ${Money.formatEuros(m.revenueCents)}, ${Money.formatEuros(m.netCents)} de bénéfice, ${m.sales} vente(s)")
        }
    }
    val total = bars.sumOf { it.value }
    val count = when (mode) { 0 -> daily.takeLast(7).sumOf { it.sales }; 1 -> daily.sumOf { it.sales }; else -> monthly.sumOf { it.sales } }
    val average = if (bars.isEmpty()) 0L else total / bars.size
    val previous = when (mode) { 0 -> previousWeekCents; 1 -> previousMonthCents; else -> null }
    val topIdx = bars.withIndex().filter { it.value.value > 0 }.sortedByDescending { it.value.value }.take(3).map { it.index }.toSet()

    val measurer = rememberTextMeasurer()
    val primary = Viz.BLUE
    val soft = Viz.BLUE.copy(alpha = 0.35f)
    val accent = Viz.AMBER
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ElevatedCard(Modifier.fillMaxWidth(), shape = CARD) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Évolution des ventes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("7 jours", "30 jours", "6 mois").forEachIndexed { i, l -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(l) }) }
            }
            Row(Modifier.fillMaxWidth()) {
                Kpi("Chiffre d'affaires", Money.formatEuros(total), ink, Modifier.weight(1.2f))
                Kpi("Ventes", count.toString(), ink, Modifier.weight(0.7f))
                Kpi(if (mode == 2) "Moyenne / mois" else "Moyenne / jour", Money.formatEuros(average), ink, Modifier.weight(1f))
            }
            previous?.let { Delta(Charts.change(total, it), if (mode == 0) "vs les 7 jours d'avant" else "vs les 30 jours d'avant") }
            Canvas(
                Modifier.fillMaxWidth().height(170.dp).pointerInput(bars.size) {
                    detectTapGestures { o ->
                        val i = (o.x / (size.width.toFloat() / bars.size.coerceAtLeast(1))).toInt().coerceIn(0, bars.lastIndex)
                        selected = if (selected == i) null else i
                    }
                },
            ) {
                val n = bars.size.coerceAtLeast(1)
                val labelSpace = 18.dp.toPx()
                val chartH = size.height - labelSpace
                val slot = size.width / n
                val gap = slot * if (n > 12) 0.25f else 0.35f
                val w = slot - gap
                val max = (bars.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(1).toFloat()
                bars.forEachIndexed { i, b ->
                    val h = if (b.value <= 0) 3f else (b.value / max * (chartH - 4f)).coerceAtLeast(6f)
                    val x = i * slot + gap / 2
                    val isSel = selected == i
                    drawRoundRect(
                        color = when { b.value <= 0 -> empty; isSel -> accent; b.secondary != null -> soft; else -> primary },
                        topLeft = Offset(x, size.height - h), size = Size(w, h),
                        cornerRadius = CornerRadius((w / 2).coerceAtMost(14f)),
                    )
                    // 6 mois : la part de bénéfice dans la barre, en plein.
                    b.secondary?.takeIf { it > 0 && b.value > 0 }?.let { net ->
                        val nh = h * (net.toFloat() / b.value).coerceIn(0f, 1f)
                        drawRoundRect(if (isSel) accent else Viz.GREEN, Offset(x, size.height - nh), Size(w, nh), CornerRadius((w / 2).coerceAtMost(14f)))
                    }
                    val showValue = b.value > 0 && (n <= 12 || i in topIdx || isSel)
                    if (showValue) {
                        val t = measurer.measure(short(b.value), TextStyle(fontSize = if (n > 12) 9.sp else 11.sp, fontWeight = FontWeight.Bold, color = ink))
                        val tx = (x + w / 2 - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
                        drawText(t, topLeft = Offset(tx, (size.height - h - t.size.height - 2f).coerceAtLeast(0f)))
                    }
                }
                if (average > 0) {
                    val y = size.height - average / max * (chartH - 4f)
                    drawLine(muted, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    // Étiquette sur fond plein, à gauche : elle ne passe plus derrière les barres.
                    val t = measurer.measure("moy. ${short(average)}", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ink))
                    val pad = 4.dp.toPx()
                    val top = (y - t.size.height - pad * 2 - 2f).coerceAtLeast(0f)
                    drawRoundRect(surface, Offset(0f, top), Size(t.size.width + pad * 2, t.size.height + pad), CornerRadius(pad * 2))
                    drawText(t, topLeft = Offset(pad, top + pad / 2))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                bars.forEach { Text(it.axis, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, softWrap = false) }
            }
            Text(
                selected?.let { bars.getOrNull(it)?.caption } ?: if (mode == 2) "Bleu clair : chiffre d'affaires ; vert : bénéfice. Touche une barre pour le détail." else "Touche une barre pour le détail du jour.",
                style = MaterialTheme.typography.bodySmall,
                color = if (selected != null) accent else muted,
                fontWeight = if (selected != null) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** Répartition du chiffre d'affaires par type de bijou : anneau en parts, pourcentages et chiffres clés. */
@Composable
fun CategoryDonut(categories: List<CategoryStats>) {
    if (categories.isEmpty()) return
    val shown = categories.take(5)
    val total = shown.sumOf { it.revenueCents }.coerceAtLeast(1)
    val palette = Viz.series
    ElevatedCard(Modifier.fillMaxWidth(), shape = CARD) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ce qui se vend, par type", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(130.dp)) {
                        val stroke = 22.dp.toPx()
                        val inset = stroke / 2
                        var start = -90f
                        shown.forEachIndexed { i, c ->
                            val sweep = 360f * c.revenueCents / total
                            drawArc(palette[i % palette.size], start, (sweep - 2f).coerceAtLeast(1f), false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                            start += sweep
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(short(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${shown.sumOf { it.salesCount }} ventes", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    shown.forEachIndexed { i, c ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(palette[i % palette.size]))
                                Text(c.category, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(pct(c.revenueCents * 100.0 / total), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = palette[i % palette.size])
                            }
                            Text(
                                "${short(c.revenueCents)} • ${plural(c.salesCount, "vente")} • ${Money.formatEuros(c.averageNetCents)}/pièce" +
                                    (c.averageDaysToSell?.let { " • " + days(it) } ?: ""),
                                Modifier.padding(start = 16.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Meilleurs bijoux par bénéfice : part du bénéfice total, net par pièce, délai, et badge « à racheter »
 * quand le bijou est épuisé. Remplace la liste « À racheter » à part.
 */
@Composable
fun TopProductsCard(top: List<Charts.Top>, totalNetCents: Long, restock: List<Restock.Advice>, onRestock: () -> Unit) {
    if (top.isEmpty()) return
    val max = top.maxOf { it.netCents }.coerceAtLeast(1)
    val restockKeys = restock.map { it.key }.toSet()
    ElevatedCard(Modifier.fillMaxWidth(), shape = CARD) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Tes bijoux les plus rentables", style = MaterialTheme.typography.titleMedium)
            top.forEachIndexed { rank, t ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        fr.vintedassistant.mobile.ui.PhotoThumbAny(t.images, t.title, size = 52.dp)
                        Surface(shape = CircleShape, color = if (rank == 0) Viz.AMBER else Viz.INK.copy(alpha = 0.75f), modifier = Modifier.size(20.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("${rank + 1}", style = MaterialTheme.typography.labelSmall, color = if (rank == 0) Viz.INK else Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(Money.formatEuros(t.netCents), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Viz.GREEN)
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth((t.netCents.toFloat() / max).coerceIn(0.03f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Viz.GREEN))
                        }
                        Text(
                            (if (totalNetCents > 0) "${t.netCents * 100 / totalNetCents} % du bénéfice • " else "") +
                                "${plural(t.sold, "vendu")} • ${Money.formatEuros(t.netCents / t.sold)}/pièce" +
                                (t.averageDays?.let { " • " + days(it) } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                        if (t.key in restockKeys) {
                            Surface(shape = RoundedCornerShape(8.dp), color = Viz.ROSE.copy(alpha = 0.18f), onClick = onRestock) {
                                Text("Épuisé : à racheter", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Viz.ROSE)
                            }
                        }
                    }
                }
            }
            val others = restock.filter { r -> top.none { it.key == r.key } }
            if (others.isNotEmpty()) {
                Text(
                    "À racheter aussi : " + others.joinToString { it.title.take(30) },
                    style = MaterialTheme.typography.labelMedium, color = Viz.ROSE,
                )
            }
        }
    }
}

/**
 * Stock en une barre : chaque pièce chez moi par état, puis réassort et colis en route.
 * Un seul coup d'œil ; le détail est dans l'onglet Stock.
 */
@Composable
fun StockBar(board: StockBoard, potentialMarginCents: Long, stagnant: Int, onOpenStock: () -> Unit) {
    val parts = listOf(
        Triple("À mettre en vente", board.toList + board.drafts, Viz.AMBER),
        Triple("En vente", board.online + board.reserved, Viz.BLUE),
        Triple("Colis à préparer", board.toShip, Viz.ROSE),
    )
    val total = board.inHand.coerceAtLeast(1)
    ElevatedCard(onClick = onOpenStock, modifier = Modifier.fillMaxWidth(), shape = CARD) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Mon stock", style = MaterialTheme.typography.titleMedium)
                    Text("${board.inHand} pièce(s) chez moi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Voir ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                parts.filter { it.second > 0 }.forEach { (_, n, c) ->
                    Box(Modifier.weight(n.toFloat() / total).height(22.dp).background(c), contentAlignment = Alignment.Center) {
                        Text("$n", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Viz.INK)
                    }
                }
            }
            parts.forEach { (label, n, c) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("$n", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(pct(n * 100.0 / total), Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(Modifier.fillMaxWidth()) {
                Kpi("Achat en main", Money.formatEuros(board.inHandCostCents), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                Kpi("Marge à venir", Money.formatEuros(potentialMarginCents), Viz.GREEN, Modifier.weight(1f))
                Kpi("En réassort", board.restocking.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(0.8f))
            }
            Text(
                listOfNotNull(
                    board.shipping.takeIf { it > 0 }?.let { "$it colis en route vers les acheteurs" },
                    board.orders.firstOrNull()?.let { "prochain réassort : ${StockBoard.etaLabel(it, java.time.LocalDate.now())}" },
                    stagnant.takeIf { it > 0 }?.let { "$it annonce(s) qui stagnent" },
                ).joinToString(" • "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
