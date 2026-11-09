package fr.vintedassistant.mobile.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import fr.vintedassistant.mobile.VintedBoostApp
import fr.vintedassistant.mobile.domain.VINTED_PACKAGE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lit les notifications que l'application Vinted affiche déjà sur ce téléphone
 * (ventes, offres, messages, favoris). Toutes les autres applications sont ignorées.
 * Rien n'est envoyé nulle part, rien n'est fait dans Vinted : l'information reste locale.
 */
class VintedNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != VINTED_PACKAGE) return
        val notification = sbn.notification ?: return
        // Les résumés de groupe répètent les notifications individuelles.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val key = "${sbn.key}|${sbn.postTime}"
        val container = (applicationContext as VintedBoostApp).container
        val repository = container.articleRepository
        scope.launch {
            try {
                val handled = repository.processNotification(key, sbn.postTime, title, text, container.settingsStore.current().priceDropPercent)
                handled.sale?.let { SaleNotifier.notify(applicationContext, it) }
                handled.reply?.let { ReplyNotifier.notify(applicationContext, it) }
            } catch (e: Exception) {
                // Une notification perdue n'est pas grave ; on ne fait jamais planter le service.
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Écran système où l'utilisateur autorise l'accès aux notifications. */
        fun settingsIntent(context: Context): Intent {
            val component = ComponentName(context, VintedNotificationListener::class.java)
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun fallbackSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Écran « Infos sur l'appli », pour « Autoriser les paramètres restreints » (APK installé hors Play Store). */
        fun appInfoIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
