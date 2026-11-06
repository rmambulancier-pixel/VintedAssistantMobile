@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.Negotiator
import fr.vintedassistant.mobile.domain.OfferDecision
import fr.vintedassistant.mobile.domain.PriceAdvisor
import fr.vintedassistant.mobile.domain.ReplyTemplates
import fr.vintedassistant.mobile.ui.Field
import fr.vintedassistant.mobile.ui.container
import fr.vintedassistant.mobile.ui.copyToClipboard
import kotlinx.coroutines.launch

private val TABS = listOf("Répondre", "Négocier", "Favoris")

@Composable
fun AssistantScreen(
    articles: List<Article>?,
    aiEnabled: Boolean,
    priceDropPercent: Int,
    modifier: Modifier,
    initialArticleId: Long? = null,
    initialTab: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(initialTab, initialArticleId) { mutableStateOf(initialTab) }
    var selectedId by rememberSaveable(initialArticleId) { mutableStateOf(initialArticleId) }
    var menu by remember { mutableStateOf(false) }

    val candidates = articles.orEmpty().filter { it.status.inStock }
    val article = candidates.firstOrNull { it.id == selectedId }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryTabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(article?.let { "${it.title} • ${Money.formatEuros(it.priceCents)}" } ?: "Choisir l'article concerné")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (candidates.isEmpty()) DropdownMenuItem(text = { Text("Aucun article en stock") }, onClick = { menu = false })
                candidates.forEach { a ->
                    DropdownMenuItem(text = { Text(a.title) }, onClick = { selectedId = a.id; menu = false })
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                "L'assistant prépare le texte, tu le copies et tu l'envoies toi-même dans Vinted. " +
                    "Rien n'est envoyé automatiquement, et pas de message en masse.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (article == null) {
            Text("Choisis d'abord un article.", style = MaterialTheme.typography.bodyMedium)
        } else when (tab) {
            0 -> {
                var buyerMessage by rememberSaveable(article.id) { mutableStateOf("") }
                var reply by rememberSaveable(article.id) { mutableStateOf("") }
                var note by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
                var working by remember { mutableStateOf(false) }
                Field("Message de l'acheteur (colle-le ici)", buyerMessage, { buyerMessage = it }, singleLine = false, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { reply = ReplyTemplates.reply(buyerMessage, article); note = null }, modifier = Modifier.weight(1f)) {
                        Text("Réponse (modèle)")
                    }
                    Button(
                        onClick = {
                            working = true
                            scope.launch {
                                val result = context.container().assistant.reply(article, buyerMessage, aiEnabled)
                                reply = result.value
                                note = result.note ?: if (!result.usedAi) "IA non disponible : modèle utilisé." else null
                                working = false
                            }
                        },
                        enabled = aiEnabled && !working && buyerMessage.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Réponse IA") }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (reply.isNotEmpty()) {
                    Field("Réponse proposée (modifiable)", reply, { reply = it }, singleLine = false, minLines = 3)
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { copyToClipboard(context, "Réponse", reply) }, modifier = Modifier.fillMaxWidth()) { Text("Copier la réponse") }
                }
            }
            1 -> {
                var offer by rememberSaveable(article.id) { mutableStateOf("") }
                val offerCents = Money.parseEuros(offer)
                Field("Offre reçue (€)", offer, { offer = it }, decimal = true)
                Text(
                    "Prix affiché ${Money.formatEuros(article.priceCents)} • plancher " +
                        (article.floorPriceCents?.let { Money.formatEuros(it) }
                            ?: "non défini (${Negotiator.DEFAULT_FLOOR_PERCENT} % du prix utilisé)"),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (offerCents != null) {
                    val advice = Negotiator.advise(article.priceCents, article.floorPriceCents, offerCents)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (advice.decision == OfferDecision.ACCEPT) "Accepter" else "Contre-proposer ${Money.formatEuros(advice.amountCents)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(advice.explanation, style = MaterialTheme.typography.bodySmall)
                            Text("Marge si vendu à ce prix : ${Money.formatEuros(advice.amountCents - article.costCents)}", style = MaterialTheme.typography.bodySmall)
                            Text(advice.messageToBuyer)
                            Button(onClick = { copyToClipboard(context, "Message", advice.messageToBuyer) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Copier le message")
                            }
                        }
                    }
                }
            }
            else -> {
                val suggested = PriceAdvisor.suggestDrop(article.priceCents, article.floorPriceCents, priceDropPercent)
                var price by rememberSaveable(article.id) { mutableStateOf(suggested?.let { Money.formatPlain(it) } ?: "") }
                Text(
                    "Pour les personnes qui ont mis l'article en favori. Vinted ne permet pas de savoir qui " +
                        "depuis une autre application : envoie le message depuis la conversation dans Vinted.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Field("Prix proposé (€), laisse vide pour un simple message", price, { price = it }, decimal = true)
                val message = ReplyTemplates.favoriteMessage(article, Money.parseEuros(price))
                Card(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(12.dp)) }
                Button(onClick = { copyToClipboard(context, "Message", message) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Copier le message")
                }
            }
        }
    }
}
