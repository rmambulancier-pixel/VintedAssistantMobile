package fr.vintedassistant.mobile.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import fr.vintedassistant.mobile.MainActivity
import fr.vintedassistant.mobile.data.Backup
import fr.vintedassistant.mobile.reminders.AutoBackup
import fr.vintedassistant.mobile.ui.SectionTitle
import fr.vintedassistant.mobile.ui.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Réglages : sauvegarde complète, envoi vers Drive ou e-mail, restauration. */
@Composable
fun BackupSection(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf(AutoBackup.last(context)) }
    var prepared by remember { mutableStateOf<Backup.Info?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.FRANCE) }

    fun task(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "Fichier refusé."
            } catch (e: Exception) {
                error = "Opération impossible : ${e.message ?: e.javaClass.simpleName}."
            } finally {
                busy = null
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task("Lecture…") {
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Fichier illisible.")
            prepared = input.use { context.container().backup.prepare(it) }
        }
    }

    SectionTitle("Sauvegarde")
    Text(
        last?.let { (at, name) -> "Dernière sauvegarde : ${format.format(Date(at))}, dans $name." }
            ?: "Aucune sauvegarde pour l'instant. Une sauvegarde automatique est faite chaque semaine.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        enabled = busy == null,
        onClick = {
            task("Sauvegarde…") {
                val name = context.container().backup.saveToDownloads(appVersion(context))
                AutoBackup.remember(context, name)
                last = AutoBackup.last(context)
                onMessage("Sauvegarde enregistrée dans $name.")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(busy ?: "Sauvegarder maintenant") }
    OutlinedButton(
        enabled = busy == null,
        onClick = {
            task("Préparation…") {
                val file = context.container().backup.saveToCache(appVersion(context))
                context.startActivity(context.container().dataExport.shareIntent(file))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Envoyer une sauvegarde (Drive, e-mail…)") }
    OutlinedButton(
        enabled = busy == null,
        onClick = { pick.launch(arrayOf("*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Restaurer une sauvegarde") }
    Text(
        "La sauvegarde contient tout : fiches, photos, achats, ventes, bordereaux et réglages. " +
            "Le dossier Téléchargements/VintedAssistant reste sur le téléphone même si l'application est désinstallée ; " +
            "pour changer de téléphone, envoie une sauvegarde sur ton Drive.",
        style = MaterialTheme.typography.bodySmall,
    )

    prepared?.let { info ->
        AlertDialog(
            onDismissRequest = { prepared = null; context.container().backup.discardPrepared() },
            title = { Text("Restaurer cette sauvegarde ?") },
            text = {
                Text(
                    "Sauvegarde du ${format.format(Date(info.createdAt))} (version ${info.appVersion}) : " +
                        "${info.articles} fiche(s), ${info.sales} vente(s), ${info.orders} commande(s), ${info.files} photo(s) et bordereau(x).\n\n" +
                        "Toutes les données actuelles seront remplacées. Une copie de sécurité de l'état actuel est enregistrée " +
                        "avant, dans Téléchargements/VintedAssistant. L'application redémarre ensuite.",
                )
            },
            confirmButton = {
                TextButton(enabled = busy == null, onClick = {
                    task("Restauration…") {
                        val backup = context.container().backup
                        backup.saveToDownloads(appVersion(context), prefix = "avant-restauration")
                        backup.restorePrepared()
                        restartApp(context)
                    }
                }) { Text(busy ?: "Restaurer") }
            },
            dismissButton = {
                TextButton(onClick = { prepared = null; context.container().backup.discardPrepared() }) { Text("Annuler") }
            },
        )
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Sauvegarde") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

/** Relance l'application sur une base neuve (après restauration, la base actuelle est fermée). */
fun restartApp(context: Context) {
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
