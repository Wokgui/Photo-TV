package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object PickedLibrary {
    private const val MANUAL_ALBUM_LABEL =
        "Album Google Photos indisponible — utilisez le mode exact"

    fun load(context: Context, uris: List<Uri>): List<PhotoItem> {
        val items = uris.distinct().mapNotNull { uri ->
            runCatching {
                val name = displayName(context, uri)
                val mime = context.contentResolver.getType(uri).orEmpty()
                val info = MediaInfoReader.read(context, uri, mime, name)
                PhotoItem(
                    uri = uri,
                    title = stripExtension(name),
                    albums = linkedSetOf(MANUAL_ALBUM_LABEL),
                    takenAt = info.takenAt,
                    location = info.location,
                    camera = info.camera,
                    width = info.width,
                    height = info.height,
                    mediaType = MediaTypeDetector.classify(name, mime) ?: "image"
                )
            }.getOrNull()
        }
        return VisualDuplicateDetector.merge(context, items)
    }

    private fun displayName(context: Context, uri: Uri): String {
        var result = "Photo"
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = cursor.getString(index) ?: result
                }
            }
        }
        return result
    }

    private fun stripExtension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
