package fr.wokgui.phototv

import android.content.Context
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

    fun load(
        context: Context,
        treeUri: Uri,
        exactMode: Boolean = true,
        onProgress: ((foldersScanned: Int, mediaFound: Int) -> Unit)? = null
    ): LoadResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return LoadResult(emptyList(), exactAlbums = false, missingExactAlbumFolders = 0)

        val raw = mutableListOf<PhotoItem>()
        val exactState = ExactState()
        scanFolder(context, root, raw, exactMode, exactState, onProgress)

        val merged = mergeAlbumMemberships(context, raw)
        return LoadResult(
            items = VisualDuplicateDetector.merge(context, merged),
            exactAlbums = exactMode && exactState.foldersWithMedia > 0 && exactState.missingAlbumMetadata == 0,
            missingExactAlbumFolders = exactState.missingAlbumMetadata
        )
    }

    private data class ExactState(
        var foldersWithMedia: Int = 0,
        var missingAlbumMetadata: Int = 0,
        var foldersScanned: Int = 0,
        var mediaFound: Int = 0
    )

    private fun scanFolder(
        context: Context,
        dir: DocumentFile,
        out: MutableList<PhotoItem>,
        exactMode: Boolean,
        state: ExactState,
        onProgress: ((foldersScanned: Int, mediaFound: Int) -> Unit)?
    ) {
        val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
        val directories = children.filter { it.isDirectory }
        val folderSignature = FolderScanIndex.signature(children, exactMode)

        state.foldersScanned++
        FolderScanIndex.read(context, dir.uri, exactMode, folderSignature)?.let { cached ->
            out += cached.items
            state.mediaFound += cached.items.size
            if (cached.items.isNotEmpty()) {
                state.foldersWithMedia++
                if (exactMode && cached.missingExactAlbumMetadata) {
                    state.missingAlbumMetadata++
                }
            }
            onProgress?.invoke(state.foldersScanned, state.mediaFound)
            directories.forEach {
                scanFolder(context, it, out, exactMode, state, onProgress)
            }
            return
        }

        val media = children.filter {
            it.isFile && MediaTypeDetector.classify(it.name, it.type) != null
        }
        val jsons = children.filter { it.isFile && it.name?.endsWith(".json", true) == true }

        state.mediaFound += media.size
        onProgress?.invoke(state.foldersScanned, state.mediaFound)
        val folderOutputStart = out.size

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

            val mime = file.type.orEmpty()
            val info = MediaInfoReader.read(context, file.uri, mime, mediaName)
            var takenAt = info.takenAt.takeIf { it > 0 } ?: file.lastModified()
            var description = ""
            var location = info.location
            var camera = info.camera
            var sourceId = ""

            if (rootJson != null) {
                rootJson.optJSONObject("photoTakenTime")
                    ?.optString("timestamp")
                    ?.toLongOrNull()
                    ?.let { takenAt = it * 1000L }

                description = rootJson.optString("description").trim()
                sourceId = rootJson.optString("url").trim()

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

            out += PhotoItem(
                uri = file.uri,
                title = stripExtension(mediaName),
                albums = linkedSetOf(albumName),
                takenAt = takenAt,
                description = description,
                location = location,
                camera = camera,
                width = info.width,
                height = info.height,
                mediaType = MediaTypeDetector.classify(mediaName, mime) ?: "image",
                sourceId = sourceId
            )
        }

        val folderItems = out.subList(folderOutputStart, out.size).toList()
        FolderScanIndex.write(
            context = context,
            folderUri = dir.uri,
            exactMode = exactMode,
            signature = folderSignature,
            items = folderItems,
            missingExactAlbumMetadata = exactMode && media.isNotEmpty() && exactName == null
        )

        directories.forEach {
            scanFolder(context, it, out, exactMode, state, onProgress)
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
            if (item.sourceId.isNotBlank()) {
                "source:" + item.sourceId
            } else {
                buildString {
                    append(item.title.lowercase())
                    append('|')
                    append(item.width)
                    append('x')
                    append(item.height)
                    append('|')
                    append(item.mediaType)
                }
            }
        }

        fun combine(existing: PhotoItem, item: PhotoItem): PhotoItem =
            existing.copy(
                albums = LinkedHashSet<String>().apply {
                    addAll(existing.albums)
                    addAll(item.albums)
                },
                description = existing.description.ifBlank { item.description },
                location = existing.location.ifBlank { item.location },
                camera = existing.camera.ifBlank { item.camera },
                width = if (existing.width > 0) existing.width else item.width,
                height = if (existing.height > 0) existing.height else item.height,
                sourceCopies = existing.sourceCopies + item.sourceCopies,
                sourceId = existing.sourceId.ifBlank { item.sourceId }
            )

        val merged = mutableListOf<PhotoItem>()
        roughGroups.values.forEach { group ->
            when {
                group.size == 1 -> merged += group.first()

                group.first().sourceId.isNotBlank() -> {
                    // A Google Photos URL is already a stable identity. Avoid hashing full
                    // image/video copies, which can make large Takeout imports very slow.
                    merged += group.drop(1).fold(group.first(), ::combine)
                }

                else -> {
                    val byFingerprint = LinkedHashMap<String, PhotoItem>()
                    group.forEachIndexed { index, item ->
                        val fingerprint = mediaFingerprint(context, item.uri)
                            ?: "unhashed:${item.uri}:$index"
                        val existing = byFingerprint[fingerprint]
                        byFingerprint[fingerprint] =
                            if (existing == null) item else combine(existing, item)
                    }
                    merged += byFingerprint.values
                }
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

    private fun stripExtension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
