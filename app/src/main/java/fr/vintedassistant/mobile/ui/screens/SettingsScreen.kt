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

@Composable
fun SettingsScreen(
    settings: AppSettings,
    aiStatus: AiAvailability?,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onCheckAi: () -> Unit,
    onDownloadAi: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
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
            "Vinted Assistant 0.3.0. Toutes les données restent sur ce téléphone. " +
                "L'application ne se connecte pas à Vinted et n'y agit jamais à ta place : " +
                "elle prépare, tu publies.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(32.dp))
    }
}
