package fr.vintedassistant.mobile.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ExpressListing
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.ui.Field
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.ui.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/**
 * Mise en vente express : les fiches préparées défilent une par une. Pour chacune : photos du vrai bijou
 * (appareil photo ou galerie), titre et prix à vérifier, texte par l'IA si besoin. À la fin, toutes les fiches
 * prêtes partent dans Vinted intégré, où l'utilisateur touche « Ajouter » pour chacune.
 */
@Composable
fun ExpressScreen(
    ids: List<Long>,
    articles: List<Article>?,
    aiEnabled: Boolean,
    modifier: Modifier,
    onPublish: (List<Long>) -> Unit,
    onOpenArticle: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var pendingShot by rememberSaveable { mutableStateOf<String?>(null) }
    val byId = articles.orEmpty().associateBy { it.id }
    val drafts = ids.mapNotNull { byId[it] }
    val ready = drafts.filter { it.photos.isNotEmpty() && it.priceCents > 0 && it.status == fr.vintedassistant.mobile.domain.ArticleStatus.DRAFT }

    if (index >= ids.size) {
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Préparation terminée", style = MaterialTheme.typography.titleLarge)
            Text("${ready.size} fiche(s) prête(s) sur ${ids.size} : photos et prix renseignés.")
            val notReady = drafts.filter { it !in ready }
            if (notReady.isNotEmpty()) {
                Text("À compléter (restent en brouillon dans Stock) :", style = MaterialTheme.typography.bodySmall)
                notReady.forEach { a ->
                    TextButton(onClick = { onOpenArticle(a.id) }) {
                        Text("${a.title.take(60)} : " + listOfNotNull(if (a.photos.isEmpty()) "photos" else null, if (a.priceCents <= 0) "prix" else null).joinToString(", "))
                    }
                }
            }
            Button(onClick = { onPublish(ready.map { it.id }) }, enabled = ready.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Publier les ${ready.size} fiche(s) dans Vinted intégré")
            }
            Text(
                "L'application remplit chaque annonce ; tu vérifies et tu touches « Ajouter » ; elle relie l'annonce à la fiche et passe à la suivante.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { index = 0 }) { Text("Revoir les fiches") }
        }
        return
    }

    val article = byId[ids[index]]
    if (article == null) {
        Column(modifier.padding(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        return
    }
    var title by remember(article.id) { mutableStateOf(article.title) }
    var price by remember(article.id) { mutableStateOf(if (article.priceCents > 0) Money.formatPlain(article.priceCents) else "") }
    var description by remember(article.id) { mutableStateOf<String?>(null) }
    var keywords by remember(article.id) { mutableStateOf<String?>(null) }

    fun task(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { note = "$label impossible : ${e.message ?: e.javaClass.simpleName}." } finally { busy = null }
        }
    }

    suspend fun save() {
        context.container().articleRepository.quickEdit(article.id, title, Money.parseEuros(price) ?: 0L, description, keywords)
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingShot
        pendingShot = null
        if (ok && path != null) task("Ajout de la photo") {
            val file = File(path)
            context.container().articleRepository.addPhotos(article.id, listOf(Uri.fromFile(file)))
            file.delete()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        if (uris.isNotEmpty()) task("Ajout des photos") { context.container().articleRepository.addPhotos(article.id, uris) }
    }

    Column(modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pièce ${index + 1} sur ${ids.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhotoThumb(article.photos.firstOrNull(), size = 72.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (article.photos.isEmpty()) "Aucune photo : prends le bijou en photo." else "${article.photos.size} photo(s)", style = MaterialTheme.typography.bodyMedium)
                    Text("Coût d'achat ${Money.formatEuros(article.costCents)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = busy == null, onClick = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                pendingShot = file.path
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
            }) { Text("Prendre une photo") }
            OutlinedButton(enabled = busy == null, onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Galerie") }
            OutlinedButton(enabled = busy == null && article.photos.isNotEmpty(), onClick = {
                task("Amélioration") {
                    val repo = context.container().articleRepository
                    val first = article.photos.first()
                    repo.enhancePhoto(article.id, first, context.container().photoEnhancer)
                    repo.removePhoto(article.id, first)
                }
            }) { Text("Améliorer la 1re") }
        }
        Field("Titre", title, { title = it })
        ExpressListing.variant(article.title).takeIf { it.isNotBlank() }?.let {
            Text("Variante achetée : $it. Vérifie que le titre et la couleur correspondent.", style = MaterialTheme.typography.bodySmall)
        }
        Field("Prix (€)", price, { price = it }, decimal = true)
        if (aiEnabled) {
            OutlinedButton(enabled = busy == null, onClick = {
                task("Rédaction") {
                    val photo = article.photos.firstOrNull()?.let { context.container().photoStore.thumbnail(it, 1024) }
                    val result = context.container().assistant.draftListing(article.copy(title = title), photo, true)
                    title = result.value.title
                    description = result.value.description
                    keywords = result.value.keywords
                    note = result.note ?: "Texte rédigé : relis-le."
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Rédiger titre et description avec l'IA") }
        }
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        busy?.let { Text("$it…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Button(enabled = busy == null, onClick = {
            task("Enregistrement") { save(); note = null; index++ }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (index + 1 < ids.size) "Enregistrer et passer à la suivante" else "Enregistrer et terminer") }
        Text("Sans photo ou sans prix, la fiche reste en brouillon et n'est pas publiée.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onOpenArticle(article.id) }) { Text("Ouvrir la fiche complète") }
        Text(" ", Modifier.padding(bottom = 24.dp))
    }
}
