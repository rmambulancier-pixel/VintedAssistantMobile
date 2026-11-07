@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.ai.AiAvailability
import fr.vintedassistant.mobile.data.AppSettings
import fr.vintedassistant.mobile.domain.Finance
import fr.vintedassistant.mobile.reminders.RepublishReminder
import fr.vintedassistant.mobile.ui.Field
import fr.vintedassistant.mobile.ui.SectionTitle
import fr.vintedassistant.mobile.ui.copyToClipboard
import fr.vintedassistant.mobile.data.importer.ExportReport
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.notifications.VintedNotificationListener
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
fun SettingsScreen(
    settings: AppSettings,
    aiStatus: AiAvailability?,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onCheckAi: () -> Unit,
    onDownloadAi: () -> Unit,
    onMessage: (String) -> Unit,
    exportReport: ExportReport?,
    onPickExport: (Uri) -> Unit,
    onConfirmExport: () -> Unit,
    onDismissExport: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenBeta: () -> Unit,
) {
    val context = LocalContext.current
    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickExport(uri)
    }
    var notificationsOn by remember { mutableStateOf(VintedNotificationListener.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = VintedNotificationListener.isEnabled(context)
        onPauseOrDispose { }
    }
    exportReport?.let { report ->
        AlertDialog(
            onDismissRequest = onDismissExport,
            title = { Text("Export Vinted lu") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${report.candidates.size} article(s) reconnu(s) dans ${report.files.size} fichier(s).")
                    report.candidates.take(5).forEach { Text("• ${it.title}" + (it.priceCents?.let { p -> " (${Money.formatEuros(p)})" } ?: "")) }
                    Text(
                        "Les articles déjà présents (même nom, même prix) ne seront pas dupliqués. " +
                            "Le format de l'export n'est pas documenté par Vinted : si le résultat est faux, " +
                            "copie la structure et transmets-la pour adapter l'import.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { copyToClipboard(context, "Structure de l'export", report.structureText()) }) {
                        Text("Copier la structure (sans tes données)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmExport, enabled = report.candidates.isNotEmpty()) { Text("Importer") }
            },
            dismissButton = { TextButton(onClick = onDismissExport) { Text("Annuler") } },
        )
    }
    LaunchedEffect(Unit) { onCheckAi() }

    var rate by rememberSaveable { mutableStateOf(Finance.formatRate(settings.contributionRateBp).removeSuffix(" %")) }
    var minAge by rememberSaveable { mutableStateOf(settings.republishMinAgeDays.toString()) }
    var maxDay by rememberSaveable { mutableStateOf(settings.republishMaxPerDay.toString()) }
    var hour by rememberSaveable { mutableStateOf(settings.reminderHour.toString()) }
    var stagnant by rememberSaveable { mutableStateOf(settings.stagnantDays.toString()) }
    var drop by rememberSaveable { mutableStateOf(settings.priceDropPercent.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onUpdate { it.copy(reminderEnabled = true) }
            onMessage("Rappel activé.")
        } else {
            onMessage("Sans autorisation des notifications, le rappel ne peut pas s'afficher.")
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Vinted")
        Text(
            if (notificationsOn) "Lecture des notifications Vinted : activée." else "Lecture des notifications Vinted : désactivée.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openNotificationAccess(context) }, modifier = Modifier.weight(1f)) {
                Text(if (notificationsOn) "Gérer l'accès" else "Autoriser")
            }
            OutlinedButton(onClick = onOpenActivity, modifier = Modifier.weight(1f)) { Text("Activité Vinted") }
        }
        OutlinedButton(onClick = { pickExport.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Importer mon export de données Vinted (ZIP)")
        }
        Text(
            "Demande l'export sur vinted.fr : Mes paramètres, Paramètres de confidentialité, Gérer les données du compte. " +
                "Vinted l'envoie par e-mail, parfois après plusieurs jours.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onOpenBeta, modifier = Modifier.fillMaxWidth()) { Text("Vinted intégré (bêta)") }

        SectionTitle("Finances")
        Field("Taux de cotisations sociales (%)", rate, { rate = it }, decimal = true)
        Text(
            "0 pour un particulier. Micro-entrepreneur : saisis le taux applicable à ton activité (vérifie-le sur le site de l'Urssaf).",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Republication")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Âge minimal (jours)", minAge, { minAge = it }, Modifier.weight(1f), number = true)
            Field("Maximum par jour", maxDay, { maxDay = it }, Modifier.weight(1f), number = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Stagnant après (jours)", stagnant, { stagnant = it }, Modifier.weight(1f), number = true)
            Field("Baisse suggérée (%)", drop, { drop = it }, Modifier.weight(1f), number = true)
        }

        SectionTitle("Rappel quotidien")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Me rappeler les annonces à republier", Modifier.weight(1f))
            Switch(
                checked = settings.reminderEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        onUpdate { it.copy(reminderEnabled = false) }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !RepublishReminder.canNotify(context)) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onUpdate { it.copy(reminderEnabled = true) }
                    }
                },
            )
        }
        Field("Heure du rappel (0 à 23)", hour, { hour = it }, number = true)
        Text(
            "Android peut décaler un peu la notification pour économiser la batterie. Le rappel ne republie rien lui-même.",
            style = MaterialTheme.typography.bodySmall,
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val parsedRate = Finance.parseRatePercent(rate)
                val values = listOf(minAge, maxDay, hour, stagnant, drop).map { it.trim().toIntOrNull() }
                if (parsedRate == null || values.any { it == null }) {
                    error = "Vérifie les valeurs : nombres entiers, taux entre 0 et 100."
                } else {
                    error = null
                    onUpdate {
                        it.copy(
                            contributionRateBp = parsedRate,
                            republishMinAgeDays = values[0]!!,
                            republishMaxPerDay = values[1]!!,
                            reminderHour = values[2]!!,
                            stagnantDays = values[3]!!,
                            priceDropPercent = values[4]!!,
                        )
                    }
                    onMessage("Réglages enregistrés.")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enregistrer les réglages") }

        SectionTitle("Intelligence artificielle")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Utiliser l'IA sur le téléphone", Modifier.weight(1f))
            Switch(checked = settings.aiEnabled, onCheckedChange = { v -> onUpdate { it.copy(aiEnabled = v) } })
        }
        Text(aiStatus?.label ?: "Vérification…", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Gemini Nano tourne sur le téléphone : gratuit, hors ligne, rien n'est envoyé sur Internet. " +
                "API encore en bêta chez Google, validée officiellement en anglais : la qualité en français est à juger. " +
                "Sans IA, les modèles de texte prennent le relais.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckAi, modifier = Modifier.weight(1f)) { Text("Revérifier") }
            Button(onClick = onDownloadAi, enabled = aiStatus == AiAvailability.DOWNLOADABLE, modifier = Modifier.weight(1f)) {
                Text("Télécharger le modèle")
            }
        }

        SectionTitle("À propos")
        Text(
            "Vinted Assistant ${appVersion(context)}. Toutes les données restent sur ce téléphone. " +
                "L'application ne se connecte pas à ton compte Vinted et n'y agit jamais seule : " +
                "elle prépare, tu publies. Le module bêta n'agit que dans la page que tu ouvres, à ta demande.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** Version installée, lue dans le paquet : permet de vérifier quelle APK tourne sur le téléphone. */
fun appVersion(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
}
