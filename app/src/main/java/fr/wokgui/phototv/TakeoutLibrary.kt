package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedHashMap

object TakeoutLibrary {
    internal fun metadataFor(mediaName: String, metadata: List<Pair<String, JSONObject>>): JSONObject? =
        metadata.firstOrNull { (name, _) ->
                name.equals("$mediaName.json", true) ||
                    name.equals("$mediaName.supplemental-metadata.json", true)
            }?.second
            ?: metadata.filter { (_, json) -> json.optString("title").trim() == mediaName }
                .singleOrNull()?.second

    internal fun importedAlbumName(metadata: List<Pair<String, JSONObject>>): String? {
        val albumFiles = setOf("metadata.json", "métadonnées.json", "metadatos.json", "metadades.json",
            "metadati.json", "metadáta.json", "metadaten.json", "метаданные.json", "метаданни.json")
        for ((name, json) in metadata) {
            val wrapped = json.optJSONObject("albumData")?.optString("title")?.trim().orEmpty()
            if (wrapped.isNotBlank()) return wrapped
            if (name.lowercase(java.util.Locale.ROOT) in albumFiles && !json.has("photoTakenTime") && !json.has("url")) {
                json.optString("title").trim().takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    internal fun importedTitle(mediaName: String, metadata: JSONObject?): String =
        metadata?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: stripExtension(mediaName)

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
        val root = if (treeUri.scheme == "file") {
            val folder = java.io.File(treeUri.path ?: return LoadResult(emptyList(), false, 0)).canonicalFile
            require(folder.path.startsWith(context.filesDir.canonicalPath + java.io.File.separator))
            DocumentFile.fromFile(folder)
        } else DocumentFile.fromTreeUri(context, treeUri)
            ?: return LoadResult(emptyList(), exactAlbums = false, missingExactAlbumFolders = 0)

        val raw = mutableListOf<PhotoItem>()
        val exactState = ExactState()
        scanFolder(context, root, raw, exactMode, exactState, onProgress)

        val merged = mergeAlbumMemberships(context, raw, treeUri.path?.endsWith("/drive-takeout") == true)
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
        val directories = children.filter { it.isDirectory }.sortedBy { it.lastModified() }
        val reliableFolderSignature = FolderScanIndex.isReliable(children)
        val folderSignature = FolderScanIndex.signature(children, exactMode)

        state.foldersScanned++
        if (reliableFolderSignature) FolderScanIndex.read(context, dir.uri, exactMode, folderSignature)?.let { cached ->
                val photos = cached.items.filter { MediaTypeDetector.isPhotoType(it.mediaType) }
                out += photos
                state.mediaFound += photos.size
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
            it.isFile && MediaTypeDetector.classifyPhoto(it.name, it.type) != null
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

        val namedMetadata = parsedJson.map { (file, json) -> file.name.orEmpty() to json }

        for (file in media) {
            val mediaName = file.name ?: continue
            val rootJson = metadataFor(mediaName, namedMetadata)

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
                title = importedTitle(mediaName, rootJson),
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
        if (reliableFolderSignature) {
            FolderScanIndex.write(
                context = context,
                folderUri = dir.uri,
                exactMode = exactMode,
                signature = folderSignature,
                items = folderItems,
                missingExactAlbumMetadata = exactMode && media.isNotEmpty() && exactName == null
            )
        }

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
        return importedAlbumName(parsed.map { (file, json) -> file.name.orEmpty() to json })
    }

    private fun mergeAlbumMemberships(context: Context, raw: List<PhotoItem>, preferLatest: Boolean = false): List<PhotoItem> {
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
            (if (preferLatest) item else existing).copy(
                albums = LinkedHashSet<String>().apply {
                    addAll(existing.albums)
                    addAll(item.albums)
                },
                description = if (preferLatest) item.description else existing.description.ifBlank { item.description },
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
        val mime = context.contentResolver.getType(uri).orEmpty()
        val signature = MediaMetadataStore.signature(context, uri, mime)
        MediaMetadataStore.readExactDigest(context, uri, signature)?.let { return it }

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
            digest.digest().joinToString("") { "%02x".format(it) }.also { sha ->
                MediaMetadataStore.writeExactDigest(context, uri, signature, sha)
            }
        }.getOrNull()
    }

    private fun stripExtension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
