package fr.wokgui.phototv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkLibraryInstrumentedTest {
    private lateinit var server: FakeWebDavServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NetworkLibrary.clearDiskCache(context)
        server = FakeWebDavServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        NetworkLibrary.clearDiskCache(context)
    }

    @Test
    fun recursiveWebDavAndDiskCacheWork() {
        val base = "http://127.0.0.1:" + server.port + "/photos/"
        val items = NetworkLibrary.load(
            kind = NetworkLibrary.Kind.WEBDAV,
            baseUrl = base,
            username = "",
            password = ""
        )

        assertEquals(2, items.size)
        assertTrue(items.any { it.title == "root" })
        assertTrue(items.any { it.title == "nested" })
        assertTrue(items.any { it.albums.any { album -> album.contains("Album A") } })

        val nested = items.first { it.title == "nested" }
        val first = NetworkLibrary.open(context, nested.uri)?.use { it.readBytes() }
        assertTrue(first != null && first.isNotEmpty())

        val requestsBefore = server.requestCount
        val second = NetworkLibrary.open(context, nested.uri)?.use { it.readBytes() }
        assertTrue(second != null && second.isNotEmpty())
        assertEquals(requestsBefore, server.requestCount)

        val cache = NetworkLibrary.cacheStats(context)
        assertTrue(cache.first >= 1)
        assertTrue(cache.second > 0L)
    }

    @Test
    fun savedNetworkSourceNeverStoresPassword() {
        val secret = "mot-de-passe-test"
        NetworkSourceStore.upsert(
            context = context,
            kind = NetworkLibrary.Kind.WEBDAV,
            baseUrl = "https://example.test/photos/",
            username = "hugo"
        )

        val prefs = context.getSharedPreferences("photo_tv_network_sources", Context.MODE_PRIVATE)
        val raw = prefs.getString("sources", "").orEmpty()
        assertFalse(raw.contains(secret))
        assertTrue(raw.contains("hugo"))
    }

    private class FakeWebDavServer : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val executor = Executors.newCachedThreadPool()
        private val socket = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        @Volatile var requestCount: Int = 0
            private set

        fun start() {
            executor.execute {
                while (running.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    executor.execute { handle(client) }
                }
            }
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            executor.shutdownNow()
        }

        private fun handle(client: Socket) {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine().orEmpty()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                }
                requestCount++

                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()

                when {
                    method == "PROPFIND" && path == "/photos/" -> respondXml(socket, rootListing())
                    method == "PROPFIND" && path == "/photos/album-a/" -> respondXml(socket, nestedListing())
                    method == "GET" && (path == "/photos/root.png" || path == "/photos/album-a/nested.png") ->
                        respondBytes(socket, tinyPng())
                    else -> respond(socket, 404, "text/plain", "not found".toByteArray())
                }
            }
        }

        private fun rootListing(): String = """
<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>/photos/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
  <d:response><d:href>/photos/root.png</d:href><d:propstat><d:prop><d:getcontenttype>image/png</d:getcontenttype><d:resourcetype/></d:prop></d:propstat></d:response>
  <d:response><d:href>/photos/album-a/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
</d:multistatus>
""".trimIndent()

        private fun nestedListing(): String = """
<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>/photos/album-a/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
  <d:response><d:href>/photos/album-a/nested.png</d:href><d:propstat><d:prop><d:getcontenttype>image/png</d:getcontenttype><d:resourcetype/></d:prop></d:propstat></d:response>
</d:multistatus>
""".trimIndent()

        private fun respondXml(socket: Socket, body: String) =
            respond(socket, 207, "application/xml; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

        private fun respondBytes(socket: Socket, body: ByteArray) =
            respond(socket, 200, "image/png", body)

        private fun respond(socket: Socket, status: Int, type: String, body: ByteArray) {
            val text = if (status == 207) "Multi-Status" else if (status == 200) "OK" else "Not Found"
            val out = socket.getOutputStream()
            val headers = "HTTP/1.1 " + status + " " + text + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + body.size + "\r\n" +
                "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(StandardCharsets.UTF_8))
            out.write(body)
            out.flush()
        }

        private fun tinyPng(): ByteArray = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
    }
}
