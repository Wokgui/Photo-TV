package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DriveSync {
    const val DEFAULT_SERVICE_URL = "https://photo-tv-drive.photo-tv-drive-service.workers.dev"
    private val busy = AtomicBoolean(false)
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private fun prefs(context: Context) = context.getSharedPreferences("photo_tv_drive", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("photo_tv_drive", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("photo_tv_drive", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun save(context: Context, value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(value.toString().toByteArray())
        prefs(context).edit().putString("connection", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    private fun connection(context: Context): JSONObject? = runCatching {
        val data = Base64.decode(prefs(context).getString("connection", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size))))
    }.getOrNull()
    fun configured(context: Context) = connection(context) != null
    fun statusText(context: Context): String = prefs(context).getString("status", "Non connecté à Drive").orEmpty()
    fun generation(context: Context) = prefs(context).getLong("generation", 0)
    private fun status(context: Context, value: String) { prefs(context).edit().putString("status", value).apply() }
    private fun request(config: JSONObject, path: String) = Request.Builder()
        .url(config.getString("url") + path).header("Authorization", "Bearer " + config.getString("token"))
    private fun json(config: JSONObject, path: String): JSONObject = http.newCall(request(config, path).build()).execute().use {
        if (it.code == 401) error("Connexion expirée : reconnectez Google dans Photos TV.")
        check(it.isSuccessful) { "Service Drive indisponible (${it.code})." }
        JSONObject(it.body?.string() ?: error("Réponse vide."))
    }
    fun pair(context: Context, base: String): String {
        val uri = Uri.parse(base.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && (uri.path.isNullOrBlank() || uri.path == "/")) {
            "Indiquez l’adresse HTTPS du service Photos TV."
        }
        val url = base.trim().trimEnd('/')
        val result = http.newCall(Request.Builder().url("$url/pairings").post(ByteArray(0).toRequestBody()).build()).execute().use {
            check(it.isSuccessful) { "Le service ne répond pas (${it.code})." }
            JSONObject(it.body?.string() ?: error("Réponse vide."))
        }
        val connect = result.getString("connectUrl")
        require(Uri.parse(connect).host == uri.host && Uri.parse(connect).scheme == "https")
        save(context, JSONObject().put("url", url).put("token", result.getString("token")))
        status(context, "Autorisez Google puis choisissez le dossier Takeout sur le téléphone.")
        return connect
    }
    fun connected(context: Context): Boolean {
        val config = connection(context) ?: return false
        val result = json(config, "/status")
        if (result.optBoolean("connected")) {
            status(context, "Connecté • " + result.optString("folderName"))
            return true
        }
        return false
    }
    fun disconnect(context: Context) {
        connection(context)?.let { config ->
            http.newCall(request(config, "/connection").delete().build()).execute().use {
                check(it.isSuccessful || it.code == 401) { "Déconnexion impossible : service indisponible." }
            }
        }
        prefs(context).edit().remove("connection").putString("status", "Déconnecté • photos importées conservées").apply()
        DriveSyncJob.cancel(context)
    }
    internal fun readRange(context: Context, id: String, offset: Long, count: Int): ByteArray {
        require(Regex("[A-Za-z0-9_-]{1,200}").matches(id) && offset >= 0 && count in 1..(64*1024*1024))
        val config = connection(context) ?: error("Reconnectez Google dans Photos TV.")
        val result = java.io.ByteArrayOutputStream(count)
        var done = 0
        while (done < count) {
            val length = minOf(8*1024*1024, count-done)
            val start = Math.addExact(offset, done.toLong())
            val end = Math.addExact(start, length.toLong()-1)
            http.newCall(request(config, "/archives/$id").header("Range", "bytes=$start-$end").build()).execute().use { response ->
                if (response.code == 401) error("Connexion expirée : reconnectez Google dans Photos TV.")
                check(response.code == 206 && response.header("Content-Range")?.startsWith("bytes $start-$end/") == true) { "Lecture Drive interrompue (${response.code})." }
                val input = response.body ?: error("Réponse Drive vide.")
                val bytes = input.bytes()
                check(bytes.size == length) { "Lecture Drive incomplète." }
                result.write(bytes)
            }
            done += length
        }
        return result.toByteArray()
    }
    fun sync(context: Context, cancelled: () -> Boolean = { false }): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            val config = connection(context) ?: return false
            if (!connected(context)) return false
            val files = json(config, "/archives").getJSONArray("files")
            val rows = (0 until files.length()).map { files.getJSONObject(it) }
            val changed = DriveRemoteLibrary.synchronize(context, rows, cancelled, progress = { value -> status(context,value) })
            if(changed) {
                SourceStore.addDrive(context)
                prefs(context).edit().putLong("generation", System.currentTimeMillis()).apply()
            }
            status(context, if(rows.isEmpty()) "Connecté • attente de la première exportation Takeout"
                else "Drive à jour • lecture en ligne • cache limité à 256 Mo")
            return changed
        } catch (failure: Exception) {
            status(context, failure.message ?: "Synchronisation interrompue ; nouvel essai prévu.")
            throw failure
        } finally { busy.set(false) }
    }
}
