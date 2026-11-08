package fr.vintedassistant.mobile.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import fr.vintedassistant.mobile.domain.PhotoLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Amélioration d'une vraie photo, entièrement sur le téléphone (ML Kit, gratuit) : le bijou est détouré,
 * posé au centre d'un fond clair carré avec une ombre douce, et la lumière est légèrement réglée.
 * Le bijou n'est ni redessiné ni inventé : c'est la photo d'origine, seul l'arrière-plan change.
 */
class PhotoEnhancer(private val context: Context) {

    data class Result(val bytes: ByteArray, val cutOut: Boolean)

    private val segmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    }

    /** Télécharge si besoin le modèle de détourage (Google Play services, une seule fois). */
    private suspend fun ensureModel() {
        val client = ModuleInstall.getClient(context)
        val available = client.areModulesAvailable(segmenter).await().areModulesAvailable()
        if (!available) {
            client.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).await()
            // Le module s'installe en arrière-plan : on attend qu'il soit utilisable (deux minutes au plus).
            withTimeout(120_000) {
                while (!client.areModulesAvailable(segmenter).await().areModulesAvailable()) kotlinx.coroutines.delay(1_000)
            }
        }
    }

    suspend fun enhance(source: Bitmap, canvasSize: Int = 1080): Result {
        ensureModel()
        val foreground = try {
            segmenter.process(InputImage.fromBitmap(source, 0)).await().foregroundBitmap
        } catch (e: Exception) {
            null
        }
        return withContext(Dispatchers.Default) {
            val out = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(BACKGROUND)
            val fg = foreground
            val box = fg?.let { alphaBox(it) }
            val cutOut = fg != null && box != null
            val subject = if (cutOut) fg!! else source
            val area = box ?: PhotoLayout.Box(0, 0, source.width, source.height)
            val place = PhotoLayout.place(area, canvasSize, if (cutOut) 0.8f else 1f)
            val matrix = Matrix().apply { postScale(place.scale, place.scale); postTranslate(place.x, place.y) }
            val (gain, offset) = PhotoLayout.levels(luminance(subject, area))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    gain, 0f, 0f, 0f, offset,
                    0f, gain, 0f, 0f, offset,
                    0f, 0f, gain, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }
            if (cutOut) {
                // Ombre douce sous le bijou, pour qu'il ne paraisse pas « flotter ».
                val blur = Paint().apply { maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL) }
                val offsetXY = IntArray(2)
                val shadow = subject.extractAlpha(blur, offsetXY)
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.argb(60, 60, 50, 40) }
                val shadowMatrix = Matrix(matrix).apply { preTranslate(offsetXY[0] + 6f, offsetXY[1] + 14f) }
                canvas.drawBitmap(shadow, shadowMatrix, shadowPaint)
                shadow.recycle()
            }
            canvas.drawBitmap(subject, matrix, paint)
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!out.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw IOException("Compression impossible")
                stream.toByteArray()
            }
            out.recycle()
            Result(bytes, cutOut)
        }
    }

    suspend fun enhanceFile(bytes: ByteArray): Result {
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            ?: throw IOException("Photo illisible")
        return try { enhance(bitmap) } finally { bitmap.recycle() }
    }

    private fun alphaBox(bitmap: Bitmap): PhotoLayout.Box? {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return PhotoLayout.subjectBox(IntArray(pixels.size) { pixels[it] ushr 24 }, w, h)
    }

    private fun luminance(bitmap: Bitmap, box: PhotoLayout.Box): IntArray {
        val step = maxOf(1, maxOf(box.width, box.height) / 200)
        val values = ArrayList<Int>()
        var y = box.top
        while (y < box.bottom) {
            var x = box.left
            while (x < box.right) {
                val p = bitmap.getPixel(x, y)
                if ((p ushr 24) > 128) values += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
                x += step
            }
            y += step
        }
        return values.toIntArray()
    }

    private companion object {
        /** Blanc cassé chaud : neutre, flatteur pour l'or comme pour l'argent. */
        val BACKGROUND = Color.rgb(247, 245, 242)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
