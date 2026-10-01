package fr.wokgui.phototv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class RemoteControlState(
    val title: String,
    val album: String,
    val slideshow: Boolean,
    val paused: Boolean,
    val durationSeconds: Int,
    val transition: String,
    val imageMode: String,
    val albums: List<String>,
    val sources: List<String>,
    val sourceFilter: String?,
    val transitions: List<String>,
    val imageModes: List<String>,
    val smartModes: List<String>,
    val smartMode: String,
    val position: Int = 0,
    val previewTitles: List<String> = emptyList()
)

class RemoteControlServer(
    private val port: Int = 8765,
    private val token: String,
    private val stateProvider: () -> RemoteControlState,
    private val thumbnailProvider: (Int) -> ByteArray? = { null },
    private val onCommand: (String) -> Unit
) {
    companion object {
        private const val MAX_BODY_BYTES = 16 * 1024
        private const val RATE_WINDOW_MS = 2_000L
        private const val RATE_MAX_ACTIONS = 40

        internal fun isPrivateIpv4(value: String): Boolean =
            value.startsWith("192.168.") ||
                value.startsWith("10.") ||
                Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(value)
    }

    private data class RateWindow(var startedAt: Long, var count: Int)

    private val executor = Executors.newCachedThreadPool()
    private val serverSockets = Collections.synchronizedList(mutableListOf<ServerSocket>())
    private val rateWindows = ConcurrentHashMap<String, RateWindow>()
    private val serverAttempts = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var bindAddresses: List<Inet4Address> = emptyList()

    fun start() {
        if (running) return
        val addresses = localIpv4Addresses()
        if (addresses.isEmpty()) return
        bindAddresses = addresses
        serverAttempts.set(addresses.size)
        running = true
        addresses.forEach { address ->
            executor.execute { runServer(address) }
        }
    }

    private fun runServer(address: Inet4Address) {
        runCatching {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(address, port), 20)
                serverSockets += server
                while (running) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    executor.execute { handle(socket) }
                }
            }
        }
        synchronized(serverSockets) {
            serverSockets.removeAll { it.isClosed }
        }
        if (serverAttempts.decrementAndGet() == 0 && running) {
            synchronized(serverSockets) {
                if (serverSockets.isEmpty()) {
                    running = false
                    bindAddresses = emptyList()
                }
            }
        }
    }

    fun stop() {
        running = false
        synchronized(serverSockets) {
            serverSockets.toList().forEach { runCatching { it.close() } }
            serverSockets.clear()
        }
        bindAddresses = emptyList()
        rateWindows.clear()
        executor.shutdownNow()
    }

    fun urls(): List<String> =
        bindAddresses.mapNotNull { address ->
            address.hostAddress?.let { ip -> "http://$ip:$port/#t=$token" }
        }

    fun url(): String? = urls().firstOrNull()

    internal fun pageForTest(): String = page()

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine().orEmpty()
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val key = line.substringBefore(':', "").trim().lowercase(Locale.US)
                if (key.isNotBlank()) headers[key] = line.substringAfter(':', "").trim()
            }

            val parts = request.split(' ')
            if (parts.size < 2) {
                respond(s, 400, "text/plain; charset=utf-8", "Requête invalide")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            if (path == "/") {
                if (method != "GET") {
                    respond(s, 405, "text/plain; charset=utf-8", "Méthode non autorisée")
                } else {
                    respond(s, 200, "text/html; charset=utf-8", page())
                }
                return
            }

            val suppliedToken = headers["x-photo-tv-token"] ?: query["t"].orEmpty()
            if (suppliedToken != token) {
                respond(s, 403, "text/plain; charset=utf-8", "Accès refusé")
                return
            }

            if (path == "/thumbnail") {
                if (method != "GET") {
                    respond(s, 405, "text/plain; charset=utf-8", "Méthode non autorisée")
                    return
                }
                val slot = query["slot"]?.toIntOrNull()?.coerceIn(0, 3) ?: 0
                val jpeg = thumbnailProvider(slot)
                if (jpeg == null || jpeg.isEmpty()) {
                    respond(s, 404, "text/plain; charset=utf-8", "Miniature indisponible")
                } else {
                    respondBytes(s, 200, "image/jpeg", jpeg)
                }
                return
            }

            if (path == "/status") {
                if (method != "GET") {
                    respond(s, 405, "text/plain; charset=utf-8", "Méthode non autorisée")
                    return
                }
                val st = stateProvider()
                val albumsJson = st.albums.joinToString(prefix = "[", postfix = "]") { "\"" + jsonEscape(it) + "\"" }
                val sourcesJson = st.sources.joinToString(prefix = "[", postfix = "]") { "\"" + jsonEscape(it) + "\"" }
                val transitionsJson = st.transitions.joinToString(prefix = "[", postfix = "]") { "\"" + jsonEscape(it) + "\"" }
                val imageModesJson = st.imageModes.joinToString(prefix = "[", postfix = "]") { "\"" + jsonEscape(it) + "\"" }
                val smartModesJson = st.smartModes.joinToString(prefix = "[", postfix = "]") { "\"" + jsonEscape(it) + "\"" }
                val filterJson = st.sourceFilter?.let { "\"" + jsonEscape(it) + "\"" } ?: "null"
                val previewsJson = st.previewTitles.joinToString(prefix = "[", postfix = "]") { "\"" + jsonEscape(it) + "\"" }
                val json = "{" +
                    "\"title\":\"" + jsonEscape(st.title) + "\"," +
                    "\"album\":\"" + jsonEscape(st.album) + "\"," +
                    "\"slideshow\":" + st.slideshow + "," +
                    "\"paused\":" + st.paused + "," +
                    "\"duration\":" + st.durationSeconds + "," +
                    "\"transition\":\"" + jsonEscape(st.transition) + "\"," +
                    "\"imageMode\":\"" + jsonEscape(st.imageMode) + "\"," +
                    "\"albums\":" + albumsJson + "," +
                    "\"sources\":" + sourcesJson + "," +
                    "\"sourceFilter\":" + filterJson + "," +
                    "\"transitions\":" + transitionsJson + "," +
                    "\"imageModes\":" + imageModesJson + "," +
                    "\"smartModes\":" + smartModesJson + "," +
                    "\"smartMode\":\"" + jsonEscape(st.smartMode) + "\"," +
                    "\"position\":" + st.position + "," +
                    "\"previewTitles\":" + previewsJson + "}"
                respond(s, 200, "application/json; charset=utf-8", json)
                return
            }

            if (path == "/action") {
                if (method != "POST") {
                    respond(s, 405, "application/json; charset=utf-8", "{\"ok\":false}")
                    return
                }
                val remoteAddress = s.inetAddress?.hostAddress.orEmpty()
                if (!allowAction(remoteAddress)) {
                    respond(s, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}")
                    return
                }
                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                if (contentLength > MAX_BODY_BYTES) {
                    respond(s, 413, "application/json; charset=utf-8", "{\"ok\":false}")
                    return
                }
                val bodyChars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(bodyChars, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                val params = parseQuery(String(bodyChars, 0, read))
                val cmd = params["cmd"].orEmpty()
                if (cmd in setOf(
                        "prev", "next", "pause", "stop", "favorite", "hide",
                        "duration_down", "duration_up", "transition_next", "mode_next",
                        "album_next", "history_prev", "sources", "album_select",
                        "source_select", "search", "network_prepare", "transition_select",
                        "mode_select", "smart_select", "start"
                    )
                ) {
                    val value = params["value"].orEmpty()
                    onCommand(if (value.isBlank()) cmd else "$cmd|$value")
                    respond(s, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                } else {
                    respond(s, 400, "application/json; charset=utf-8", "{\"ok\":false}")
                }
                return
            }

            respond(s, 404, "text/plain; charset=utf-8", "Introuvable")
        }
    }

    private fun allowAction(address: String): Boolean {
        val now = System.currentTimeMillis()
        val window = rateWindows.computeIfAbsent(address) { RateWindow(now, 0) }
        synchronized(window) {
            if (now - window.startedAt >= RATE_WINDOW_MS) {
                window.startedAt = now
                window.count = 0
            }
            if (window.count >= RATE_MAX_ACTIONS) return false
            window.count++
            return true
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
.preview-strip{grid-column:1/-1;display:grid;grid-template-columns:2fr 1fr 1fr 1fr;gap:8px;background:#0d2134;border-radius:14px;padding:10px}
.preview-card{min-width:0}
.preview-card img{width:100%;aspect-ratio:16/10;object-fit:cover;border-radius:10px;background:#07121e;display:block}
.preview-card:first-child img{aspect-ratio:16/9}
.preview-title{font-size:11px;color:#c8d5e3;margin-top:5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
</style>
</head>
<body>
<main>
<h1>Photo TV</h1>
<div class="grid">
<div class="preview-strip" id="previews">
  <div class="preview-card"><img id="thumb0" alt="Photo actuelle"><div class="preview-title" id="previewTitle0"></div></div>
  <div class="preview-card"><img id="thumb1" alt="Photo suivante"><div class="preview-title" id="previewTitle1"></div></div>
  <div class="preview-card"><img id="thumb2" alt="Photo suivante"><div class="preview-title" id="previewTitle2"></div></div>
  <div class="preview-card"><img id="thumb3" alt="Photo suivante"><div class="preview-title" id="previewTitle3"></div></div>
</div>
<button onclick="send('prev')">◀ Précédente</button>
<button onclick="send('next')">Suivante ▶</button>
<button class="wide" onclick="send('pause')">Pause / reprise</button>
<button onclick="send('favorite')">★ Favori</button>
<button onclick="send('hide')">Masquer</button>
<button onclick="send('duration_down')">− Durée</button>
<button onclick="send('duration_up')">+ Durée</button>
<button onclick="send('transition_next')">Transition</button>
<button onclick="send('mode_next')">Affichage</button>
<select id="transition" onchange="sendValue('transition_select',this.value)" style="grid-column:1/-1;padding:14px;border-radius:12px;font-size:17px"></select>
<select id="imageMode" onchange="sendValue('mode_select',this.value)" style="grid-column:1/-1;padding:14px;border-radius:12px;font-size:17px"></select>
<select id="smartMode" onchange="sendValue('smart_select',this.value)" style="grid-column:1/-1;padding:14px;border-radius:12px;font-size:17px"></select>
<button class="wide" onclick="send('start')">Démarrer le diaporama</button>
<button onclick="send('album_next')">Album suivant</button>
<button onclick="send('history_prev')">Historique</button>
<button onclick="send('sources')">Sources</button><button onclick="send('stop')">Arrêter</button>
<select id="album" onchange="sendValue('album_select',this.value)" style="grid-column:1/-1;padding:14px;border-radius:12px;font-size:17px"></select>
<select id="source" onchange="sendValue('source_select',this.value)" style="grid-column:1/-1;padding:14px;border-radius:12px;font-size:17px"></select>
<div style="grid-column:1/-1;display:flex;gap:8px"><input id="search" placeholder="Rechercher un album" style="flex:1;padding:14px;border-radius:12px;border:0;font-size:17px"><button onclick="sendValue('search',document.getElementById('search').value)" style="padding:14px">Rechercher</button></div>
<details style="grid-column:1/-1;background:#0d2134;border-radius:14px;padding:14px">
<summary>Ajouter une source réseau</summary>
<div style="display:grid;gap:8px;margin-top:12px">
<select id="netKind" style="padding:12px;border-radius:10px"><option value="WEBDAV">WebDAV</option><option value="SMB">SMB / NAS</option></select>
<input id="netUrl" placeholder="Adresse de la source" style="padding:12px;border-radius:10px;border:0">
<input id="netUser" placeholder="Utilisateur (facultatif)" style="padding:12px;border-radius:10px;border:0">
<button onclick="prepareNetwork()">Préparer sur la TV</button>
</div></details>
<div id="status" style="grid-column:1/-1;background:#0d2134;border-radius:14px;padding:16px"></div>
</div>
<small>Réseau local uniquement. Le lien secret est affiché dans Photo TV.</small>
<script>
const t=new URLSearchParams(location.hash.slice(1)).get('t')||'';
if(t){history.replaceState(null,'',location.pathname);}
const auth={'X-Photo-TV-Token':t};
function postAction(data){
  return fetch('/action',{
    method:'POST',
    headers:{...auth,'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
    body:new URLSearchParams(data).toString()
  });
}
function send(cmd){
  postAction({cmd}).then(refresh).catch(()=>{});
}
function sendValue(cmd,value){
  postAction({cmd,value}).then(refresh).catch(()=>{});
}
function fillSelect(id,values,current,allLabel){
  const el=document.getElementById(id);
  const wanted=[allLabel].concat(values||[]);
  const old=el.value;
  el.innerHTML='';
  wanted.forEach(v=>{const o=document.createElement('option');o.value=v;o.textContent=v;el.appendChild(o);});
  el.value=current||old||allLabel;
}
let lastPreviewSignature='';
function updatePreviews(s){
  const titles=s.previewTitles||[];
  const signature=String(s.position)+'|'+titles.join('|');
  for(let i=0;i<4;i++){
    const title=titles[i]||'';
    document.getElementById('previewTitle'+i).textContent=title;
    document.getElementById('thumb'+i).style.display=title?'block':'none';
  }
  if(signature===lastPreviewSignature)return;
  lastPreviewSignature=signature;
  for(let i=0;i<4;i++){
    const img=document.getElementById('thumb'+i);
    if(titles[i]) img.src='/thumbnail?slot='+i+'&v='+encodeURIComponent(signature);
    else img.removeAttribute('src');
  }
}
function prepareNetwork(){
  const kind=document.getElementById('netKind').value;
  const url=document.getElementById('netUrl').value.trim();
  const user=document.getElementById('netUser').value.trim();
  if(!url)return;
  sendValue('network_prepare',kind+'\t'+url+'\t'+user);
}
function refresh(){
  fetch('/status',{headers:auth})
    .then(r=>{if(!r.ok)throw new Error('status');return r.json();})
    .then(s=>{
      updatePreviews(s);
      document.getElementById('status').textContent=
        s.title+' • '+s.album+' • '+s.duration+' s • '+s.transition+' • '+s.imageMode+(s.paused?' • pause':'');
      fillSelect('album',s.albums,s.album,'Tous les albums');
      fillSelect('source',s.sources,s.sourceFilter,'Toutes les sources');
      fillSelect('transition',s.transitions,s.transition,'Transition');
      fillSelect('imageMode',s.imageModes,s.imageMode,'Affichage');
      fillSelect('smartMode',s.smartModes,s.smartMode,'Sélection intelligente');
    })
    .catch(()=>{document.getElementById('status').textContent='Connexion à Photo TV indisponible';});
}
refresh();
setInterval(refresh,2000);
</script>
</main>
</body>
</html>
""".trimIndent()

    private fun respondBytes(socket: Socket, status: Int, contentType: String, bytes: ByteArray) {
        val statusText = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $status $statusText\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Cache-Control: no-store, max-age=0\r\n")
        writer.write("X-Content-Type-Options: nosniff\r\n")
        writer.write("X-Frame-Options: DENY\r\n")
        writer.write("Referrer-Policy: no-referrer\r\n")
        writer.write("Content-Security-Policy: default-src 'none'\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun respond(socket: Socket, status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $status $statusText\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("X-Content-Type-Options: nosniff\r\n")
        writer.write("X-Frame-Options: DENY\r\n")
        writer.write("Referrer-Policy: no-referrer\r\n")
        writer.write("Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n")
        writer.write("Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'\r\n")
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

    private fun localIpv4Addresses(): List<Inet4Address> {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrNull() ?: return emptyList()

        return interfaces
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { networkInterface ->
                Collections.list(networkInterface.inetAddresses)
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { isPrivateIpv4(it.hostAddress.orEmpty()) }
                    .map { address -> networkInterface.name.orEmpty() to address }
            }
            .distinctBy { it.second.hostAddress }
            .sortedWith(
                compareBy<Pair<String, Inet4Address>> {
                    when {
                        it.first.startsWith("eth", ignoreCase = true) ||
                            it.first.startsWith("en", ignoreCase = true) -> 0
                        it.first.startsWith("wlan", ignoreCase = true) ||
                            it.first.startsWith("wifi", ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenBy { it.second.hostAddress.orEmpty() }
            )
            .map { it.second }
            .toList()
    }
}
