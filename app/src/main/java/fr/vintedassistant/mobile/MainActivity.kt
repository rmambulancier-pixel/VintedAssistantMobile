package fr.vintedassistant.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.vintedassistant.mobile.domain.VintedLinks
import fr.vintedassistant.mobile.reminders.RepublishReminder
import fr.vintedassistant.mobile.ui.AppRoot
import fr.vintedassistant.mobile.ui.Tab

class MainActivity : ComponentActivity() {
    private var initialTab by mutableStateOf(Tab.HOME)
    private var sharedLink by mutableStateOf<String?>(null)
    /** Incrémenté à chaque demande d'ouverture d'onglet (notification reçue application ouverte). */
    private var tabRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) applyIntent(intent)
        setContent {
            MaterialTheme {
                AppRoot(initialTab = initialTab, tabRequest = tabRequest, sharedLink = sharedLink, onSharedLinkHandled = { sharedLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        if (intent == null) return
        // La notification de rappel ouvre directement l'onglet Republier.
        if (intent.getStringExtra(RepublishReminder.EXTRA_OPEN_TAB) == RepublishReminder.TAB_REPUBLISH) {
            initialTab = Tab.REPUBLISH
            tabRequest++
        }
        // « Partager » depuis l'application Vinted : on propose d'associer le lien à un article.
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { VintedLinks.extract(it) }
        }
    }
}
