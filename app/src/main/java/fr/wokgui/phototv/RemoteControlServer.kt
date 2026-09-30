package fr.wokgui.phototv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors

data class RemoteControlState(
    val title: String,
    val album: String,
    val slideshow: Boolean,
    val paused: Boolean,
    val durationSeconds: Int,
    val transition: String,
    val imageMode: String
)

class RemoteControlServer(
    private val port: Int = 8765,
    private val token: String,
    private val stateProvider: () -> RemoteControlState,
    private val onCommand: (String) -> Unit
) {
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        executor.execute {
            runCatching {
                ServerSocket(port).use { server ->
                    serverSocket = server
                    while (running) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: break
                        executor.execute { handle(socket) }
                    }
                }
            }
            running = false
            serverSocket = null
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
    }

    fun url(): String? = localIpv4()?.let { ip ->
        "http://$ip:$port/?t=$token"
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine().orEmpty()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
            }

            val parts = request.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                respond(s, 405, "text/plain; charset=utf-8", "Méthode non autorisée")
                return
            }

            val target = parts[1]
            val path = target.substringBefore('?')
            val params = parseQuery(target.substringAfter('?', ""))
            if (params["t"] != token) {
                respond(s, 403, "text/plain; charset=utf-8", "Accès refusé")
                return
            }

            if (path == "/status") {
                val st = stateProvider()
                val json = """{"title":"${jsonEscape(st.title)}","album":"${jsonEscape(st.album)}","slideshow":${st.slideshow},"paused":${st.paused},"duration":${st.durationSeconds},"transition":"${jsonEscape(st.transition)}","imageMode":"${jsonEscape(st.imageMode)}"}"""
                respond(s, 200, "application/json; charset=utf-8", json)
                return
            }

            if (path == "/action") {
                val cmd = params["cmd"].orEmpty()
                if (cmd in setOf("prev", "next", "pause", "stop", "favorite", "hide", "duration_down", "duration_up", "transition_next", "mode_next", "album_next", "history_prev", "sources")) {
                    onCommand(cmd)
                    respond(s, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                } else {
                    respond(s, 400, "application/json; charset=utf-8", "{\"ok\":false}")
                }
                return
            }

            respond(s, 200, "text/html; charset=utf-8", page())
        }
    }

    private fun page(): String = """
<!doctype html>
<html lang="fr">
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Photo TV</title>
<style>
body{font-family:system-ui,sans-serif;background:#07121e;color:white;margin:0;padding:24px}
main{max-width:520px;margin:auto}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
button{font:inherit;font-size:18px;padding:20px 12px;border:0;border-radius:14px;background:#126fe8;color:white}
button.wide{grid-column:1/-1}
small{display:block;margin-top:18px;color:#9fb3c8}
</style>
</head>
<body>
<main>
<h1>Photo TV</h1>
<div class="grid">
<button onclick="send('prev')">◀ Précédente</button>
<button onclick="send('next')">Suivante ▶</button>
<button class="wide" onclick="send('pause')">Pause / reprise</button>
<button onclick="send('favorite')">★ Favori</button>
<button onclick="send('hide')">Masquer</button>
<button onclick="send('duration_down')">− Durée</button>
<button onclick="send('duration_up')">+ Durée</button>
<button onclick="send('transition_next')">Transition</button>
<button onclick="send('mode_next')">Affichage</button>
<button onclick="send('album_next')">Album suivant</button>
<button onclick="send('history_prev')">Historique</button>
<button onclick="send('sources')">Sources</button><button onclick="send('stop')">Arrêter</button>
<div id="status" style="grid-column:1/-1;background:#0d2134;border-radius:14px;padding:16px"></div>
</div>
<small>Réseau local uniquement. Le lien secret est affiché dans Photo TV.</small>
<script>
const t=new URLSearchParams(location.search).get('t');
function send(cmd){
  fetch('/action?t='+encodeURIComponent(t)+'&cmd='+encodeURIComponent(cmd)).then(refresh).catch(()=>{});
}
function refresh(){
  fetch('/status?t='+encodeURIComponent(t))
    .then(r=>r.json())
    .then(s=>{
      document.getElementById('status').textContent=
        s.title+' • '+s.album+' • '+s.duration+' s • '+s.transition+' • '+s.imageMode+(s.paused?' • pause':'');
    })
    .catch(()=>{});
}
refresh();
setInterval(refresh,2000);
</script>
</main>
</body>
</html>
""".trimIndent()

    private fun respond(socket: Socket, status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $status $statusText\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun jsonEscape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&')
            .mapNotNull {
                if (it.isBlank()) return@mapNotNull null
                val key = URLDecoder.decode(it.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                key to value
            }
            .toMap()

    private fun localIpv4(): String? {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrNull() ?: return null

        return interfaces
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { address ->
                address.startsWith("192.168.") ||
                    address.startsWith("10.") ||
                    Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(address)
            }
    }
}
