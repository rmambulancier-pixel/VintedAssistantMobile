package fr.vintedassistant.mobile.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Calculs de l'amélioration d'une photo, sans Android : cadrage du bijou détouré sur un fond carré
 * et réglage doux de la lumière. Le bijou lui-même n'est ni redessiné ni retouché dans sa forme.
 */
object PhotoLayout {

    /** Rectangle en pixels (bornes incluses à gauche et en haut, exclues à droite et en bas). */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Placement du bijou sur la toile : échelle et coin haut gauche. */
    data class Placement(val scale: Float, val x: Float, val y: Float)

    /**
     * Rectangle des pixels assez opaques ([alpha] ligne par ligne, 0 à 255). Null si le sujet occupe moins
     * de [minShare] de l'image : le détourage n'a probablement rien trouvé d'utile.
     */
    fun subjectBox(alpha: IntArray, width: Int, height: Int, threshold: Int = 40, minShare: Double = 0.01): Box? {
        var l = width; var t = height; var r = -1; var b = -1; var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (alpha[y * width + x] >= threshold) {
                count++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < 0 || count < width.toLong() * height * minShare) return null
        return Box(l, t, r + 1, b + 1)
    }

    /** Centre le sujet sur une toile carrée de [canvas] pixels, en lui laissant [fill] de la largeur ou de la hauteur. */
    fun place(subject: Box, canvas: Int, fill: Float = 0.8f): Placement {
        val scale = fill * canvas / max(subject.width, subject.height).toFloat()
        val w = subject.width * scale
        val h = subject.height * scale
        return Placement(scale, (canvas - w) / 2f - subject.left * scale, (canvas - h) / 2f - subject.top * scale)
    }

    /**
     * Réglage doux de la lumière à partir de la luminance (0 à 255) des pixels du sujet : on étire entre
     * les 1 % les plus sombres et les 1 % les plus clairs, gain plafonné à [maxGain] pour rester naturel.
     * Renvoie (gain, décalage) à appliquer à chaque canal : v' = v × gain + décalage.
     */
    fun levels(luminance: IntArray, maxGain: Float = 1.3f): Pair<Float, Float> {
        if (luminance.isEmpty()) return 1f to 0f
        val hist = IntArray(256)
        luminance.forEach { hist[it.coerceIn(0, 255)]++ }
        val cut = max(1, luminance.size / 100)
        var low = 0; var acc = 0
        while (low < 255 && acc + hist[low] < cut) { acc += hist[low]; low++ }
        var high = 255; acc = 0
        while (high > 0 && acc + hist[high] < cut) { acc += hist[high]; high-- }
        if (high - low < 10) return 1f to 0f
        val gain = min(maxGain, 255f / (high - low))
        // On centre l'étirement sur le milieu de la plage du sujet, pour ne pas assombrir ni brûler.
        val mid = (low + high) / 2f
        val offset = 135f - mid * gain
        return gain to offset.coerceIn(-40f, 40f).roundToInt().toFloat()
    }
}
