package fr.vintedassistant.mobile.data.legacy

import org.json.JSONArray
import java.math.BigDecimal
import java.math.RoundingMode

/** Article tel qu'enregistré par la version 0.1 (JSON dans les SharedPreferences). */
data class LegacyItem(
    val id: Long,
    val title: String,
    val costCents: Long,
    val priceCents: Long,
    val status: String,
)

data class LegacyParseResult(val items: List<LegacyItem>, val skipped: Int)

object LegacyInventoryParser {
    private const val DEFAULT_STATUS = "Brouillon"

    /**
     * Lit le JSON de la version 0.1. Les entrées incomplètes sont ignorées et comptées.
     * @throws org.json.JSONException si le texte n'est pas un tableau JSON lisible.
     */
    fun parse(raw: String): LegacyParseResult {
        val array = JSONArray(raw)
        val items = mutableListOf<LegacyItem>()
        var skipped = 0
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
            if (obj == null) {
                skipped++
                continue
            }
            val id = obj.optLong("id", 0L)
            val title = obj.optString("title", "").trim()
            val cost = obj.optDouble("cost", Double.NaN)
            val price = obj.optDouble("price", Double.NaN)
            val valid = id > 0L && title.isNotEmpty() &&
                cost.isFinite() && price.isFinite() && cost >= 0.0 && price >= 0.0
            if (!valid) {
                skipped++
                continue
            }
            val status = obj.optString("status", DEFAULT_STATUS).ifBlank { DEFAULT_STATUS }
            items += LegacyItem(id, title, toCents(cost), toCents(price), status)
        }
        return LegacyParseResult(items, skipped)
    }

    /** 19.99 en Double donne 1999 centimes, sans erreur d'arrondi flottant. */
    internal fun toCents(euros: Double): Long =
        BigDecimal.valueOf(euros).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
}
