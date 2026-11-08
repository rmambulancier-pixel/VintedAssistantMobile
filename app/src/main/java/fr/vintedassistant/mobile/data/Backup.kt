package fr.vintedassistant.mobile.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sauvegarde complète de l'application dans un seul fichier ZIP : base de données (fiches, achats, ventes,
 * historique), photos, bordereaux et réglages. Contrairement à l'export de diagnostic, elle sert à tout
 * remettre en place : après une réinstallation, sur un nouveau téléphone ou après une erreur.
 *
 * Les sauvegardes automatiques vont dans Téléchargements/VintedAssistant : ce dossier reste sur le
 * téléphone même si l'application est désinstallée. Les 4 plus récentes sont gardées.
 */
class Backup(
    private val context: Context,
    private val db: VintedBoostDatabase,
    private val settingsPrefs: SharedPreferences,
    private val closeDatabase: () -> Unit,
) {
    data class Info(
        val createdAt: Long,
        val appVersion: String,
        val schema: Int,
        val articles: Int,
        val sales: Int,
        val orders: Int,
        val files: Int,
    )

    /** Écrit la sauvegarde dans [out]. La base est copiée dans un état cohérent, même si l'app tourne. */
    suspend fun writeTo(out: OutputStream, appVersion: String, now: Long = System.currentTimeMillis()): Info = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "backup-work").apply { deleteRecursively(); mkdirs() }
        try {
            val snapshot = File(work, DB_ENTRY.substringAfterLast('/'))
            snapshotDatabase(snapshot)
            val counts = countRows(snapshot)
            val files = FILE_DIRS.flatMap { dir -> File(context.filesDir, dir).listFiles()?.filter { it.isFile }.orEmpty().map { dir to it } }
            val info = Info(now, appVersion, counts.schema, counts.articles, counts.sales, counts.orders, files.size)
            val manifest = JSONObject()
                .put("format", FORMAT).put("version", 1)
                .put("createdAt", now).put("app", appVersion).put("schema", counts.schema)
                .put("articles", counts.articles).put("sales", counts.sales).put("orders", counts.orders)
                .put("files", files.size)
                .put("settings", prefsToJson(settingsPrefs))
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files.forEach { (dir, f) ->
                    zip.putNextEntry(ZipEntry("files/$dir/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            info
        } finally {
            work.deleteRecursively()
        }
    }

    /** Sauvegarde dans Téléchargements/VintedAssistant, puis ne garde que les [keep] plus récentes. */
    suspend fun saveToDownloads(appVersion: String, prefix: String = "sauvegarde", keep: Int = 4, now: Long = System.currentTimeMillis()): String =
        withContext(Dispatchers.IO) {
            val name = "$prefix-${stamp(now)}.zip"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Dossier Téléchargements inaccessible")
                try {
                    resolver.openOutputStream(uri)?.use { writeTo(it, appVersion, now) } ?: throw IOException("Écriture impossible")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                if (prefix == "sauvegarde") pruneDownloads(keep)
            } else {
                val dir = File(context.getExternalFilesDir(null), FOLDER).apply { mkdirs() }
                File(dir, name).outputStream().use { writeTo(it, appVersion, now) }
                if (prefix == "sauvegarde") {
                    dir.listFiles { f -> f.name.startsWith("sauvegarde-") }?.sortedByDescending { it.name }?.drop(keep)?.forEach { it.delete() }
                }
            }
            "Téléchargements/$FOLDER/$name"
        }

    /** Fichier temporaire à partager (Drive, e-mail…). */
    suspend fun saveToCache(appVersion: String, now: Long = System.currentTimeMillis()): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "backups").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "vinted-assistant-sauvegarde-${stamp(now)}.zip")
        file.outputStream().use { writeTo(it, appVersion, now) }
        file
    }

    private fun pruneDownloads(keep: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/$FOLDER%", "sauvegarde-%")
        val found = mutableListOf<Pair<Long, String>>()
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) found += c.getLong(0) to c.getString(1)
        }
        found.sortedByDescending { it.second }.drop(keep).forEach { (id, _) ->
            try {
                resolver.delete(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()), null, null)
            } catch (e: Exception) {
                // Fichier déplacé ou appartenant à une installation précédente : on le laisse.
            }
        }
    }

    /**
     * Lit et vérifie une sauvegarde sans rien modifier. Le contenu est préparé dans le cache :
     * [restorePrepared] l'installe ensuite. Lève une exception explicite si le fichier ne convient pas.
     */
    suspend fun prepare(input: InputStream, currentSchema: Int = VintedBoostDatabase.VERSION): Info = withContext(Dispatchers.IO) {
        val staged = File(context.cacheDir, STAGED).apply { deleteRecursively(); mkdirs() }
        try {
            var manifest: JSONObject? = null
            val stagedCanonical = staged.canonicalPath + File.separator
            ZipInputStream(input.buffered()).use { zip ->
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val target = File(staged, e.name)
                    if (!target.canonicalPath.startsWith(stagedCanonical)) throw IOException("Fichier de sauvegarde invalide")
                    val allowed = e.name == MANIFEST || e.name == DB_ENTRY ||
                        FILE_DIRS.any { e.name.startsWith("files/$it/") && e.name.count { c -> c == '/' } == 2 }
                    if (!allowed) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("Sauvegarde trop volumineuse")
                            out.write(buf, 0, n)
                        }
                    }
                    if (e.name == MANIFEST) manifest = JSONObject(target.readText())
                }
            }
            val m = manifest ?: throw IllegalArgumentException("Ce fichier n'est pas une sauvegarde Vinted Assistant.")
            if (m.optString("format") != FORMAT) throw IllegalArgumentException("Ce fichier n'est pas une sauvegarde Vinted Assistant.")
            val dbFile = File(staged, DB_ENTRY)
            if (!dbFile.exists()) throw IllegalArgumentException("Sauvegarde incomplète : base de données absente.")
            val counts = countRows(dbFile, checkIntegrity = true)
            if (counts.schema > currentSchema) {
                throw IllegalArgumentException("Sauvegarde faite avec une version plus récente de l'application : installe d'abord la dernière version.")
            }
            Info(
                createdAt = m.optLong("createdAt"),
                appVersion = m.optString("app"),
                schema = counts.schema,
                articles = counts.articles,
                sales = counts.sales,
                orders = counts.orders,
                files = File(staged, "files").walkTopDown().count { it.isFile },
            )
        } catch (e: Exception) {
            staged.deleteRecursively()
            throw e
        }
    }

    /**
     * Remplace les données actuelles par la sauvegarde préparée. La base est fermée : l'application
     * doit redémarrer juste après (voir restartApp dans les réglages).
     */
    suspend fun restorePrepared() = withContext(Dispatchers.IO) {
        val staged = File(context.cacheDir, STAGED)
        val stagedDb = File(staged, DB_ENTRY)
        if (!stagedDb.exists()) throw IllegalStateException("Aucune sauvegarde préparée")
        val manifest = JSONObject(File(staged, MANIFEST).readText())

        // Fichiers d'abord, base en dernier : la bascule de la base se fait par un renommage quasi instantané.
        FILE_DIRS.forEach { dir ->
            val current = File(context.filesDir, dir)
            val incoming = File(staged, "files/$dir")
            current.deleteRecursively()
            current.mkdirs()
            incoming.listFiles()?.forEach { it.copyTo(File(current, it.name), overwrite = true) }
        }
        val dbPath = context.getDatabasePath(VintedBoostDatabase.FILE_NAME)
        dbPath.parentFile?.mkdirs()
        val tmp = File(dbPath.path + ".restore")
        stagedDb.copyTo(tmp, overwrite = true)
        closeDatabase()
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbPath.path + it).delete() }
        if (!tmp.renameTo(dbPath)) {
            tmp.copyTo(dbPath, overwrite = true)
            tmp.delete()
        }
        manifest.optJSONObject("settings")?.let { jsonToPrefs(it, settingsPrefs) }
        staged.deleteRecursively()
    }

    fun discardPrepared() {
        File(context.cacheDir, STAGED).deleteRecursively()
    }

    private fun snapshotDatabase(target: File) {
        target.delete()
        val sql = db.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Copie cohérente faite par SQLite lui-même, sans bloquer l'application.
            sql.execSQL("VACUUM INTO '" + target.path.replace("'", "''") + "'")
        } else {
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            context.getDatabasePath(VintedBoostDatabase.FILE_NAME).copyTo(target, overwrite = true)
        }
    }

    private data class Counts(val schema: Int, val articles: Int, val sales: Int, val orders: Int)

    private fun countRows(file: File, checkIntegrity: Boolean = false): Counts {
        val sql = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw IllegalArgumentException("Base de données de la sauvegarde illisible.")
        }
        return sql.use { d ->
            if (checkIntegrity) {
                val ok = d.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                if (!ok) throw IllegalArgumentException("Base de données de la sauvegarde abîmée.")
            }
            fun count(table: String) = try {
                d.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            } catch (e: Exception) { 0 }
            Counts(d.version, count("articles"), count("sales"), count("purchase_orders"))
        }
    }

    companion object {
        const val FORMAT = "vinted-assistant-backup"
        const val FOLDER = "VintedAssistant"
        private const val MANIFEST = "manifest.json"
        private const val DB_ENTRY = "db/vinted_boost.db"
        private const val STAGED = "restore-staged"
        private val FILE_DIRS = listOf("photos", "labels")
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

        private fun stamp(now: Long) = SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date(now))

        fun prefsToJson(prefs: SharedPreferences): JSONObject {
            val out = JSONObject()
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is Int -> out.put(k, JSONObject().put("t", "i").put("v", v))
                    is Long -> out.put(k, JSONObject().put("t", "l").put("v", v))
                    is Boolean -> out.put(k, JSONObject().put("t", "b").put("v", v))
                    is Float -> out.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                    is String -> out.put(k, JSONObject().put("t", "s").put("v", v))
                }
            }
            return out
        }

        fun jsonToPrefs(json: JSONObject, prefs: SharedPreferences) {
            val editor = prefs.edit().clear()
            json.keys().forEach { k ->
                val o = json.optJSONObject(k) ?: return@forEach
                when (o.optString("t")) {
                    "i" -> editor.putInt(k, o.optInt("v"))
                    "l" -> editor.putLong(k, o.optLong("v"))
                    "b" -> editor.putBoolean(k, o.optBoolean("v"))
                    "f" -> editor.putFloat(k, o.optDouble("v").toFloat())
                    "s" -> editor.putString(k, o.optString("v"))
                }
            }
            editor.commit()
        }
    }
}
