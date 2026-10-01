package fr.wokgui.phototv

object TransitionPolicy {
    fun choose(
        requestedIndex: Int,
        fadeIndex: Int,
        noneIndex: Int,
        mediaType: String,
        imageMode: Int,
        width: Int,
        height: Int,
        measuredFps: Float,
        jankPercent: Long,
        freeRatio: Double
    ): Int {
        if (requestedIndex == noneIndex) return noneIndex
        if (imageMode >= 4 || mediaType == "video") return noneIndex
        if (freeRatio < .15) return noneIndex
        if (mediaType == "gif") return fadeIndex

        val pixels = width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
        val runtimeSlow = measuredFps in 1f..20f || jankPercent >= 25L
        val heavyImage = pixels >= 20_000_000L && freeRatio < .35
        val constrained = freeRatio < .24

        return if (runtimeSlow || heavyImage || constrained) fadeIndex else requestedIndex
    }
}
