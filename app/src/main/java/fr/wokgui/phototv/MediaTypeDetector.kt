package fr.wokgui.phototv

import java.util.Locale

object MediaTypeDetector {
    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "avif"
    )
    private val videoExtensions = setOf(
        "mp4", "mkv", "webm", "mov", "m4v"
    )

    fun classify(name: String?, mimeType: String?): String? {
        val mime = mimeType.orEmpty().trim().lowercase(Locale.ROOT)
        val ext = name.orEmpty()
            .substringAfterLast('.', "")
            .trim()
            .lowercase(Locale.ROOT)

        return when {
            mime.startsWith("video/") -> "video"
            mime == "image/gif" -> "gif"
            mime.startsWith("image/") -> "image"
            ext == "gif" -> "gif"
            ext in videoExtensions -> "video"
            ext in imageExtensions -> "image"
            else -> null
        }
    }
}
