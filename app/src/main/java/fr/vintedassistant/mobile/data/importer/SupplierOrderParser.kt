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
    /** Code produit du fournisseur (« NH59670045 ») : identique d'une commande à l'autre. */
    val sku: String = "",
)

data class ParsedOrder(
    val orderRef: String,
    val orderedAt: Long?,
    val lines: List<ParsedOrderLine>,
    val shippingCents: Long,
    val feesCents: Long,
    val totalCents: Long?,
    val url: String,
    /** Livraison annoncée par le fournisseur (« mer. 25 nov. - mar. 1 déc. »), vide si inconnue. */
    val deliveryEta: String = "",
    /** Nombre de pièces annoncé par le site, pour vérifier que toutes les lignes ont été lues. */
    val expectedPieces: Int? = null,
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
            val name = it.optString("name").trim().replace(Regex("\\s+"), " ")
                .replace(Regex("^(?i)(essai gratuit|free trial)\\s*"), "").take(200)
            // Produit offert (« Essai gratuit ») : payé 0 €, son coût sera sa part du port.
            val price = if (it.optBoolean("free")) 0L else money(it.optString("price")) ?: continue
            val qty = it.optString("qty").trim().toIntOrNull()?.coerceIn(1, 10_000) ?: 1
            // Prix 0 accepté : produit offert (« Essai gratuit »), son coût sera sa part du port.
            if (name.length < 3 || price < 0) continue
            val image = it.optString("image").trim()
            if (!seen.add("$name|$price|$image|" + it.optString("sku"))) continue
            val sku = it.optString("sku").trim().uppercase().takeIf { s -> s.matches(Regex("[A-Z0-9]{4,40}")) }.orEmpty()
            lines += ParsedOrderLine(name, price, qty, image.takeIf { s -> s.startsWith("https://") }.orEmpty(), it.optString("url").trim(), sku)
        }
        if (lines.isEmpty()) return null
        val shipping = money(o.optString("shipping")) ?: 0L
        val total = money(o.optString("total"))
        // Total payé nul ou égal au seul port : tous les produits étaient offerts, quel que soit le prix affiché.
        if (total != null && shipping > 0 && (total <= 5 || kotlin.math.abs(total - shipping) <= 5) && lines.any { it.unitPriceCents > 0 }) {
            lines.replaceAll { it.copy(unitPriceCents = 0) }
        }
        val itemsTotal = lines.sumOf { it.unitPriceCents * it.quantity }
        val declaredFees = money(o.optString("fees")) ?: 0L
        // Frais non détaillés : écart entre le total payé et articles + port, s'il est positif et plausible.
        // Écart de quelques centimes = arrondi du site, pas des frais.
        // Écart positif : frais non détaillés. Un coupon est déjà déduit des prix unitaires payés
        // (0,74 € payé pour 0,82 € d'origine), il ne crée donc pas d'écart. Quelques centimes : arrondi.
        val derivedFees = total?.let { it - itemsTotal - shipping }?.takeIf { it > 5 && it <= itemsTotal * 2 } ?: 0L
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
            expectedPieces = o.optString("count").trim().toIntOrNull()?.takeIf { n -> n in 1..10_000 },
            deliveryEta = o.optString("eta").trim().replace(Regex("\\s+"), " ").take(60),
        )
    }

    /** « €1,23 », « 1.23 € », « EUR 4,50 » → centimes. */
    fun money(raw: String): Long? {
        val n = Regex("""\d{1,6}(?:[.,]\d{1,2})?""").find(raw.replace(" ", " ").replace(" ", ""))?.value ?: return null
        return Money.parseEuros(n)
    }

    private val MONTHS = listOf(
        "jan" to 1, "fév" to 2, "fev" to 2, "feb" to 2, "mar" to 3, "avr" to 4, "apr" to 4, "mai" to 5, "may" to 5,
        "juin" to 6, "jun" to 6, "juil" to 7, "jul" to 7, "aoû" to 8, "aou" to 8, "aug" to 8, "sep" to 9,
        "oct" to 10, "nov" to 11, "déc" to 12, "dec" to 12,
        "janv" to 1, "févr" to 2, "sept" to 9, "june" to 6, "july" to 7,
    )

    /** « 13 oct. 2026, 11:30 », « 2026-10-12 14:33 », « 12/10/2026 » → millisecondes (fuseau du téléphone). */
    fun date(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        // Le site sépare souvent jour, mois et année par des espaces insécables, que \s ne reconnaît pas.
        val t = raw.replace(Regex("(?i)free\\s*trial|essai\\s*gratuit"), " ").replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2007', ' ').trim()
        Regex("""(\d{1,2})\s+([\p{L}]{3,9})\.?\s+(\d{4})(?:,?\s+(\d{1,2}):(\d{2}))?""").find(t)?.let { m ->
            val word = m.groupValues[2].lowercase()
            // « juin » / « juil » avant « jui… » : on teste les préfixes les plus longs d'abord.
            val month = MONTHS.sortedByDescending { it.first.length }.firstOrNull { word.startsWith(it.first) }?.second
            if (month != null) {
                return try {
                    LocalDateTime.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt(),
                        m.groupValues[4].toIntOrNull() ?: 12, m.groupValues[5].toIntOrNull() ?: 0)
                        .atZone(zone).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    null
                }
            }
        }
        // Site en anglais : « Aug 20, 2026 », « Aug 20, 2026 11:05 PM ».
        Regex("""([\p{L}]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})(?:,?\s+(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp][Mm])?)?""").find(t)?.let { m ->
            val word = m.groupValues[1].lowercase()
            val month = MONTHS.sortedByDescending { it.first.length }.firstOrNull { word.startsWith(it.first) }?.second
            if (month != null) {
                var hour = m.groupValues[4].toIntOrNull() ?: 12
                val pm = m.groupValues[6].lowercase()
                if (pm == "pm" && hour < 12) hour += 12
                if (pm == "am" && hour == 12) hour = 0
                return try {
                    LocalDateTime.of(m.groupValues[3].toInt(), month, m.groupValues[2].toInt(), hour, m.groupValues[5].toIntOrNull() ?: 0)
                        .atZone(zone).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    null
                }
            }
        }
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
