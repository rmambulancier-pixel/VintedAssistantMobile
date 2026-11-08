package fr.vintedassistant.mobile.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.vintedassistant.mobile.MainActivity
import fr.vintedassistant.mobile.VintedBoostApp
import fr.vintedassistant.mobile.domain.ShippingDeadline
import fr.vintedassistant.mobile.ui.screens.appVersion
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Chaque matin vers 9 h : notification s'il reste un colis à déposer aujourd'hui ou au prochain jour ouvré,
 * ou si le délai Vinted est dépassé. Comme pour la republication, Android peut décaler l'heure.
 */
object ShippingReminder {
    private const val CHANNEL_ID = "expeditions"
    private const val WORK_NAME = "shipping_reminder"
    private const val NOTIFICATION_ID = 1002
    private const val HOUR = 9

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ShippingReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(ReminderTime.millisUntilNext(HOUR, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun message(urgent: Int, late: Int): String? = ShippingDeadline.reminderText(urgent, late)

    fun notify(context: Context, text: String) {
        if (!RepublishReminder.canNotify(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Colis à expédier", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rappel quand le délai d'envoi Vinted arrive à échéance."
                },
            )
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(RepublishReminder.EXTRA_OPEN_TAB, RepublishReminder.TAB_SALES)
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(fr.vintedassistant.mobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Colis à expédier")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission retirée : rien à faire.
        }
    }
}

class ShippingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val sales = (applicationContext as VintedBoostApp).container.articleRepository.sales.first()
        val now = System.currentTimeMillis()
        val dues = sales.mapNotNull { ShippingDeadline.due(it, now) }
        ShippingReminder.message(dues.count { it.urgent }, dues.count { it.late })?.let { ShippingReminder.notify(applicationContext, it) }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}

/**
 * Sauvegarde complète automatique, une fois par semaine, dans Téléchargements/VintedAssistant.
 * Rien n'est écrit tant que l'application est vide : une réinstallation ne remplace pas les bonnes sauvegardes.
 */
object AutoBackup {
    private const val WORK_NAME = "auto_backup"
    private const val PREFS = "backup"
    private const val K_LAST = "last_at"
    private const val K_LAST_NAME = "last_name"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun remember(context: Context, name: String, at: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(K_LAST, at).putString(K_LAST_NAME, name).apply()
    }

    /** Date et emplacement de la dernière sauvegarde, null si aucune. */
    fun last(context: Context): Pair<Long, String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(K_LAST, 0L)
        return if (at == 0L) null else at to p.getString(K_LAST_NAME, "").orEmpty()
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as VintedBoostApp).container
        val repo = container.articleRepository
        if (repo.articles.first().isEmpty() && repo.sales.first().isEmpty()) {
            Result.success()
        } else {
            val name = container.backup.saveToDownloads(appVersion(applicationContext))
            AutoBackup.remember(applicationContext, name)
            Result.success()
        }
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
