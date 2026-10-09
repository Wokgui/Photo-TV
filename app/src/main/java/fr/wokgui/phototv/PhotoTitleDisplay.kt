package fr.wokgui.phototv

object PhotoTitleDisplay {
    private val mediaExtension = Regex("\\.(?:jpe?g|png|gif|webp|heic|heif|avif|bmp|tiff?|dng|mp4|m4v|mov|mkv|avi|webm|3gp)$", RegexOption.IGNORE_CASE)
    fun format(title: String, hideExtensions: Boolean): String =
        if (hideExtensions) title.replace(mediaExtension, "") else title
}
