package fr.vintedassistant.mobile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.vintedassistant.mobile.domain.QuickReply
import fr.vintedassistant.mobile.domain.VINTED_PACKAGE
import fr.vintedassistant.mobile.reminders.RepublishReminder

/**
 * Réponse préparée dès qu'un acheteur écrit, fait une offre ou met un article en favori :
 * notification avec le texte, bouton « Copier » puis « Ouvrir Vinted ». L'envoi reste fait par l'utilisateur.
 */
object ReplyNotifier {
    private const val CHANNEL_ID = "reponses"
    const val ACTION_COPY = "fr.vintedassistant.mobile.COPY_REPLY"
    const val EXTRA_TEXT = "text"
    const val EXTRA_ID = "notification_id"

    fun notify(context: Context, prepared: QuickReply.Prepared) {
        if (!RepublishReminder.canNotify(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Réponses préparées", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Texte prêt à coller quand un acheteur écrit, fait une offre ou met un article en favori."
                },
            )
        }
        val id = 3000 + (prepared.articleId % 1000).toInt() * 4 + prepared.kind.ordinal % 4
        val copy = PendingIntent.getBroadcast(
            context, id,
            Intent(context, CopyReplyReceiver::class.java).setAction(ACTION_COPY)
                .putExtra(EXTRA_TEXT, prepared.message).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val vinted = context.packageManager.getLaunchIntentForPackage(VINTED_PACKAGE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(fr.vintedassistant.mobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle(prepared.heading)
            .setContentText(prepared.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prepared.message))
            .setAutoCancel(true)
            .addAction(0, "Copier", copy)
        if (vinted != null) {
            val open = PendingIntent.getActivity(context, id, vinted.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(open).addAction(0, "Ouvrir Vinted", open)
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission retirée : rien à faire.
        }
    }
}

/** Copie la réponse préparée dans le presse-papiers depuis la notification. */
class CopyReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReplyNotifier.ACTION_COPY) return
        val text = intent.getStringExtra(ReplyNotifier.EXTRA_TEXT) ?: return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Réponse", text))
        // Android 13 et plus affiche déjà sa propre confirmation de copie.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Réponse copiée", Toast.LENGTH_SHORT).show()
    }
}
