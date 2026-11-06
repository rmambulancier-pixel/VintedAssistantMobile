package fr.vintedassistant.mobile.data.importer

import fr.vintedassistant.mobile.domain.Money
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class ParsedOrderLine(
    val name: String,
    val unitPriceCents: Long,
    val quantity: Int,
    val imageUrl: String,
    val productUrl: String,
)

data class ParsedOrder(
    val orderRef: String,
    val orderedAt: Long?,
    val lines: List<ParsedOrderLine>,
    val shippingCents: Long,
    val feesCents: Long,
    val totalCents: Long?,
    val url: String,
) {
    val itemsTotalCents: Long get() = lines.sumOf { it.unitPriceCents * it.quantity }
}

/**
 * Lecture du JSON relevé sur une page de commande fournisseur (Nihaojewelry).
 * La structure exacte du site n'est pas documentée : lecture tolérante, et les frais manquants
 * sont déduits du total quand il est connu (total − articles − port).
 */
object SupplierOrderParser {

    fun parse(json: String, now: Long = System.currentTimeMillis()): ParsedOrder? {
        val o = JSONObject(json)
        val lines = mutableListOf<ParsedOrderLine>()
        val seen = HashSet<String>()
        val items = o.optJSONArray("items")
        if (items != null) for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val name = it.optString("name").trim().replace(Regex("\\s+"), " ").take(200)
            val price = money(it.optString("price")) ?: continue
            val qty = it.optString("qty").trim().toIntOrNull()?.coerceIn(1, 10_000) ?: 1
            if (name.length < 3 || price <= 0) continue
            val image = it.optString("image").trim()
            if (!seen.add("$name|$price|$image")) continue
            lines += ParsedOrderLine(name, price, qty, image.takeIf { s -> s.startsWith("https://") }.orEmpty(), it.optString("url").trim())
        }
        if (lines.isEmpty()) return null
        val itemsTotal = lines.sumOf { it.unitPriceCents * it.quantity }
        val shipping = money(o.optString("shipping")) ?: 0L
        val total = money(o.optString("total"))
        val declaredFees = money(o.optString("fees")) ?: 0L
        // Frais non détaillés : écart entre le total payé et articles + port, s'il est positif et plausible.
        val derivedFees = total?.let { it - itemsTotal - shipping }?.takeIf { it in 1..(itemsTotal * 2) } ?: 0L
        val fees = if (declaredFees > 0) declaredFees else derivedFees
        val ref = o.optString("ref").trim().take(60).ifEmpty { o.optString("url").substringAfterLast('/').take(60) }
        return ParsedOrder(
            orderRef = ref,
            orderedAt = date(o.optString("date")),
            lines = lines,
            shippingCents = shipping,
            feesCents = fees,
            totalCents = total,
            url = o.optString("url"),
        )
    }

    /** « €1,23 », « 1.23 € », « EUR 4,50 » → centimes. */
    fun money(raw: String): Long? {
        val n = Regex("""\d{1,6}(?:[.,]\d{1,2})?""").find(raw.replace(" ", " ").replace(" ", ""))?.value ?: return null
        return Money.parseEuros(n)
    }

    /** « 2026-10-12 14:33 », « 12/10/2026 », « 2026/10/12 » → millisecondes (fuseau du téléphone). */
    fun date(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = raw.trim()
        Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:\s+(\d{1,2}):(\d{2}))?""").find(t)?.let { m ->
            val (y, mo, d) = m.destructured
            return try {
                val h = m.groupValues[4].toIntOrNull() ?: 12
                val mi = m.groupValues[5].toIntOrNull() ?: 0
                LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h, mi).atZone(zone).toInstant().toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
        Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})""").find(t)?.let { m ->
            val (d, mo, y) = m.destructured
            return try {
                LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
        return null
    }
}
