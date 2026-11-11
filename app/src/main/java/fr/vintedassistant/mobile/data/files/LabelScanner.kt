package fr.vintedassistant.mobile.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lecture du code produit imprimé sur l'étiquette d'un sachet du fournisseur (« NH53003570 »).
 * Reconnaissance de texte ML Kit, sur le téléphone, gratuite ; le modèle est fourni par Google Play services
 * et téléchargé une fois. Rien n'est envoyé ailleurs.
 */
class LabelScanner(private val context: Context) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Codes produits lus sur la photo, sans doublon. Vide si rien de lisible. */
    suspend fun codes(photo: File): List<String> = withContext(Dispatchers.Default) {
        val bitmap = decode(photo) ?: return@withContext emptyList()
        try {
            val client = ModuleInstall.getClient(context)
            if (!client.areModulesAvailable(recognizer).await().areModulesAvailable()) {
                client.installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build()).await()
                var tries = 0
                while (!client.areModulesAvailable(recognizer).await().areModulesAvailable() && tries++ < 60) kotlinx.coroutines.delay(1_000)
            }
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
            extract(text)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = PhotoStore.sampleSize(bounds.outWidth, bounds.outHeight, 2000) }
        return BitmapFactory.decodeFile(file.path, options)
    }

    companion object {
        private val CODE = Regex("""N\s*H\s*[:.]?\s*(\d{6,10})""", RegexOption.IGNORE_CASE)

        /** « 义乌仓:NH53003570 » donne « NH53003570 » ; les O lus à la place des 0 sont corrigés. */
        fun extract(text: String): List<String> =
            CODE.findAll(text.replace(Regex("(?<=\\d)[Oo]|[Oo](?=\\d)"), "0")).map { "NH" + it.groupValues[1] }.distinct().toList()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
