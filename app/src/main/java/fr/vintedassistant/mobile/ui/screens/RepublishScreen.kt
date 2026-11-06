package fr.vintedassistant.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.data.AppSettings
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.DAY_MS
import fr.vintedassistant.mobile.domain.ListingTemplates
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.RepublishPlan
import fr.vintedassistant.mobile.domain.StagnantArticle
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.ui.SectionTitle
import fr.vintedassistant.mobile.ui.StatRow
import fr.vintedassistant.mobile.ui.container
import fr.vintedassistant.mobile.ui.copyToClipboard
import fr.vintedassistant.mobile.ui.daysLabel
import fr.vintedassistant.mobile.ui.sharePhotos

@Composable
fun RepublishScreen(
    plan: RepublishPlan?,
    stagnant: List<StagnantArticle>,
    settings: AppSettings,
    modifier: Modifier,
    onRepublished: (Long) -> Unit,
    onApplyPrice: (Long, Long) -> Unit,
    onOpenArticle: (Long) -> Unit,
    onOpenInVinted: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (plan == null) {
            item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            return@LazyColumn
        }
        item {
            StatRow(
                "À faire" to plan.today.size.toString(),
                "Fait aujourd'hui" to "${plan.doneToday} / ${settings.republishMaxPerDay}",
                "Trop récents" to plan.waiting.toString(),
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    "Republication assistée : l'application prépare, tu publies. Pour chaque article : " +
                        "1. « Préparer » copie le texte et ouvre le partage des photos (choisis Vinted). " +
                        "2. Dans Vinted, colle le texte, publie la nouvelle annonce, puis supprime l'ancienne. " +
                        "3. Reviens ici et appuie sur « C'est republié ». " +
                        "Règles actuelles : annonces en ligne depuis au moins ${daysLabel(settings.republishMinAgeDays)}, " +
                        "${settings.republishMaxPerDay} par jour au maximum.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        item { SectionTitle("Plan du jour") }
        if (plan.today.isEmpty()) {
            item {
                Text(
                    if (plan.remainingQuota == 0) "Quota du jour atteint. Bravo !" else "Rien à republier aujourd'hui.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(plan.today, key = { "plan-${it.id}" }) { article ->
            PlanCard(
                article = article,
                onPrepare = {
                    val draft = ListingTemplates.draft(article)
                    val description = article.description.ifBlank { draft.description }
                    val tags = article.keywords.ifBlank { draft.keywords }
                    val text = buildString {
                        append(article.title)
                        append("\n\n")
                        append(description)
                        if (tags.isNotBlank() && !description.contains(tags)) append("\n\n").append(tags)
                    }
                    copyToClipboard(context, "Titre et description", text)
                    val store = context.container().photoStore
                    sharePhotos(context, article.photos.map { store.shareUri(it) })
                },
                onDone = { onRepublished(article.id) },
                onOpen = { onOpenArticle(article.id) },
                onBeta = if (settings.betaVintedAccepted) ({ onOpenInVinted(article.id) }) else null,
            )
        }

        item { SectionTitle("Baisses de prix suggérées") }
        val drops = stagnant.filter { it.suggestedPriceCents != null }
        if (drops.isEmpty()) {
            item { Text("Aucune baisse suggérée pour le moment.", style = MaterialTheme.typography.bodySmall) }
        } else {
            item {
                Text(
                    "Articles en ligne depuis plus de ${daysLabel(settings.stagnantDays)} : baisse de ${settings.priceDropPercent} %, " +
                        "jamais sous ton prix plancher. Applique ici, puis modifie le prix dans Vinted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(drops, key = { "drop-${it.article.id}" }) { s ->
                ArticleRow(s.article, trailing = {
                    OutlinedButton(onClick = { onApplyPrice(s.article.id, s.suggestedPriceCents!!) }) {
                        Text("→ ${Money.formatEuros(s.suggestedPriceCents!!)}")
                    }
                }) { onOpenArticle(s.article.id) }
            }
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun PlanCard(article: Article, onPrepare: () -> Unit, onDone: () -> Unit, onOpen: () -> Unit, onBeta: (() -> Unit)?) {
    val since = article.lastVisibleAt?.let { ((System.currentTimeMillis() - it) / DAY_MS).toInt() }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhotoThumb(article.photos.firstOrNull())
                Column(Modifier.weight(1f)) {
                    Text(article.title, style = MaterialTheme.typography.titleSmall)
                    Text(Money.formatEuros(article.priceCents), style = MaterialTheme.typography.bodyMedium)
                    since?.let { Text("Dernière mise en ligne il y a ${daysLabel(it)}", style = MaterialTheme.typography.bodySmall) }
                    if (article.photos.isEmpty()) {
                        Text("Pas de photo enregistrée ici.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Fiche") }
                OutlinedButton(onClick = onPrepare, modifier = Modifier.weight(1f)) { Text("Préparer") }
                Button(onClick = onDone, modifier = Modifier.weight(1.3f)) { Text("C'est republié") }
            }
            if (onBeta != null) {
                OutlinedButton(onClick = onBeta, modifier = Modifier.fillMaxWidth()) { Text("Republier dans Vinted intégré (bêta)") }
            }
        }
    }
}
