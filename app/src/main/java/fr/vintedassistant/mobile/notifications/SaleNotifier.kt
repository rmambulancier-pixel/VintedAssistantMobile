package fr.vintedassistant.mobile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.vintedassistant.mobile.MainActivity
import fr.vintedassistant.mobile.data.ArticleRepository
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.reminders.RepublishReminder

/** Confirmation d'une vente enregistrée automatiquement à partir d'une notification Vinted. */
object SaleNotifier {
    private const val CHANNEL_ID = "ventes"

    fun notify(context: Context, sale: ArticleRepository.AutoSale, insight: String? = null) {
        if (!RepublishReminder.canNotify(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ventes enregistrées", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(RepublishReminder.EXTRA_OPEN_TAB, RepublishReminder.TAB_SALES)
        val pending = PendingIntent.getActivity(
            context, sale.articleId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val price = Money.formatEuros(sale.priceCents)
        val text = (if (sale.priceFromNotice) "${sale.title.take(60)} : $price, à expédier."
            else "${sale.title.take(60)} : $price (prix affiché, corrige-le si tu as accepté une offre). À expédier.") +
            (insight?.let { " $it" } ?: "")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(fr.vintedassistant.mobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Vente enregistrée")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(2000 + (sale.articleId % 1000).toInt(), notification)
        } catch (e: SecurityException) {
            // Permission retirée : la vente reste enregistrée, seule la confirmation manque.
        }
    }
}
