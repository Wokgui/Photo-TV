package fr.wokgui.phototv

/** Keeps the existing full fade by default; lower strengths reduce the overlap. */
object FadeBlendPolicy {
    fun progress(progress: Float, intensityPercent: Int): Float {
        val t = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
        val strength = intensityPercent.coerceIn(0, 100) / 100f
        if (strength == 0f) return if (t < .5f) 0f else 1f
        if (strength == 1f) return t
        return ((t - (1f - strength) / 2f) / strength).coerceIn(0f, 1f)
    }
}
