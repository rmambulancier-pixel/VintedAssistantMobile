package fr.vintedassistant.mobile.data

import android.content.Context
import fr.vintedassistant.mobile.domain.IncidentLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Journal de Vinted intégré, gardé dans un fichier privé (une ligne JSON par événement, 600 au plus).
 * Hors de la base Room : un incident doit pouvoir être noté même si la base pose problème.
 */
class IncidentStore(private val context: Context) {
    private val file = File(context.filesDir, "incidents.jsonl")
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entries = MutableStateFlow<List<IncidentLog.Entry>>(emptyList())
    val entries: StateFlow<List<IncidentLog.Entry>> = _entries

    init {
        scope.launch { lock.withLock { _entries.value = readAll() } }
    }

    private fun readAll(): List<IncidentLog.Entry> = try {
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { line -> runCatching { IncidentLog.Entry.fromJson(JSONObject(line)) }.getOrNull() }
    } catch (e: Exception) { emptyList() }

    /** Note un événement. Ne lève jamais d'erreur : le journal ne doit pas gêner la mise en vente. */
    fun add(entry: IncidentLog.Entry) {
        scope.launch {
            lock.withLock {
                try {
                    val list = (_entries.value + entry).takeLast(MAX)
                    if (list.size < _entries.value.size + 1) file.writeText(list.joinToString("\n", postfix = "\n") { it.toJson().toString() })
                    else file.appendText(entry.toJson().toString() + "\n")
                    _entries.value = list
                } catch (e: Exception) { /* journal indisponible : on continue sans lui */ }
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock { file.delete(); _entries.value = emptyList() }
    }

    /** Contenu de `incidents.json` : tous les événements, du plus ancien au plus récent. */
    fun json(): String = JSONObject()
        .put("format", "vinted-assistant-incidents").put("version", 1)
        .put("entries", JSONArray().apply { _entries.value.forEach { put(it.toJson()) } })
        .toString(2)

    /** Fichier à envoyer : `resume.txt` lisible et `incidents.json` complet. */
    suspend fun export(version: String, now: Long = System.currentTimeMillis()): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date(now))
        val out = File(dir, "journal-vinted-integre-$stamp.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("resume.txt"))
            zip.write(IncidentLog.summary(_entries.value, now, version).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("incidents.json"))
            zip.write(json().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        out
    }

    companion object { const val MAX = 600 }
}
