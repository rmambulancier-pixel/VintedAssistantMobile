package fr.vintedassistant.mobile.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.vintedassistant.mobile.MainActivity
import fr.vintedassistant.mobile.VintedBoostApp
import fr.vintedassistant.mobile.domain.RepublishPlanner
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Rappel quotidien : une notification indique combien d'annonces sont à republier.
 * Il ne republie rien lui-même. WorkManager ne garantit pas l'heure exacte :
 * Android peut décaler la notification pour économiser la batterie.
 */
object RepublishReminder {
    const val CHANNEL_ID = "republication"
    private const val WORK_NAME = "republish_reminder"
    private const val NOTIFICATION_ID = 1001
    const val EXTRA_OPEN_TAB = "open_tab"
    const val TAB_REPUBLISH = "republish"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Rappels de republication", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Un rappel par jour quand des annonces sont à republier."
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun schedule(context: Context, hour: Int) {
        val delay = ReminderTime.millisUntilNext(hour, System.currentTimeMillis())
        val request = PeriodicWorkRequestBuilder<RepublishReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun canNotify(context: Context): Boolean {
        val permissionOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permissionOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notify(context: Context, count: Int) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TAB, TAB_REPUBLISH)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (count == 1) "1 annonce est à republier aujourd'hui." else "$count annonces sont à republier aujourd'hui."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Republication du jour")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission retirée entre-temps : on n'insiste pas.
        }
    }
}

class RepublishReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as VintedBoostApp).container
        val settings = container.settingsStore.current()
        if (!settings.reminderEnabled) return Result.success()
        return try {
            val repository = container.articleRepository
            val plan = RepublishPlanner.plan(
                articles = repository.articles.first(),
                republishedTimestamps = repository.republishedTimestamps.first(),
                settings = settings.republish,
                now = System.currentTimeMillis(),
            )
            if (plan.today.isNotEmpty()) RepublishReminder.notify(applicationContext, plan.today.size)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
