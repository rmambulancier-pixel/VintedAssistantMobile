package fr.vintedassistant.mobile.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

private fun authority(context: Context) = "${context.packageName}.fileprovider"

/**
 * Photos des articles, copiées dans l'espace privé de l'application.
 * Chaque photo est redressée (EXIF), réduite à 1600 px maximum et réencodée en JPEG :
 * place limitée, envoi rapide vers Vinted, et l'original de la galerie n'est jamais modifié.
 */
class PhotoStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    fun shareUri(name: String): Uri = FileProvider.getUriForFile(context, authority(context), file(name))

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(uri, MAX_DIMENSION)
        val name = "${UUID.randomUUID()}.jpg"
        val target = file(name)
        try {
            target.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IOException("Compression JPEG impossible")
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
        name
    }

    fun delete(name: String) {
        file(name).delete()
    }

    /** Importe une image déjà téléchargée (octets JPEG/PNG/WebP), réduite et réencodée comme les autres. */
    suspend fun importBytes(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException("Image illisible")
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Image illisible")
        val name = "${UUID.randomUUID()}.jpg"
        val target = file(name)
        try {
            target.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("Compression JPEG impossible")
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
        name
    }

    /** Miniature pour l'affichage. Null si le fichier est absent ou illisible. */
    suspend fun thumbnail(name: String, maxDimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(name)
        if (!f.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        BitmapFactory.decodeFile(f.path, options)
    }

    private fun decodeScaled(uri: Uri, maxDimension: Int): Bitmap {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1.0, maxDimension.toDouble() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("Image illisible")
        }
    }

    companion object {
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 85

        fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
            var sample = 1
            while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) sample *= 2
            return sample
        }
    }
}

/** Bordereaux d'envoi (PDF ou image), copiés tels quels dans l'espace privé de l'application. */
class LabelStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "labels").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    fun shareUri(name: String): Uri = FileProvider.getUriForFile(context, authority(context), file(name))

    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        else -> "image/jpeg"
    }

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val extension = when (resolver.getType(uri)) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> throw IOException("Format non pris en charge : PDF, JPEG ou PNG uniquement.")
        }
        val name = "${UUID.randomUUID()}.$extension"
        val target = file(name)
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Fichier illisible")
            input.use { inp ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = inp.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("Fichier trop volumineux (15 Mo maximum).")
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        name
    }

    fun delete(name: String) {
        file(name).delete()
    }

    companion object {
        const val MAX_BYTES = 15L * 1024 * 1024
    }
}

/**
 * Téléchargement des photos de vos annonces depuis les serveurs d'images de Vinted.
 * Seules les adresses https des serveurs Vinted sont acceptées ; 10 Mo maximum par image.
 */
object ImageDownloader {
    private const val MAX_BYTES = 10L * 1024 * 1024

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        if (!fr.vintedassistant.mobile.data.importer.PageImport.isAllowedImage(url)) throw IOException("Adresse d'image refusée")
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode != 200) throw IOException("Réponse ${connection.responseCode}")
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw IOException("Image trop lourde")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
