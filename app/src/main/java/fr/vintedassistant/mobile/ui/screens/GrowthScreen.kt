package fr.vintedassistant.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.DashboardStats
import fr.vintedassistant.mobile.domain.Growth
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.StockOverview
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.ui.container
import fr.vintedassistant.mobile.ui.copyToClipboard
import kotlinx.coroutines.launch
import java.util.Locale

private val GREEN = Color(0xFF34C38F)
private val AMBER = Color(0xFFF5B54A)

/**
 * « Gagner plus » : les quatre leviers mesurés sur les ventes réelles, dans l'ordre de l'argent qu'ils rapportent.
 * 1. Vitrine : pièces hors ligne et ce qu'elles valent, mise en vente et publication en série.
 * 2. Prix : modèles qui partent trop vite pour leur prix.
 * 3. Rachat : quoi recommander, combien, pour quel budget.
 * 4. Relances : messages pour les favoris et nouvelle photo principale des annonces qui dorment.
 * Rien n'est envoyé ni publié sans un toucher de l'utilisateur.
 */
@Composable
fun GrowthScreen(
    articles: List<Article>?,
    sales: List<Sale>,
    stock: StockOverview?,
    stats: DashboardStats?,
    modifier: Modifier,
    onExpress: () -> Unit,
    onPublish: (List<Long>) -> Unit,
    onOpenArticle: (Long) -> Unit,
    onOpenSupplier: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.container().articleRepository
    val all = articles.orEmpty()
    val now = remember { System.currentTimeMillis() }
    if (stock == null) { Text("Chargement…", modifier.padding(16.dp)); return }
    val show = remember(stock, all, sales) { Growth.showcase(stock, all, sales, now) }
    val prices = remember(stock, all, sales) { Growth.priceAdvice(stock, all, sales, now) }
    val plan = remember(stock, sales) { Growth.restockPlan(stock, sales, now) }
    val ready = all.filter { it.status == ArticleStatus.DRAFT && it.photos.isNotEmpty() && it.priceCents > 0 }
    val incomplete = all.count { it.status == ArticleStatus.DRAFT } - ready.size
    val stagnant = stats?.stagnant.orEmpty()

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Vitrine
        item {
            Card(colors = CardDefaults.cardColors(containerColor = GREEN.copy(alpha = 0.14f)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Mettre en vitrine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${show.waiting} pièce(s) hors vitrine ≈ ${Money.formatEuros(show.waitingValueCents)} de ventes " +
                            "(au prix médian de ${Money.formatEuros(show.medianPriceCents)}).",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "En ligne : ${show.online} • reçues sans fiche : ${show.notListed} • brouillons : ${show.drafts} • en réassort : ${show.inTransit}" +
                            (show.medianDays?.let { "\nUne pièce en ligne se vend en " + String.format(Locale.FRANCE, "%.0f", it) + " jour(s) (médiane) : chaque pièce publiée rapporte vite." } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (show.notListed > 0) {
                        Button(onClick = onExpress, modifier = Modifier.fillMaxWidth()) { Text("Préparer les ${show.notListed} pièce(s) reçue(s)") }
                    }
                    if (ready.isNotEmpty()) {
                        Button(onClick = { onPublish(ready.map { it.id }) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Publier les ${ready.size} brouillon(s) prêt(s) en série")
                        }
                    }
                    if (incomplete > 0) Text("$incomplete brouillon(s) sans photo ou sans prix : à compléter dans Stock, vue Brouillons.", style = MaterialTheme.typography.labelSmall)
                    if (Growth.giftSeason(Growth.today(now))) {
                        Text("Jusqu'au 24 décembre, chaque annonce remplie dans Vinted intégré mentionne « idée cadeau pour Noël ».", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 2. Prix
        item { Text("2. Prix : ce qui part trop vite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (prices.isEmpty()) {
            item { Text("Aucun modèle vendu au moins 3 fois en 3 jours ou moins : pas de hausse à conseiller pour l'instant.", style = MaterialTheme.typography.bodySmall) }
        } else {
            item {
                Text(
                    "Ces modèles se vendent en moins de 3 jours : une hausse de 1 € (10 % au-delà de 10 €) a de bonnes chances de passer. " +
                        "Le nouveau prix s'applique aux brouillons ; pour une annonce déjà en ligne, change-le dans Vinted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(prices, key = { it.model }) { p ->
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(
                            "${p.sold} ventes en ${String.format(Locale.FRANCE, "%.0f", p.medianDays)} j (médiane) à ${Money.formatEuros(p.medianPriceCents)} → " +
                                "conseillé ${Money.formatEuros(p.suggestedCents)} (+${Money.formatEuros(p.gainPerSaleCents)} par vente)",
                            style = MaterialTheme.typography.labelMedium, color = GREEN,
                        )
                        if (p.draftIds.isNotEmpty()) {
                            TextButton(onClick = {
                                scope.launch {
                                    p.draftIds.forEach { repo.setPrice(it, p.suggestedCents) }
                                    onMessage("${p.draftIds.size} brouillon(s) passé(s) à ${Money.formatEuros(p.suggestedCents)}.")
                                }
                            }) { Text("Appliquer aux ${p.draftIds.size} brouillon(s)") }
                        }
                    }
                }
            }
        }

        // 3. Rachat
        item { Text("3. Racheter ce qui se vend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (plan.isEmpty()) {
            item { Text("Rien à recommander : le stock couvre le rythme de vente des 60 derniers jours.", style = MaterialTheme.typography.bodySmall) }
        } else {
            item {
                Text(
                    "Pour tenir 30 jours de ventes après un délai fournisseur d'environ 3 semaines : budget " +
                        "${Money.formatEuros(plan.sumOf { it.budgetCents })} (hors port) pour un bénéfice attendu de " +
                        "${Money.formatEuros(plan.sumOf { it.expectedProfitCents })}.",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                )
            }
            items(plan, key = { it.productKey }) { l ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoThumb(l.imageFile, size = 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(l.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(
                            "Commander ${l.quantity} • ${String.format(Locale.FRANCE, "%.1f", l.perMonth)} vente(s)/mois • dispo ${l.available} • " +
                                "×${String.format(Locale.FRANCE, "%.0f", l.multiple)} la mise",
                            style = MaterialTheme.typography.labelSmall, color = AMBER, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            item { OutlinedButton(onClick = onOpenSupplier, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir Nihaojewelry") } }
        }

        // 4. Relances
        item { Text("4. Relancer ce qui dort", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (stagnant.isEmpty()) {
            item { Text("Aucune annonce ne stagne.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(stagnant, key = { it.article.id }) { st ->
                val a = st.article
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PhotoThumb(a.photos.firstOrNull(), size = 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(a.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(
                                    "${st.daysOnline} j en ligne • ${a.favoritesCount ?: 0} favori(s) • ${a.viewsCount ?: 0} vue(s) • ${Money.formatEuros(a.priceCents)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Text(
                            when {
                                (a.favoritesCount ?: 0) > 0 -> "Des gens l'ont en favori : envoie-leur une offre (message prêt ci-dessous)."
                                (a.viewsCount ?: 0) >= 30 -> "Beaucoup de vues, aucune vente : la photo principale ou le prix bloque."
                                else -> "Peu vu : republie-le avec une nouvelle photo principale."
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row {
                            if ((a.favoritesCount ?: 0) > 0) {
                                TextButton(onClick = { copyToClipboard(context, "Message", Growth.favoriteOffer(a)) }) { Text("Copier l'offre") }
                            }
                            if (a.photos.size > 1) {
                                TextButton(onClick = {
                                    scope.launch { if (repo.rotateMainPhoto(a.id)) onMessage("Nouvelle photo principale : republie l'annonce pour qu'elle compte.") }
                                }) { Text("Changer la photo") }
                            }
                            TextButton(onClick = { onOpenArticle(a.id) }) { Text("Fiche") }
                        }
                    }
                }
            }
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}
