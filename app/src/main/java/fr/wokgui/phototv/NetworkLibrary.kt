package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Properties

object NetworkLibrary {
    enum class Kind { WEBDAV, SMB }

    private data class Config(
        val key: String,
        val kind: Kind,
        val baseUrl: String,
        val username: String,
        val password: String
    )

    private data class Entry(
        val id: String,
        val sourceKey: String,
        val sourceLabel: String,
        val remoteUrl: String,
        val title: String,
        val album: String,
        val mediaType: String
    )

    private data class DavNode(
        val url: String,
        val decodedPath: String,
        val collection: Boolean,
        val contentType: String
    )

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val configs = linkedMapOf<String, Config>()
    private val entries = linkedMapOf<String, Entry>()

    private const val MAX_NETWORK_ITEMS = 5000
    private const val MAX_RECURSION_DEPTH = 8
    private const val CACHE_MAX_FILES = 300
    @Volatile private var cacheMaxBytes = 512L * 1024L * 1024L
    @Volatile private var cacheFreshMs = 24L * 60L * 60L * 1000L
    @Volatile private var offlineUntilMs = 0L

    fun load(
        kind: Kind,
        baseUrl: String,
        username: String,
        password: String
    ): List<PhotoItem> {
        val clean = normalizeBase(kind, baseUrl)
        val cleanUser = username.trim()
        val cfg = Config(sourceKey(kind, clean, cleanUser), kind, clean, cleanUser, password)
        val discovered = when (kind) {
            Kind.WEBDAV -> listWebDavRecursive(cfg)
            Kind.SMB -> listSmbRecursive(cfg)
        }.take(MAX_NETWORK_ITEMS)

        synchronized(entries) {
            entries.entries.removeAll { it.value.sourceKey == cfg.key }
            discovered.forEach { entries[it.id] = it }
            configs[cfg.key] = cfg
        }

        return discovered.map(::toPhotoItem)
    }

    fun open(context: Context, uri: Uri): InputStream? {
        if (!isNetworkUri(uri)) return null

        val id = uri.host ?: uri.schemeSpecificPart.removePrefix("//")
        val pair = synchronized(entries) {
            val entry = entries[id] ?: return null
            val cfg = configs[entry.sourceKey] ?: return null
            cfg to entry
        }

        val cacheDir = File(context.cacheDir, "network-media").apply { mkdirs() }
        val cached = File(cacheDir, pair.second.id + ".bin")
        val now = System.currentTimeMillis()

        if (cached.isFile && cached.length() > 0L && now - cached.lastModified() <= cacheFreshMs) {
            cached.setLastModified(now)
            return cached.inputStream()
        }

        val staleExists = cached.isFile && cached.length() > 0L
        val tmp = File(cacheDir, pair.second.id + ".tmp")
        tmp.delete()

        val downloaded = runCatching {
            openRemote(pair.first, pair.second.remoteUrl)?.use { input ->
                tmp.outputStream().buffered().use { output ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            } ?: return@runCatching false

            if (tmp.length() <= 0L) return@runCatching false
            if (cached.exists()) cached.delete()
            if (!tmp.renameTo(cached)) {
                tmp.copyTo(cached, overwrite = true)
                tmp.delete()
            }
            cached.setLastModified(now)
            trimCache(cacheDir)
            offlineUntilMs = 0L
            true
        }.getOrElse {
            false
        }

        if (!downloaded) offlineUntilMs = now + 60_000L

        return when {
            downloaded && cached.isFile -> cached.inputStream()
            staleExists -> {
                cached.setLastModified(now)
                cached.inputStream()
            }
            else -> {
                tmp.delete()
                null
            }
        }
    }

    fun isNetworkUri(uri: Uri): Boolean = uri.scheme == "phototv-network"

    fun isTemporarilyOffline(): Boolean = System.currentTimeMillis() < offlineUntilMs

    fun isCached(context: Context, uri: Uri): Boolean {
        if (!isNetworkUri(uri)) return false
        val id = uri.host ?: uri.schemeSpecificPart.removePrefix("//")
        val file = File(File(context.cacheDir, "network-media"), id + ".bin")
        return file.isFile && file.length() > 0L
    }

    fun canUseOffline(context: Context, uri: Uri): Boolean =
        !isNetworkUri(uri) || !isTemporarilyOffline() || isCached(context, uri)

    fun materialize(context: Context, uri: Uri): File? {
        if (!isNetworkUri(uri)) return null
        val id = uri.host ?: uri.schemeSpecificPart.removePrefix("//")
        open(context, uri)?.use { }
        return File(File(context.cacheDir, "network-media"), id + ".bin")
            .takeIf { it.isFile && it.length() > 0L }
    }

    fun isConfigured(): Boolean = synchronized(entries) { configs.isNotEmpty() }

    fun configureCache(maxMb: Int, ttlHours: Int) {
        cacheMaxBytes = maxMb.coerceIn(64, 2048).toLong() * 1024L * 1024L
        cacheFreshMs = ttlHours.coerceIn(1, 168).toLong() * 60L * 60L * 1000L
    }

    fun cacheStats(context: Context): Pair<Int, Long> {
        val files = File(context.cacheDir, "network-media")
            .listFiles()
            ?.filter { it.isFile && it.extension == "bin" }
            .orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    fun prefetch(context: Context, uris: List<Uri>) {
        uris.filter(::isNetworkUri).distinct().forEach { uri ->
            runCatching { open(context, uri)?.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (input.read(buffer) > 0) Unit
            } }
        }
    }

    fun reload(): List<PhotoItem>? {
        val snapshot = synchronized(entries) { configs.values.toList() }
        if (snapshot.isEmpty()) return null

        val all = mutableListOf<Entry>()
        snapshot.forEach { cfg ->
            val discovered = when (cfg.kind) {
                Kind.WEBDAV -> listWebDavRecursive(cfg)
                Kind.SMB -> listSmbRecursive(cfg)
            }.take(MAX_NETWORK_ITEMS)
            all += discovered
        }

        synchronized(entries) {
            entries.clear()
            all.forEach { entries[it.id] = it }
        }

        return all.map(::toPhotoItem)
    }

    fun removeSource(kind: Kind, baseUrl: String, username: String): String? {
        val clean = normalizeBase(kind, baseUrl)
        val key = sourceKey(kind, clean, username.trim())
        val removed = synchronized(entries) {
            configs.remove(key)
            val label = entries.values.firstOrNull { it.sourceKey == key }?.sourceLabel
            entries.entries.removeAll { it.value.sourceKey == key }
            label
        }
        return removed
    }

    fun clearDiskCache(context: Context) {
        File(context.cacheDir, "network-media")
            .takeIf { it.exists() }
            ?.listFiles()
            ?.forEach { it.delete() }
    }

    private fun normalizeBase(kind: Kind, raw: String): String {
        val trimmed = raw.trim()
        return when (kind) {
            Kind.WEBDAV -> {
                val value = if (
                    trimmed.startsWith("http://", true) ||
                    trimmed.startsWith("https://", true)
                ) trimmed else "https://$trimmed"
                if (value.endsWith("/")) value else "$value/"
            }

            Kind.SMB -> {
                val value = if (trimmed.startsWith("smb://", true)) trimmed else "smb://$trimmed"
                if (value.endsWith("/")) value else "$value/"
            }
        }
    }

    private fun listWebDavRecursive(cfg: Config): List<Entry> {
        data class Pending(val url: String, val depth: Int, val albumPath: String)

        val rootUri = URI(cfg.baseUrl)
        val rootPath = decodePath(rootUri.path.orEmpty()).trimEnd('/')
        val rootAlbum = rootPath.substringAfterLast('/').ifBlank { "WebDAV" }
        val queue = ArrayDeque<Pending>()
        val visited = linkedSetOf<String>()
        val out = mutableListOf<Entry>()

        queue.add(Pending(cfg.baseUrl, 0, rootAlbum))

        while (queue.isNotEmpty() && out.size < MAX_NETWORK_ITEMS) {
            val pending = queue.removeFirst()
            val normalized = pending.url.trimEnd('/') + "/"
            if (!visited.add(normalized)) continue

            val nodes = propfind(cfg, normalized)
            for (node in nodes) {
                val nodeNormalized = node.url.trimEnd('/') + "/"
                if (nodeNormalized == normalized) continue

                if (node.collection) {
                    if (pending.depth < MAX_RECURSION_DEPTH && visited.size < MAX_NETWORK_ITEMS) {
                        val folder = node.decodedPath.trimEnd('/').substringAfterLast('/').ifBlank { "Album" }
                        val albumPath = if (pending.albumPath.isBlank()) folder else pending.albumPath + " / " + folder
                        queue.add(Pending(nodeNormalized, pending.depth + 1, albumPath))
                    }
                    continue
                }

                val name = node.decodedPath.substringAfterLast('/').ifBlank { "Photo" }
                val type = mediaType(name, node.contentType) ?: continue
                out += Entry(
                    id = stableId(cfg.kind, node.url),
                    sourceKey = cfg.key,
                    sourceLabel = sourceDisplayLabel(cfg),
                    remoteUrl = node.url,
                    title = stripExtension(name),
                    album = pending.albumPath.ifBlank { rootAlbum },
                    mediaType = type
                )
                if (out.size >= MAX_NETWORK_ITEMS) break
            }
        }

        return out
    }

    private fun propfind(cfg: Config, url: String): List<DavNode> {
        val bodyText =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""
        val body = bodyText.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())

        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", "1")

        if (cfg.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(cfg.username, cfg.password))
        }

        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 207) {
                throw IllegalStateException("WebDAV HTTP " + response.code)
            }
            return parseWebDav(response.body?.string().orEmpty(), url)
        }
    }

    private fun parseWebDav(xml: String, requestUrl: String): List<DavNode> {
        val parser = XmlPullParserFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newPullParser()
        parser.setInput(StringReader(xml))

        data class Row(
            var href: String = "",
            var contentType: String = "",
            var collection: Boolean = false
        )

        val rows = mutableListOf<Row>()
        var row: Row? = null
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> row = Row()
                    "href" -> row?.href = parser.nextText().trim()
                    "getcontenttype" -> row?.contentType = parser.nextText().trim()
                    "collection" -> row?.collection = true
                }

                XmlPullParser.END_TAG -> if (parser.name.equals("response", true)) {
                    row?.let { rows += it }
                    row = null
                }
            }
            event = parser.next()
        }

        val base = URI(requestUrl)
        return rows.mapNotNull { r ->
            if (r.href.isBlank()) return@mapNotNull null
            val resolved = runCatching { base.resolve(r.href) }.getOrNull() ?: return@mapNotNull null
            DavNode(
                url = resolved.toString(),
                decodedPath = decodePath(resolved.path.orEmpty()),
                collection = r.collection,
                contentType = r.contentType
            )
        }
    }

    private fun listSmbRecursive(cfg: Config): List<Entry> {
        val out = mutableListOf<Entry>()
        val root = SmbFile(cfg.baseUrl, smbContext(cfg))
        val rootAlbum = cfg.baseUrl.trimEnd('/').substringAfterLast('/').ifBlank { "SMB" }

        fun walk(dir: SmbFile, depth: Int, albumPath: String) {
            if (depth > MAX_RECURSION_DEPTH || out.size >= MAX_NETWORK_ITEMS) return

            val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
            for (child in children) {
                if (out.size >= MAX_NETWORK_ITEMS) break

                if (runCatching { child.isDirectory }.getOrDefault(false)) {
                    val folder = child.name.trimEnd('/').ifBlank { "Album" }
                    val nestedAlbum = if (albumPath.isBlank()) folder else albumPath + " / " + folder
                    walk(child, depth + 1, nestedAlbum)
                    continue
                }

                if (!runCatching { child.isFile }.getOrDefault(false)) continue
                val name = child.name.trimEnd('/')
                val type = mediaType(name, null) ?: continue
                val remote = child.canonicalPath

                out += Entry(
                    id = stableId(cfg.kind, remote),
                    sourceKey = cfg.key,
                    sourceLabel = sourceDisplayLabel(cfg),
                    remoteUrl = remote,
                    title = stripExtension(name),
                    album = albumPath.ifBlank { rootAlbum },
                    mediaType = type
                )
            }
        }

        walk(root, 0, rootAlbum)
        return out
    }

    private fun openRemote(cfg: Config, url: String): InputStream? =
        when (cfg.kind) {
            Kind.WEBDAV -> openWebDav(cfg, url)
            Kind.SMB -> openSmb(cfg, url)
        }

    private fun openWebDav(cfg: Config, url: String): InputStream? {
        val builder = Request.Builder().url(url).get()
        if (cfg.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(cfg.username, cfg.password))
        }

        val response = http.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }

        val stream = response.body?.byteStream() ?: run {
            response.close()
            return null
        }

        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    private fun smbContext(cfg: Config): jcifs.CIFSContext {
        val base = BaseContext(PropertyConfiguration(Properties()))
        return if (cfg.username.isBlank()) {
            base
        } else {
            base.withCredentials(NtlmPasswordAuthenticator(null, cfg.username, cfg.password))
        }
    }

    private fun openSmb(cfg: Config, url: String): InputStream? =
        runCatching {
            val file = SmbFile(url, smbContext(cfg))
            file.inputStream
        }.getOrNull()

    private fun trimCache(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension == "bin" }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList()
            ?: return

        var totalBytes = files.sumOf { it.length() }
        while (files.size > CACHE_MAX_FILES || totalBytes > cacheMaxBytes) {
            val first = files.removeFirstOrNull() ?: break
            totalBytes -= first.length()
            first.delete()
        }
    }

    private fun toPhotoItem(e: Entry): PhotoItem =
        PhotoItem(
            uri = Uri.parse("phototv-network://" + e.id),
            title = e.title,
            albums = linkedSetOf(e.album),
            mediaType = e.mediaType,
            sourceId = e.remoteUrl,
            sourceLabel = e.sourceLabel
        )

    private fun sourceDisplayLabel(cfg: Config): String {
        val prefix = if (cfg.kind == Kind.WEBDAV) "WebDAV" else "SMB"
        val clean = cfg.baseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("smb://")
            .trimEnd('/')
        return prefix + " • " + clean
    }

    private fun sourceKey(kind: Kind, baseUrl: String, username: String): String =
        stableId(kind, baseUrl + "|" + username.lowercase())

    private fun stableId(kind: Kind, remoteUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((kind.name + "|" + remoteUrl).toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun decodePath(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun mediaType(name: String, contentType: String?): String? {
        val lower = name.lowercase()
        return when {
            contentType?.startsWith("video/") == true ||
                lower.endsWith(".mp4") ||
                lower.endsWith(".mkv") ||
                lower.endsWith(".webm") -> "video"

            contentType.equals("image/gif", true) || lower.endsWith(".gif") -> "gif"

            contentType?.startsWith("image/") == true ||
                listOf(
                    ".jpg", ".jpeg", ".png", ".webp",
                    ".heic", ".heif", ".bmp", ".avif"
                ).any { lower.endsWith(it) } -> "image"

            else -> null
        }
    }

    private fun stripExtension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
