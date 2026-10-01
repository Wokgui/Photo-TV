package fr.wokgui.phototv

import java.util.Calendar

object SmartAlbumPolicy {
    const val FAVORITES = "★ Favoris"
    const val BEST = "✨ Meilleures photos"
    const val RECENT = "🕘 Récentes"
    const val LANDSCAPE = "▭ Paysages"
    const val PORTRAIT = "▯ Portraits"
    const val HIGH_RES = "◫ Haute résolution"
    const val VIDEOS = "▶ Vidéos"

    val labels = listOf(FAVORITES, BEST, RECENT, LANDSCAPE, PORTRAIT, HIGH_RES, VIDEOS)

    data class Candidate(
        val favorite: Boolean,
        val width: Int,
        val height: Int,
        val takenAt: Long,
        val mediaType: String,
        val qualityScore: Int? = null
    )

    fun isSmartAlbum(name: String): Boolean = name in labels

    fun matches(name: String, c: Candidate, now: Calendar = Calendar.getInstance()): Boolean = when (name) {
        FAVORITES -> c.favorite
        BEST -> c.favorite || (c.qualityScore ?: metadataQuality(c)) >= 72
        RECENT -> c.takenAt > 0L && now.timeInMillis - c.takenAt in 0L..(366L * 24 * 60 * 60 * 1000)
        LANDSCAPE -> c.width > c.height && c.width > 0 && c.height > 0
        PORTRAIT -> c.height > c.width && c.width > 0 && c.height > 0
        HIGH_RES -> c.width.toLong() * c.height.toLong() >= 8_000_000L
        VIDEOS -> c.mediaType == "video"
        else -> false
    }

    private fun metadataQuality(c: Candidate): Int {
        val px = c.width.toLong().coerceAtLeast(0) * c.height.toLong().coerceAtLeast(0)
        return when {
            px >= 12_000_000L -> 82
            px >= 8_000_000L -> 75
            px >= 4_000_000L -> 68
            px >= 2_000_000L -> 60
            px > 0L -> 42
            else -> 50
        }
    }
}
