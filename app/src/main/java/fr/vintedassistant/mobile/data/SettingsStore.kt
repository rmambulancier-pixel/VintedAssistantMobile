package fr.vintedassistant.mobile.data

import android.content.SharedPreferences
import fr.vintedassistant.mobile.domain.DashboardSettings
import fr.vintedassistant.mobile.domain.RepublishSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Taux de cotisations sociales en points de base (1230 = 12,30 %). 0 pour un particulier. */
    val contributionRateBp: Int = 0,
    val republishMinAgeDays: Int = 7,
    val republishMaxPerDay: Int = 10,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 19,
    val stagnantDays: Int = 21,
    val priceDropPercent: Int = 10,
    val aiEnabled: Boolean = true,
) {
    val republish get() = RepublishSettings(republishMinAgeDays, republishMaxPerDay)
    val dashboard get() = DashboardSettings(contributionRateBp, stagnantDays, priceDropPercent)

    /** Bornes de sécurité, appliquées à chaque enregistrement. */
    fun sanitized() = copy(
        contributionRateBp = contributionRateBp.coerceIn(0, 10_000),
        republishMinAgeDays = republishMinAgeDays.coerceIn(1, 90),
        republishMaxPerDay = republishMaxPerDay.coerceIn(1, 100),
        reminderHour = reminderHour.coerceIn(0, 23),
        stagnantDays = stagnantDays.coerceIn(1, 365),
        priceDropPercent = priceDropPercent.coerceIn(0, 50),
    )
}

/** Réglages stockés localement. Une seule instance dans l'application. */
class SettingsStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    fun current(): AppSettings = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value).sanitized()
        prefs.edit()
            .putInt(K_RATE, next.contributionRateBp)
            .putInt(K_MIN_AGE, next.republishMinAgeDays)
            .putInt(K_MAX_DAY, next.republishMaxPerDay)
            .putBoolean(K_REMINDER, next.reminderEnabled)
            .putInt(K_HOUR, next.reminderHour)
            .putInt(K_STAGNANT, next.stagnantDays)
            .putInt(K_DROP, next.priceDropPercent)
            .putBoolean(K_AI, next.aiEnabled)
            .apply()
        state.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            contributionRateBp = prefs.getInt(K_RATE, d.contributionRateBp),
            republishMinAgeDays = prefs.getInt(K_MIN_AGE, d.republishMinAgeDays),
            republishMaxPerDay = prefs.getInt(K_MAX_DAY, d.republishMaxPerDay),
            reminderEnabled = prefs.getBoolean(K_REMINDER, d.reminderEnabled),
            reminderHour = prefs.getInt(K_HOUR, d.reminderHour),
            stagnantDays = prefs.getInt(K_STAGNANT, d.stagnantDays),
            priceDropPercent = prefs.getInt(K_DROP, d.priceDropPercent),
            aiEnabled = prefs.getBoolean(K_AI, d.aiEnabled),
        ).sanitized()
    }

    companion object {
        const val PREFS_NAME = "settings"
        private const val K_RATE = "contribution_rate_bp"
        private const val K_MIN_AGE = "republish_min_age_days"
        private const val K_MAX_DAY = "republish_max_per_day"
        private const val K_REMINDER = "reminder_enabled"
        private const val K_HOUR = "reminder_hour"
        private const val K_STAGNANT = "stagnant_days"
        private const val K_DROP = "price_drop_percent"
        private const val K_AI = "ai_enabled"
    }
}
