package fr.wokgui.phototv

import kotlin.math.max
import kotlin.math.min

object QualityScorePolicy {
    data class Metrics(
        val sharpness: Float,
        val exposure: Float,
        val contrast: Float,
        val noiseQuality: Float,
        val portrait: Boolean = false,
        val favorite: Boolean = false,
        val megapixels: Float = 0f
    )

    fun score(metrics: Metrics): Int {
        val sharp = metrics.sharpness.coerceIn(0f, 1f)
        val exposure = metrics.exposure.coerceIn(0f, 1f)
        val contrast = metrics.contrast.coerceIn(0f, 1f)
        val noise = metrics.noiseQuality.coerceIn(0f, 1f)
        val resolution = (metrics.megapixels / 12f).coerceIn(0f, 1f)

        val total =
            sharp * 30f +
            exposure * 20f +
            contrast * 15f +
            noise * 15f +
            resolution * 5f +
            (if (metrics.portrait) 5f else 0f) +
            (if (metrics.favorite) 10f else 0f)
        return total.toInt().coerceIn(0, 100)
    }

    fun label(score: Int): String = when (score.coerceIn(0, 100)) {
        in 85..100 -> "Excellente"
        in 70..84 -> "Très bonne"
        in 55..69 -> "Bonne"
        in 40..54 -> "Moyenne"
        else -> "Faible"
    }
}
