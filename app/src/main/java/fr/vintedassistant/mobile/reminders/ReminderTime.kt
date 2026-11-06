package fr.vintedassistant.mobile.reminders

import java.time.Instant
import java.time.ZoneId

object ReminderTime {
    /** Délai jusqu'à la prochaine occurrence de [hour] h 00 (aujourd'hui si pas encore passée, sinon demain). */
    fun millisUntilNext(hour: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val current = Instant.ofEpochMilli(now).atZone(zone)
        var next = current.toLocalDate().atTime(hour.coerceIn(0, 23), 0).atZone(zone)
        if (!next.isAfter(current)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli() - now
    }
}
