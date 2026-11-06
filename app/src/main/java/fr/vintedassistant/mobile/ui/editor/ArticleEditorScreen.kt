@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.Conditions
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.ui.Field
import fr.vintedassistant.mobile.ui.MessageCard
import fr.vintedassistant.mobile.ui.PhotoThumb
import fr.vintedassistant.mobile.ui.SectionTitle
import fr.vintedassistant.mobile.ui.container
import fr.vintedassistant.mobile.ui.copyToClipboard
import fr.vintedassistant.mobile.ui.sharePhotos

@Composable
fun ArticleEditorScreen(articleId: Long, sessionKey: String, aiEnabled: Boolean, onClose: () -> Unit) {
    val vm: EditorViewModel = viewModel(key = sessionKey, factory = EditorViewModel.factory(articleId))
    val context = LocalContext.current
    val form by vm.form.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val article by vm.article.collectAsStateWithLifecycle()
    val id by vm.id.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()

    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var saleDialog by rememberSaveable { mutableStateOf(false) }
    val dirty = form != saved

    fun leave() {
        if (dirty) confirmLeave = true else onClose()
    }
    BackHandler { leave() }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        vm.addPhotos(uris)
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Modifications non enregistrées") },
            text = { Text("Quitter sans enregistrer ?") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onClose() }) { Text("Quitter") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Rester") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer l'article ?") },
            text = { Text("La fiche et ses photos seront supprimées. Les ventes déjà enregistrées sont conservées.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onClose) }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
    if (saleDialog) {
        SaleDialog(
            defaultPrice = form.price,
            onDismiss = { saleDialog = false },
            onConfirm = { price, fees, buyer ->
                val problem = vm.recordSale(price, fees, buyer)
                if (problem == null) saleDialog = false
                problem
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id > 0) "Fiche article" else "Nouvel article") },
                navigationIcon = {
                    IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                actions = {
                    if (id > 0) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Supprimer") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            info?.let { MessageCard(it, vm::dismissInfo) }

            val current = article
            if (current != null) {
                Text("Statut", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ArticleStatus.entries.filter { it != ArticleStatus.SOLD }) { status ->
                        FilterChip(
                            selected = current.status == status,
                            onClick = { vm.setStatus(status) },
                            label = { Text(status.label) },
                        )
                    }
                }
                if (current.status == ArticleStatus.SOLD) Text("Vendu : voir l'onglet Ventes.", color = MaterialTheme.colorScheme.primary)
            }

            SectionTitle("Photos")
            if (current == null || current.photos.isEmpty()) {
                Text("Aucune photo.", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(current.photos, key = { it }) { name ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PhotoThumb(name, size = 96.dp)
                            TextButton(onClick = { vm.removePhoto(name) }) { Text("Retirer") }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = id > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (id > 0) "Ajouter des photos" else "Enregistre la fiche pour ajouter des photos") }

            SectionTitle("Article")
            Field("Nom de l'article *", form.title, { v -> vm.edit { it.copy(title = v) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Coût d'achat (€) *", form.cost, { v -> vm.edit { it.copy(cost = v) } }, Modifier.weight(1f), decimal = true)
                Field("Prix affiché (€) *", form.price, { v -> vm.edit { it.copy(price = v) } }, Modifier.weight(1f), decimal = true)
            }
            Field("Prix plancher (€), ton minimum en négociation", form.floorPrice, { v -> vm.edit { it.copy(floorPrice = v) } }, decimal = true)
            val cost = Money.parseEuros(form.cost)
            val price = Money.parseEuros(form.price)
            if (cost != null && price != null) {
                Text(
                    "Marge brute : ${Money.formatEuros(price - cost)}",
                    color = if (price >= cost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Marque", form.brand, { v -> vm.edit { it.copy(brand = v) } }, Modifier.weight(1f))
                Field("Taille", form.size, { v -> vm.edit { it.copy(size = v) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Catégorie", form.category, { v -> vm.edit { it.copy(category = v) } }, Modifier.weight(1f))
                Field("Couleur", form.color, { v -> vm.edit { it.copy(color = v) } }, Modifier.weight(1f))
            }
            Text("État", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Conditions.ALL) { c ->
                    FilterChip(
                        selected = form.condition == c,
                        onClick = { vm.edit { it.copy(condition = if (it.condition == c) "" else c) } },
                        label = { Text(c) },
                    )
                }
            }

            SectionTitle("Annonce Vinted")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.draft(useAi = false) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Rédiger (modèle)")
                }
                Button(onClick = { vm.draft(useAi = true) }, enabled = !busy && aiEnabled, modifier = Modifier.weight(1f)) {
                    Text("Rédiger avec l'IA")
                }
            }
            Field("Description", form.description, { v -> vm.edit { it.copy(description = v) } }, singleLine = false, minLines = 5)
            Field("Mots-clés", form.keywords, { v -> vm.edit { it.copy(keywords = v) } }, singleLine = false)

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.save() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (dirty) "Enregistrer la fiche" else "Fiche enregistrée")
            }

            if (id > 0) {
                SectionTitle("Publier sur Vinted (à la main)")
                Text(
                    "1. Copie le titre et la description. 2. Envoie les photos vers Vinted. " +
                        "3. Colle les textes, vérifie, publie. 4. Reviens ici et valide.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyToClipboard(context, "Titre", form.title.trim()) }, modifier = Modifier.weight(1f)) {
                        Text("Copier le titre")
                    }
                    OutlinedButton(onClick = { copyToClipboard(context, "Description", vm.fullDescription()) }, modifier = Modifier.weight(1f)) {
                        Text("Copier la description")
                    }
                }
                OutlinedButton(
                    onClick = {
                        val store = context.container().photoStore
                        sharePhotos(context, current?.photos.orEmpty().map { store.shareUri(it) })
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Envoyer les photos vers Vinted") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.markPublished() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("C'est publié")
                    }
                    OutlinedButton(onClick = { vm.markRepublished() }, enabled = !busy && current?.firstPublishedAt != null, modifier = Modifier.weight(1f)) {
                        Text("C'est republié")
                    }
                }

                if (current != null && current.status != ArticleStatus.SOLD) {
                    SectionTitle("Vente")
                    Button(onClick = { saleDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Marquer comme vendu") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SaleDialog(defaultPrice: String, onDismiss: () -> Unit, onConfirm: (String, String, String) -> String?) {
    var price by rememberSaveable { mutableStateOf(defaultPrice) }
    var fees by rememberSaveable { mutableStateOf("") }
    var buyer by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enregistrer la vente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Prix de vente final (€)", price, { price = it }, decimal = true)
                Field("Frais à ta charge (€) : boost, emballage…", fees, { fees = it }, decimal = true)
                Field("Pseudo de l'acheteur (facultatif)", buyer, { buyer = it })
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { problem = onConfirm(price, fees, buyer) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
