package fr.vintedassistant.mobile.data.importer

import fr.vintedassistant.mobile.domain.Money
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.text.Normalizer
import java.util.zip.ZipInputStream

/** Article trouvé dans l'export RGPD de Vinted. */
data class ExportCandidate(
    val title: String,
    val priceCents: Long?,
    val description: String = "",
    val brand: String = "",
    val size: String = "",
    val sourceFile: String = "",
)

/** Résumé d'un fichier de l'export : sert à adapter l'import au format réel. */
data class ExportFileSummary(val name: String, val records: Int, val keys: List<String>)

data class ExportReport(val candidates: List<ExportCandidate>, val files: List<ExportFileSummary>) {
    /** Texte à copier pour transmettre la structure (noms de fichiers et de champs, aucune valeur). */
    fun structureText(): String = buildString {
        appendLine("Structure de l'export Vinted (${files.size} fichier(s) lus, aucune donnée personnelle) :")
        files.forEach { appendLine("- ${it.name} : ${it.records} enregistrement(s), champs ${it.keys.take(40).joinToString(", ")}") }
        append("Articles reconnus : ${candidates.size}")
    }
}

/**
 * Lecture tolérante de l'archive ZIP fournie par Vinted au titre du RGPD.
 * Le format n'est pas documenté publiquement : on cherche, dans les fichiers JSON et CSV,
 * des listes d'objets qui ont un titre et un prix. Les fichiers d'articles (« item »,
 * « article », « annonce », « listing ») sont prioritaires.
 */
object VintedExportParser {
    private const val MAX_ENTRY_BYTES = 20L * 1024 * 1024
    private val TITLE_KEYS = listOf("title", "titre", "item_title", "itemtitle", "name", "nom")
    private val PRICE_KEYS = listOf("price", "prix", "price_numeric", "item_price", "amount", "montant", "total_item_price")
    private val DESCRIPTION_KEYS = listOf("description", "desc")
    private val BRAND_KEYS = listOf("brand", "brand_title", "marque")
    private val SIZE_KEYS = listOf("size", "size_title", "taille")
    private val ITEM_FILE = Regex("item|article|annonce|listing|wardrobe|dressing", RegexOption.IGNORE_CASE)

    fun parse(zip: InputStream): ExportReport {
        val files = mutableListOf<ExportFileSummary>()
        val preferred = mutableListOf<ExportCandidate>()
        val others = mutableListOf<ExportCandidate>()
        ZipInputStream(zip).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val name = entry.name
                val lower = name.lowercase()
                if (entry.isDirectory || !(lower.endsWith(".json") || lower.endsWith(".csv"))) continue
                val bytes = readLimited(stream) ?: continue
                val records = try {
                    if (lower.endsWith(".json")) jsonRecords(String(bytes, Charsets.UTF_8)) else csvRecords(String(bytes, Charsets.UTF_8))
                } catch (e: Exception) {
                    emptyList()
                }
                val keys = records.flatMap { it.keys }.distinct()
                files += ExportFileSummary(name, records.size, keys)
                val found = records.mapNotNull { toCandidate(it, name) }
                if (ITEM_FILE.containsMatchIn(name.substringAfterLast('/'))) preferred += found else others += found
            }
        }
        val chosen = preferred.ifEmpty { others }
        return ExportReport(dedupe(chosen), files)
    }

    private fun readLimited(stream: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Toutes les listes d'objets du document, aplaties en dictionnaires clé → texte. */
    internal fun jsonRecords(text: String): List<Map<String, String>> {
        val root = JSONTokener(text).nextValue()
        val result = mutableListOf<Map<String, String>>()
        fun walk(value: Any?, depth: Int) {
            if (depth > 8) return
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) {
                    val v = value.opt(i)
                    if (v is JSONObject) result += flatten(v) else walk(v, depth + 1)
                    if (v is JSONObject) v.keys().forEach { k -> val c = v.opt(k); if (c is JSONArray) walk(c, depth + 1) }
                }
                is JSONObject -> value.keys().forEach { k -> walk(value.opt(k), depth + 1) }
            }
        }
        walk(root, 0)
        return result
    }

    private fun flatten(obj: JSONObject): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        obj.keys().forEach { key ->
            when (val v = obj.opt(key)) {
                null, JSONObject.NULL -> {}
                is JSONArray -> {}
                // Prix sous forme d'objet : {"amount": "12.0", "currency_code": "EUR"}
                is JSONObject -> listOf("amount", "title", "value", "name").firstNotNullOfOrNull { k -> v.opt(k)?.takeIf { it != JSONObject.NULL } }
                    ?.let { map[key.lowercase()] = it.toString() }
                else -> map[key.lowercase()] = v.toString()
            }
        }
        return map
    }

    internal fun csvRecords(text: String): List<Map<String, String>> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val separator = if (lines[0].count { it == ';' } > lines[0].count { it == ',' }) ';' else ','
        val header = splitCsv(lines[0].removePrefix("﻿"), separator).map { it.trim().lowercase() }
        return lines.drop(1).map { line ->
            val cells = splitCsv(line, separator)
            header.indices.associate { i -> header[i] to (cells.getOrNull(i)?.trim() ?: "") }
        }
    }

    private fun splitCsv(line: String, separator: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == separator && !quoted -> { cells += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }

    private fun Map<String, String>.first(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> this[k]?.trim()?.takeIf { it.isNotEmpty() && it != "null" } }

    internal fun toCandidate(record: Map<String, String>, file: String): ExportCandidate? {
        val title = record.first(TITLE_KEYS) ?: return null
        val rawPrice = record.first(PRICE_KEYS) ?: return null
        if (title.length > 200) return null
        return ExportCandidate(
            title = title,
            priceCents = parsePrice(rawPrice),
            description = record.first(DESCRIPTION_KEYS).orEmpty().take(2000),
            brand = record.first(BRAND_KEYS).orEmpty(),
            size = record.first(SIZE_KEYS).orEmpty(),
            sourceFile = file,
        )
    }

    /** « 12.0 », « 12,50 € », « EUR 7 » → centimes. */
    internal fun parsePrice(raw: String): Long? {
        val number = Regex("""\d+(?:[.,]\d{1,2})?""").find(raw)?.value ?: return null
        return Money.parseEuros(number)
    }

    private fun dedupe(list: List<ExportCandidate>): List<ExportCandidate> =
        list.distinctBy { normalize(it.title) to it.priceCents }

    internal fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ").trim()
}
