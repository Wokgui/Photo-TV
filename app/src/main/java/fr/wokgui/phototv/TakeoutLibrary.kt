package fr.wokgui.phototv

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedHashMap

object TakeoutLibrary {
    fun load(context: Context, treeUri: Uri): List<PhotoItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val raw = mutableListOf<PhotoItem>()
        scanFolder(context, root, raw)
        return mergeAlbumMemberships(raw)
    }

    private fun scanFolder(context: Context, dir: DocumentFile, out: MutableList<PhotoItem>) {
        val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
        val media = children.filter {
            it.isFile && (
                it.type?.startsWith("image/") == true ||
                    it.type?.startsWith("video/") == true
            )
        }
        val jsons = children.filter { it.isFile && it.name?.endsWith(".json", true) == true }
        val albumName = exactAlbumName(context, dir, jsons)

        for (file in media) {
            val mediaName = file.name ?: continue
            val sidecar = jsons.firstOrNull { json ->
                val n = json.name?.removeSuffix(".json").orEmpty()
                n == mediaName || n.startsWith(mediaName)
            }

            var takenAt = file.lastModified()
            var description = ""
            var location = ""
            var camera = ""

            if (sidecar != null) {
                runCatching {
                    val text = context.contentResolver.openInputStream(sidecar.uri)!!.use {
                        BufferedReader(InputStreamReader(it)).readText()
                    }
                    val rootJson = JSONObject(text)
                    rootJson.optJSONObject("photoTakenTime")
                        ?.optString("timestamp")
                        ?.toLongOrNull()
                        ?.let { takenAt = it * 1000L }

                    description = rootJson.optString("description").trim()

                    val geo = rootJson.optJSONObject("geoDataExif")
                        ?: rootJson.optJSONObject("geoData")
                    if (geo != null) {
                        val lat = geo.optDouble("latitude", 0.0)
                        val lon = geo.optDouble("longitude", 0.0)
                        if (lat != 0.0 || lon != 0.0) {
                            location = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)
                        }
                    }

                    camera = listOf(
                        rootJson.optString("cameraMake").trim(),
                        rootJson.optString("cameraModel").trim()
                    ).filter { it.isNotBlank() }.joinToString(" ")
                }
            }

            val mime = file.type.orEmpty()
            val dims = mediaDimensions(context, file.uri, mime)
            out += PhotoItem(
                uri = file.uri,
                title = stripExtension(mediaName),
                albums = linkedSetOf(albumName),
                takenAt = takenAt,
                description = description,
                location = location,
                camera = camera,
                width = dims.first,
                height = dims.second,
                mediaType = if (mime.startsWith("video/")) "video" else "image"
            )
        }

        children.filter { it.isDirectory }.forEach { scanFolder(context, it, out) }
    }

    private fun exactAlbumName(context: Context, dir: DocumentFile, jsons: List<DocumentFile>): String {
        val ordered = jsons.sortedBy { if (it.name.equals("metadata.json", true)) 0 else 1 }
        for (jsonFile in ordered) {
            val exact = runCatching {
                val text = context.contentResolver.openInputStream(jsonFile.uri)!!.use {
                    BufferedReader(InputStreamReader(it)).readText()
                }
                JSONObject(text)
                    .optJSONObject("albumData")
                    ?.optString("title")
                    ?.trim()
                    .orEmpty()
            }.getOrDefault("")
            if (exact.isNotBlank()) return exact
        }
        return dir.name ?: "Album"
    }

    private fun mergeAlbumMemberships(raw: List<PhotoItem>): List<PhotoItem> {
        val merged = LinkedHashMap<String, PhotoItem>()
        for (item in raw) {
            val key = buildString {
                append(item.title.lowercase())
                append('|')
                append(item.takenAt)
                append('|')
                append(item.width)
                append('x')
                append(item.height)
                append('|')
                append(item.mediaType)
            }

            val existing = merged[key]
            if (existing == null) {
                merged[key] = item
            } else {
                merged[key] = existing.copy(
                    albums = LinkedHashSet<String>().apply {
                        addAll(existing.albums)
                        addAll(item.albums)
                    },
                    description = existing.description.ifBlank { item.description },
                    location = existing.location.ifBlank { item.location },
                    camera = existing.camera.ifBlank { item.camera },
                    width = if (existing.width > 0) existing.width else item.width,
                    height = if (existing.height > 0) existing.height else item.height
                )
            }
        }
        return merged.values.toList()
    }

    private fun mediaDimensions(context: Context, uri: Uri, mime: String): Pair<Int, Int> {
        return if (mime.startsWith("video/")) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    w to h
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrDefault(0 to 0)
        } else {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                opts.outWidth to opts.outHeight
            }.getOrDefault(0 to 0)
        }
    }

    private fun stripExtension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
