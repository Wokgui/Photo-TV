package fr.wokgui.phototv

object SmartAlbumPolicy {
    const val PREFIX = "Intelligent • "
    const val FAVORITES = PREFIX + "Favoris"
    const val HIGH_QUALITY = PREFIX + "Haute qualité"
    const val LANDSCAPE = PREFIX + "Paysages"
    const val PORTRAIT = PREFIX + "Portraits"
    const val RECENT = PREFIX + "Récentes"
    const val VIDEOS = PREFIX + "Vidéos"

    val labels = listOf(FAVORITES, HIGH_QUALITY, LANDSCAPE, PORTRAIT, RECENT, VIDEOS)

    fun isSmart(name: String): Boolean = name in labels

    fun matches(
        album: String,
        favorite: Boolean,
        width: Int,
        height: Int,
        mediaType: String,
        takenAt: Long,
        qualityScore: Int?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = when (album) {
        FAVORITES -> favorite
        HIGH_QUALITY -> mediaType == "image" && (qualityScore ?: 0) >= 68
        LANDSCAPE -> width > 0 && height > 0 && width > height
        PORTRAIT -> width > 0 && height > 0 && height > width
        RECENT -> takenAt > 0L && nowMillis - takenAt in 0L..(120L * 24L * 60L * 60L * 1000L)
        VIDEOS -> mediaType == "video"
        else -> false
    }
}
