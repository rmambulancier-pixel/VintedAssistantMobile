package fr.vintedassistant.mobile.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Délai d'expédition Vinted : 5 jours ouvrés après l'achat (aide Vinted, articles 753 et 101),
 * week-ends et jours fériés français exclus. Passé ce délai, Vinted annule la commande.
 * Le jour de la vente ne compte pas : une vente le lundi doit partir au plus tard le lundi suivant.
 */
object ShippingDeadline {
    const val WORKING_DAYS = 5

    data class Due(
        /** Dernier jour pour déposer le colis. */
        val lastDay: LocalDate,
        /** Jours ouvrés restants, aujourd'hui compris s'il est ouvré. 0 : délai dépassé. */
        val workingDaysLeft: Int,
    ) {
        val late: Boolean get() = workingDaysLeft == 0
        /** À déposer aujourd'hui ou au prochain jour ouvré au plus tard. */
        val urgent: Boolean get() = workingDaysLeft <= 1
    }

    fun lastDay(soldAt: Long, zone: ZoneId = ZoneId.of("Europe/Paris")): LocalDate {
        var day = Instant.ofEpochMilli(soldAt).atZone(zone).toLocalDate()
        var counted = 0
        while (counted < WORKING_DAYS) {
            day = day.plusDays(1)
            if (isWorkingDay(day)) counted++
        }
        return day
    }

    /** Null pour une vente déjà partie, annulée, ou importée sans date. */
    fun due(sale: Sale, now: Long, zone: ZoneId = ZoneId.of("Europe/Paris")): Due? {
        if (sale.shippingStatus != ShippingStatus.TO_SHIP || !sale.dateKnown) return null
        val last = lastDay(sale.soldAt, zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var left = 0
        var d = today
        while (!d.isAfter(last)) {
            if (isWorkingDay(d)) left++
            d = d.plusDays(1)
        }
        return Due(last, left)
    }

    fun isWorkingDay(d: LocalDate): Boolean =
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(d)

    /** Jours fériés en France métropolitaine. */
    fun isHoliday(d: LocalDate): Boolean {
        val fixed = listOf(1 to 1, 5 to 1, 5 to 8, 7 to 14, 8 to 15, 11 to 1, 11 to 11, 12 to 25)
        if (fixed.any { (m, day) -> d.monthValue == m && d.dayOfMonth == day }) return true
        val easter = easter(d.year)
        return d == easter.plusDays(1) || d == easter.plusDays(39) || d == easter.plusDays(50)
    }

    /** Dimanche de Pâques (algorithme de Meeus pour le calendrier grégorien). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** Texte de la notification, null s'il n'y a rien d'urgent. */
    fun reminderText(urgent: Int, late: Int): String? = when {
        urgent == 0 -> null
        late == urgent -> if (late == 1) "1 colis a dépassé le délai Vinted de 5 jours ouvrés : dépose-le vite ou préviens l'acheteur."
            else "$late colis ont dépassé le délai Vinted de 5 jours ouvrés : dépose-les vite ou préviens les acheteurs."
        late > 0 -> "$urgent colis à déposer au plus vite, dont $late en retard sur le délai Vinted."
        urgent == 1 -> "1 colis est à déposer aujourd'hui, ou au plus tard au prochain jour ouvré."
        else -> "$urgent colis sont à déposer aujourd'hui, ou au plus tard au prochain jour ouvré."
    }
}
