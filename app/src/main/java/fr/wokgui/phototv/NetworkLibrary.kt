package fr.wokgui.phototv

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
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.Properties
import java.util.UUID

object NetworkLibrary {
    enum class Kind { WEBDAV, SMB }

    private data class Config(val kind: Kind, val baseUrl: String, val username: String, val password: String)
    private data class Entry(val id: String, val remoteUrl: String, val title: String, val album: String, val mediaType: String)

    private val http = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    @Volatile private var config: Config? = null
    private val entries = linkedMapOf<String, Entry>()

    fun load(kind: Kind, baseUrl: String, username: String, password: String): List<PhotoItem> {
        val clean = normalizeBase(kind, baseUrl)
        val cfg = Config(kind, clean, username.trim(), password)
        val discovered = when (kind) {
            Kind.WEBDAV -> listWebDav(cfg)
            Kind.SMB -> listSmb(cfg)
        }
        synchronized(entries) {
            entries.clear()
            discovered.forEach { entries[it.id] = it }
            config = cfg
        }
        return discovered.map { e ->
            PhotoItem(
                uri = Uri.parse("phototv-network://" + e.id),
                title = e.title,
                albums = linkedSetOf(e.album),
                mediaType = e.mediaType,
                sourceId = e.remoteUrl
            )
        }
    }

    fun open(uri: Uri): InputStream? {
        if (uri.scheme != "phototv-network") return null
        val id = uri.host ?: uri.schemeSpecificPart.removePrefix("//")
        val pair = synchronized(entries) {
            val cfg = config ?: return null
            val entry = entries[id] ?: return null
            cfg to entry
        }
        return when (pair.first.kind) {
            Kind.WEBDAV -> openWebDav(pair.first, pair.second.remoteUrl)
            Kind.SMB -> openSmb(pair.first, pair.second.remoteUrl)
        }
    }

    fun isNetworkUri(uri: Uri): Boolean = uri.scheme == "phototv-network"

    private fun normalizeBase(kind: Kind, raw: String): String {
        val trimmed = raw.trim()
        return when (kind) {
            Kind.WEBDAV -> {
                val value = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://" + trimmed
                if (value.endsWith("/")) value else value + "/"
            }
            Kind.SMB -> {
                val value = if (trimmed.startsWith("smb://")) trimmed else "smb://" + trimmed
                if (value.endsWith("/")) value else value + "/"
            }
        }
    }

    private fun listWebDav(cfg: Config): List<Entry> {
        val xmlBody = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""
        val body = xmlBody.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
        val builder = Request.Builder().url(cfg.baseUrl).method("PROPFIND", body).header("Depth", "1")
        if (cfg.username.isNotBlank()) builder.header("Authorization", Credentials.basic(cfg.username, cfg.password))
        val response = http.newCall(builder.build()).execute()
        response.use {
            if (!it.isSuccessful && it.code != 207) throw IllegalStateException("WebDAV HTTP " + it.code)
            return parseWebDav(it.body?.string().orEmpty(), cfg.baseUrl)
        }
    }

    private fun parseWebDav(xml: String, baseUrl: String): List<Entry> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        data class Row(var href: String = "", var contentType: String = "", var collection: Boolean = false)
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
        val basePath = Uri.parse(baseUrl).path.orEmpty().trimEnd('/')
        return rows.mapNotNull { r ->
            if (r.collection || r.href.isBlank()) return@mapNotNull null
            val hrefUri = Uri.parse(r.href)
            val decodedPath = runCatching { URLDecoder.decode(hrefUri.path.orEmpty(), "UTF-8") }.getOrDefault(hrefUri.path.orEmpty())
            if (decodedPath.trimEnd('/') == basePath) return@mapNotNull null
            val remote = if (r.href.startsWith("http://") || r.href.startsWith("https://")) {
                r.href
            } else {
                val root = Uri.parse(baseUrl)
                root.buildUpon().encodedPath(hrefUri.encodedPath).build().toString()
            }
            val name = decodedPath.substringAfterLast('/').ifBlank { "Photo" }
            val type = mediaType(name, r.contentType) ?: return@mapNotNull null
            Entry(UUID.randomUUID().toString(), remote, stripExtension(name), basePath.substringAfterLast('/').ifBlank { "WebDAV" }, type)
        }
    }

    private fun openWebDav(cfg: Config, url: String): InputStream? {
        val builder = Request.Builder().url(url).get()
        if (cfg.username.isNotBlank()) builder.header("Authorization", Credentials.basic(cfg.username, cfg.password))
        val response = http.newCall(builder.build()).execute()
        if (!response.isSuccessful) { response.close(); return null }
        val stream = response.body?.byteStream() ?: run { response.close(); return null }
        return object : FilterInputStream(stream) {
            override fun close() {
                try { super.close() } finally { response.close() }
            }
        }
    }

    private fun smbContext(cfg: Config): jcifs.CIFSContext {
        val base = BaseContext(PropertyConfiguration(Properties()))
        return if (cfg.username.isBlank()) base else base.withCredentials(NtlmPasswordAuthenticator(null, cfg.username, cfg.password))
    }

    private fun listSmb(cfg: Config): List<Entry> {
        val ctx = smbContext(cfg)
        val root = SmbFile(cfg.baseUrl, ctx)
        val album = cfg.baseUrl.trimEnd('/').substringAfterLast('/').ifBlank { "SMB" }
        return root.listFiles().asSequence().filter { it.isFile }.mapNotNull { file ->
            val name = file.name.trimEnd('/')
            val type = mediaType(name, null) ?: return@mapNotNull null
            Entry(UUID.randomUUID().toString(), file.canonicalPath, stripExtension(name), album, type)
        }.toList()
    }

    private fun openSmb(cfg: Config, url: String): InputStream? = runCatching { SmbFile(url, smbContext(cfg)).inputStream }.getOrNull()

    private fun mediaType(name: String, contentType: String?): String? {
        val lower = name.lowercase()
        return when {
            contentType?.startsWith("video/") == true || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") -> "video"
            contentType.equals("image/gif", true) || lower.endsWith(".gif") -> "gif"
            contentType?.startsWith("image/") == true || listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".avif").any { lower.endsWith(it) } -> "image"
            else -> null
        }
    }

    private fun stripExtension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}