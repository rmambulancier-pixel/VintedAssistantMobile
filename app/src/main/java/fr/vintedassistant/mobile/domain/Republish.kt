package fr.vintedassistant.mobile.domain

import java.time.Instant
import java.time.ZoneId

data class RepublishSettings(
    /** Âge minimal de la dernière mise en ligne avant de republier. */
    val minAgeDays: Int,
    /** Nombre maximal de republications par jour. */
    val maxPerDay: Int,
)

data class RepublishPlan(
    val today: List<Article>,
    val doneToday: Int,
    val remainingQuota: Int,
    /** Articles en ligne qui seront éligibles plus tard (pas encore assez anciens). */
    val waiting: Int,
)

object RepublishPlanner {
    /**
     * Plan du jour : articles en ligne dont la dernière mise en avant date d'au moins
     * [RepublishSettings.minAgeDays] jours, les plus anciens d'abord, dans la limite du quota restant.
     * Date inconnue (annonce importée depuis Vinted) : considérée comme ancienne, donc prioritaire.
     */
    fun plan(
        articles: List<Article>,
        republishedTimestamps: List<Long>,
        settings: RepublishSettings,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): RepublishPlan {
        val dayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val doneToday = republishedTimestamps.count { it >= dayStart }
        val quota = (settings.maxPerDay - doneToday).coerceAtLeast(0)
        val threshold = now - settings.minAgeDays.toLong() * DAY_MS
        val online = articles.filter { it.status == ArticleStatus.ONLINE }
        val eligible = online.filter { (it.lastVisibleAt ?: 0L) <= threshold }.sortedBy { it.lastVisibleAt ?: 0L }
        return RepublishPlan(
            today = eligible.take(quota),
            doneToday = doneToday,
            remainingQuota = quota,
            waiting = online.size - eligible.size,
        )
    }
}
