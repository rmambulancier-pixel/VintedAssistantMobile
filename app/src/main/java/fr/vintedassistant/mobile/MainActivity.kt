package fr.vintedassistant.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.vintedassistant.mobile.reminders.RepublishReminder
import fr.vintedassistant.mobile.ui.AppRoot
import fr.vintedassistant.mobile.ui.Tab

class MainActivity : ComponentActivity() {
    private var initialTab by mutableStateOf(Tab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) applyIntent(intent)
        setContent { MaterialTheme { AppRoot(initialTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    /** La notification de rappel ouvre directement l'onglet Republier. */
    private fun applyIntent(intent: Intent?) {
        if (intent?.getStringExtra(RepublishReminder.EXTRA_OPEN_TAB) == RepublishReminder.TAB_REPUBLISH) {
            initialTab = Tab.REPUBLISH
        }
    }
}
