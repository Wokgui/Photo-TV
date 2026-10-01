package fr.wokgui.phototv

import kotlin.math.roundToInt

object PhotoQualityPolicy {
    data class Metrics(
        val brightness: Float,
        val contrast: Float,
        val edgeEnergy: Float,
        val megapixels: Float
    )

    data class Result(
        val score: Int,
        val blurred: Boolean,
        val underexposed: Boolean,
        val overexposed: Boolean
    )

    fun evaluate(metrics: Metrics): Result {
        val brightness = metrics.brightness.coerceIn(0f, 1f)
        val contrast = metrics.contrast.coerceIn(0f, 1f)
        val edge = metrics.edgeEnergy.coerceIn(0f, 1f)
        val resolution = (metrics.megapixels / 8f).coerceIn(0f, 1f)

        val exposurePenalty = when {
            brightness < .12f -> (.12f - brightness) / .12f
            brightness > .92f -> (brightness - .92f) / .08f
            else -> 0f
        }.coerceIn(0f, 1f)

        val score = (
            100f * (
                edge * .40f +
                    contrast * .25f +
                    resolution * .20f +
                    (1f - exposurePenalty) * .15f
                )
            ).roundToInt().coerceIn(0, 100)

        return Result(
            score = score,
            blurred = edge < .12f,
            underexposed = brightness < .12f,
            overexposed = brightness > .92f
        )
    }
}

object SmartAlbumPolicy {
    const val BEST = "Intelligent • Meilleures"
    const val LANDSCAPES = "Intelligent • Paysages"
    const val PORTRAITS = "Intelligent • Portraits"
    const val RECENT = "Intelligent • Récentes"
    const val LOW_QUALITY = "Intelligent • À revoir"

    val names = listOf(BEST, LANDSCAPES, PORTRAITS, RECENT, LOW_QUALITY)

    fun matches(
        album: String,
        width: Int,
        height: Int,
        takenAt: Long,
        favorite: Boolean,
        qualityScore: Int?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = when (album) {
        BEST -> favorite || (qualityScore ?: 0) >= 72
        LANDSCAPES -> width > 0 && height > 0 && width.toFloat() / height.toFloat() >= 1.20f
        PORTRAITS -> width > 0 && height > 0 && height.toFloat() / width.toFloat() >= 1.20f
        RECENT -> takenAt > 0L && nowMillis - takenAt in 0L..(365L * 24L * 60L * 60L * 1000L)
        LOW_QUALITY -> qualityScore != null && qualityScore < 45
        else -> false
    }
}
