package fr.vintedassistant.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import fr.vintedassistant.mobile.MainActivity
import fr.vintedassistant.mobile.R
import fr.vintedassistant.mobile.VintedBoostApp
import fr.vintedassistant.mobile.domain.ShippingDeadline
import fr.vintedassistant.mobile.domain.ShippingStatus
import fr.vintedassistant.mobile.domain.StockBoard
import fr.vintedassistant.mobile.domain.StockLedger
import fr.vintedassistant.mobile.domain.WidgetSummary
import fr.vintedassistant.mobile.domain.computeDashboard
import fr.vintedassistant.mobile.reminders.RepublishReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Widget d'écran d'accueil : ventes du mois et objectif, colis à déposer, stock. Lecture seule : toucher le
 * widget ouvre l'application (sur les ventes s'il y a un colis à déposer).
 * Mis à jour par l'application à chaque changement de données, et par Android toutes les 30 minutes.
 */
class SalesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Rafraîchit tous les widgets posés (sans effet s'il n'y en a aucun). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SalesWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { runCatching { render(context, manager, ids) } }
        }

        private suspend fun summary(context: Context): WidgetSummary {
            val c = (context.applicationContext as VintedBoostApp).container
            val settings = c.settingsStore.current()
            val articles = c.articleRepository.articles.first()
            val sales = c.articleRepository.sales.first()
            val orders = c.purchaseRepository.orders.first()
            val now = System.currentTimeMillis()
            val dash = computeDashboard(articles, sales, settings.dashboard, now, supplies = c.supplies.items.first())
            val board = StockBoard.build(StockLedger.build(orders, articles), sales)
            val dues = sales.mapNotNull { ShippingDeadline.due(it, now) }
            val undated = sales.count { it.shippingStatus == ShippingStatus.TO_SHIP && !it.dateKnown }
            return WidgetSummary.build(
                dash.monthRevenueCents, dash.monthNetCents, dash.monthSalesCount, settings.monthlyGoalEuros,
                dues, undated, board, LocalDate.now(),
            )
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val s = try { summary(context) } catch (e: Exception) { return }
            val open = openApp(context, sales = s.parcelsCount > 0)
            val wide = views(context, R.layout.widget_wide, s, open)
            val small = views(context, R.layout.widget_small, s, open)
            ids.forEach { id ->
                val rv = if (Build.VERSION.SDK_INT >= 31) {
                    // Android choisit la mise en page selon la taille réelle du widget.
                    RemoteViews(mapOf(SizeF(110f, 100f) to small, SizeF(250f, 100f) to wide))
                } else wide
                manager.updateAppWidget(id, rv)
            }
        }

        private fun views(context: Context, layout: Int, s: WidgetSummary, open: PendingIntent): RemoteViews =
            RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.w_month, s.monthLabel)
                setTextViewText(R.id.w_revenue, s.revenue)
                if (layout == R.layout.widget_wide) {
                    setTextViewText(R.id.w_detail, s.monthDetail)
                    setTextViewText(R.id.w_stock, s.stock)
                    setTextViewText(R.id.w_goal_pill, s.goalPercent?.let { "$it %" } ?: "")
                    setViewVisibility(R.id.w_goal_pill, if (s.goalPercent == null) View.GONE else View.VISIBLE)
                } else {
                    setTextViewText(R.id.w_goal_pill, s.goalPercent?.let { "$it % • ${s.goalText}" } ?: s.monthDetail)
                }
                setProgressBar(R.id.w_progress, 100, (s.goalPercent ?: 0).coerceIn(0, 100), false)
                setViewVisibility(R.id.w_progress, if (s.goalPercent == null) View.GONE else View.VISIBLE)
                setTextViewText(R.id.w_parcels, s.parcels)
                setTextColor(R.id.w_parcels, if (s.parcelsUrgent) 0xFFF2647A.toInt() else 0xFFFFFFFF.toInt())
                setOnClickPendingIntent(R.id.widget_root, open)
            }

        private fun openApp(context: Context, sales: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (sales) intent.putExtra(RepublishReminder.EXTRA_OPEN_TAB, RepublishReminder.TAB_SALES)
            return PendingIntent.getActivity(context, if (sales) 7101 else 7100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
