package fr.vintedassistant.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.setValue
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.VINTED_PACKAGE
import fr.vintedassistant.mobile.domain.VintedEventKind
import fr.vintedassistant.mobile.domain.VintedLinks
import fr.vintedassistant.mobile.domain.VintedNotice
import fr.vintedassistant.mobile.notifications.VintedNotificationListener
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Ouvre l'application Vinted, sur l'annonce si son lien est connu. */
fun openVinted(context: Context, url: String? = null) {
    val intent = if (url != null && VintedLinks.isVintedUrl(url)) {
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
    } else {
        context.packageManager.getLaunchIntentForPackage(VINTED_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.vinted.fr/"))
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Impossible d'ouvrir Vinted", Toast.LENGTH_SHORT).show()
    }
}

fun openNotificationAccess(context: Context) {
    val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) VintedNotificationListener.settingsIntent(context)
    else VintedNotificationListener.fallbackSettingsIntent()
    try {
        context.startActivity(primary)
    } catch (e: Exception) {
        context.startActivity(VintedNotificationListener.fallbackSettingsIntent())
    }
}

@Composable
fun ActivityScreen(
    notices: List<VintedNotice>,
    articles: List<Article>?,
    modifier: Modifier,
    onHandled: (Long) -> Unit,
    onAllHandled: () -> Unit,
    onOpenArticle: (Long) -> Unit,
    onAssist: (Long, Int) -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(VintedNotificationListener.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = VintedNotificationListener.isEnabled(context)
        onPauseOrDispose { }
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.FRANCE) }
    val byId = articles.orEmpty().associateBy { it.id }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!enabled) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Lecture des notifications Vinted désactivée", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Autorise l'accès aux notifications : l'application ne garde que celles de Vinted " +
                                "(ventes, offres, messages, favoris), sur ce téléphone uniquement, 60 jours maximum.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Si Android affiche « paramètre restreint » : ouvre Infos sur l'appli, menu ⋮, " +
                                "« Autoriser les paramètres restreints », puis réessaie.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openNotificationAccess(context) }) { Text("Autoriser") }
                            OutlinedButton(onClick = {
                                context.startActivity(VintedNotificationListener.appInfoIntent(context))
                            }) { Text("Infos sur l'appli") }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openVinted(context) }, modifier = Modifier.weight(1f)) { Text("Ouvrir Vinted") }
                OutlinedButton(onClick = onAllHandled, enabled = notices.any { !it.handled }, modifier = Modifier.weight(1f)) {
                    Text("Tout marquer vu")
                }
            }
        }
        if (notices.isEmpty()) {
            item {
                Text(
                    if (enabled) "Aucune notification Vinted reçue depuis l'activation." else "Rien pour l'instant.",
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(notices, key = { it.id }) { notice ->
            val article = notice.articleId?.let { byId[it] }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${notice.kind.label} • ${dateFormat.format(Date(notice.postedAt))}" + if (!notice.handled) " • nouveau" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (notice.handled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    )
                    if (notice.title.isNotBlank()) Text(notice.title, style = MaterialTheme.typography.titleSmall)
                    if (notice.text.isNotBlank()) Text(notice.text, style = MaterialTheme.typography.bodyMedium)
                    article?.let { Text("Article reconnu : ${it.title} (${Money.formatEuros(it.priceCents)})", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (article != null) {
                            when (notice.kind) {
                                VintedEventKind.SALE -> TextButton(onClick = { onHandled(notice.id); onOpenArticle(article.id) }) { Text("Enregistrer la vente") }
                                VintedEventKind.OFFER -> TextButton(onClick = { onHandled(notice.id); onAssist(article.id, 1) }) { Text("Conseil d'offre") }
                                VintedEventKind.MESSAGE -> TextButton(onClick = { onHandled(notice.id); onAssist(article.id, 0) }) { Text("Préparer la réponse") }
                                VintedEventKind.FAVORITE -> TextButton(onClick = { onHandled(notice.id); onAssist(article.id, 2) }) { Text("Message favori") }
                                VintedEventKind.OTHER -> TextButton(onClick = { onHandled(notice.id); onOpenArticle(article.id) }) { Text("Fiche") }
                            }
                        }
                        TextButton(onClick = { onHandled(notice.id); openVinted(context, article?.vintedUrl) }) { Text("Vinted") }
                        if (!notice.handled) TextButton(onClick = { onHandled(notice.id) }) { Text("Vu") }
                    }
                }
            }
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}
