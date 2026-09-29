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
    data class LoadResult(
        val items: List<PhotoItem>,
        val exactAlbums: Boolean,
        val missingExactAlbumFolders: Int
    )

    fun load(context: Context, treeUri: Uri, exactMode: Boolean = true): LoadResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return LoadResult(emptyList(), exactAlbums = false, missingExactAlbumFolders = 0)

        val raw = mutableListOf<PhotoItem>()
        val exactState = ExactState()
        scanFolder(context, root, raw, exactMode, exactState)

        return LoadResult(
            items = mergeAlbumMemberships(context, raw),
            exactAlbums = exactMode && exactState.foldersWithMedia > 0 && exactState.missingAlbumMetadata == 0,
            missingExactAlbumFolders = exactState.missingAlbumMetadata
        )
    }

    private data class ExactState(
        var foldersWithMedia: Int = 0,
        var missingAlbumMetadata: Int = 0
    )

    private fun scanFolder(
        context: Context,
        dir: DocumentFile,
        out: MutableList<PhotoItem>,
        exactMode: Boolean,
        state: ExactState
    ) {
        val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
        val media = children.filter {
            it.isFile && (
                it.type?.startsWith("image/") == true ||
                    it.type?.startsWith("video/") == true
            )
        }
        val jsons = children.filter { it.isFile && it.name?.endsWith(".json", true) == true }
        val parsedJson = jsons.mapNotNull { file ->
            readJson(context, file)?.let { file to it }
        }

        val exactName = if (exactMode) exactAlbumName(parsedJson) else null
        val albumName = when {
            exactName != null -> exactName
            exactMode -> "Album indisponible — métadonnées Takeout absentes"
            else -> dir.name?.trim().orEmpty().ifBlank { "Dossier local" }
        }

        if (media.isNotEmpty()) {
            state.foldersWithMedia++
            if (exactMode && exactName == null) state.missingAlbumMetadata++
        }

        val byDeclaredTitle = linkedMapOf<String, JSONObject>()
        parsedJson.forEach { (_, root) ->
            root.optString("title").trim().takeIf { it.isNotBlank() }?.let {
                byDeclaredTitle[it] = root
            }
        }

        for (file in media) {
            val mediaName = file.name ?: continue
            val filenameRoot = parsedJson.firstOrNull { (json, _) ->
                val n = json.name?.removeSuffix(".json").orEmpty()
                n == mediaName || n.startsWith(mediaName)
            }?.second
            val rootJson = filenameRoot ?: byDeclaredTitle[mediaName]

            var takenAt = file.lastModified()
            var description = ""
            var location = ""
            var camera = ""

            if (rootJson != null) {
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
                mediaType = when {
                    mime.startsWith("video/") -> "video"
                    mime.equals("image/gif", true) -> "gif"
                    else -> "image"
                }
            )
        }

        children.filter { it.isDirectory }.forEach {
            scanFolder(context, it, out, exactMode, state)
        }
    }

    private fun readJson(context: Context, file: DocumentFile): JSONObject? {
        return runCatching {
            val text = context.contentResolver.openInputStream(file.uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            } ?: return@runCatching null
            JSONObject(text)
        }.getOrNull()
    }

    private fun exactAlbumName(parsed: List<Pair<DocumentFile, JSONObject>>): String? {
        val ordered = parsed.sortedBy { (file, _) ->
            if (file.name.equals("metadata.json", true)) 0 else 1
        }
        for ((_, root) in ordered) {
            val exact = root
                .optJSONObject("albumData")
                ?.optString("title")
                ?.trim()
                .orEmpty()
            if (exact.isNotBlank()) return exact
        }
        return null
    }

    private fun mergeAlbumMemberships(context: Context, raw: List<PhotoItem>): List<PhotoItem> {
        val roughGroups = raw.groupBy { item ->
            buildString {
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
        }

        val merged = mutableListOf<PhotoItem>()
        roughGroups.values.forEach { group ->
            if (group.size == 1) {
                merged += group.first()
            } else {
                val byFingerprint = LinkedHashMap<String, PhotoItem>()
                group.forEachIndexed { index, item ->
                    val fingerprint = mediaFingerprint(context, item.uri)
                        ?: "unhashed:${item.uri}:$index"
                    val existing = byFingerprint[fingerprint]
                    if (existing == null) {
                        byFingerprint[fingerprint] = item
                    } else {
                        byFingerprint[fingerprint] = existing.copy(
                            albums = LinkedHashSet<String>().apply {
                                addAll(existing.albums)
                                addAll(item.albums)
                            },
                            description = existing.description.ifBlank { item.description },
                            location = existing.location.ifBlank { item.location },
                            camera = existing.camera.ifBlank { item.camera },
                            width = if (existing.width > 0) existing.width else item.width,
                            height = if (existing.height > 0) existing.height else item.height,
                            sourceCopies = existing.sourceCopies + item.sourceCopies
                        )
                    }
                }
                merged += byFingerprint.values
            }
        }
        return merged
    }

    private fun mediaFingerprint(context: Context, uri: Uri): String? {
        return runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return@runCatching null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
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
