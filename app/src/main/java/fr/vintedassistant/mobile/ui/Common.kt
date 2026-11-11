package fr.vintedassistant.mobile.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.AppContainer
import fr.vintedassistant.mobile.VintedBoostApp

fun Context.container(): AppContainer = (applicationContext as VintedBoostApp).container

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copié", Toast.LENGTH_SHORT).show()
}

/** Ouvre le menu de partage Android avec les photos (choisir Vinted pour créer l'annonce). */
fun sharePhotos(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) {
        Toast.makeText(context, "Aucune photo à partager", Toast.LENGTH_SHORT).show()
        return
    }
    shareFiles(context, uris, "image/jpeg", "Envoyer les photos vers…")
}

fun shareFiles(context: Context, uris: List<Uri>, mimeType: String, title: String) {
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = mimeType
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // ClipData : nécessaire pour que l'autorisation de lecture couvre toutes les photos.
    val clip = ClipData.newRawUri("photos", uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    try {
        context.startActivity(Intent.createChooser(intent, title))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Aucune application pour partager", Toast.LENGTH_SHORT).show()
    }
}

fun openFile(context: Context, uri: Uri, mimeType: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Aucune application pour ouvrir ce fichier", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun PhotoThumb(fileName: String?, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, fileName) {
        value = fileName?.let { context.container().photoStore.thumbnail(it, 320) }
    }
    val shape = RoundedCornerShape(8.dp)
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (fileName == null) "Pas de\nphoto" else "…", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Première photo lisible parmi plusieurs candidates (une photo peut avoir été supprimée avec sa fiche).
 * Sans photo : un carré avec l'initiale, pas de « … ».
 */
@Composable
fun PhotoThumbAny(names: List<String>, fallback: String, size: Dp = 52.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, names) {
        val store = context.container().photoStore
        value = names.firstNotNullOfOrNull { store.thumbnail(it, 320) }
    }
    val shape = RoundedCornerShape(8.dp)
    val current = bitmap
    if (current != null) {
        Image(current.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(shape))
    } else {
        Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(fallback.trim().take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp))
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun StatRow(vararg cards: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f)) }
    }
}

@Composable
fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(message)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = when {
            decimal -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            number -> KeyboardOptions(keyboardType = KeyboardType.Number)
            else -> KeyboardOptions.Default
        },
        modifier = modifier.fillMaxWidth(),
    )
}

fun daysLabel(days: Int): String = if (days <= 1) "$days jour" else "$days jours"
