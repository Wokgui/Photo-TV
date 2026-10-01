package fr.wokgui.phototv

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import org.json.JSONArray

class BackgroundMusicController(private val context: Context) {
    private val prefs = context.getSharedPreferences("photo_tv_music", Context.MODE_PRIVATE)
    private var player: MediaPlayer? = null
    private var index = 0
    private var videoWithSound = false

    var enabled: Boolean = prefs.getBoolean("enabled", false)
        private set

    var volume: Float = prefs.getFloat("volume", 0.35f).coerceIn(0f, 1f)
        private set

    val playlist: List<Uri>
        get() = parsePlaylist(prefs.getString("playlist", "[]").orEmpty())

    fun setPlaylist(uris: List<Uri>) {
        val distinct = uris.distinct().take(200)
        val arr = JSONArray()
        distinct.forEach { arr.put(it.toString()) }
        prefs.edit().putString("playlist", arr.toString()).apply()
        index = 0
        restartIfNeeded()
    }

    fun toggle(): Boolean {
        enabled = !enabled
        prefs.edit().putBoolean("enabled", enabled).apply()
        restartIfNeeded()
        return enabled
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        prefs.edit().putFloat("volume", volume).apply()
        player?.setVolume(volume, volume)
    }

    fun setVideoWithSound(active: Boolean) {
        if (videoWithSound == active) return
        videoWithSound = active
        restartIfNeeded()
    }

    fun release() {
        releasePlayer()
    }

    private fun restartIfNeeded() {
        if (!enabled || videoWithSound || playlist.isEmpty()) {
            releasePlayer()
            return
        }
        play(index.coerceIn(0, playlist.lastIndex))
    }

    private fun play(position: Int) {
        val list = playlist
        if (list.isEmpty() || !enabled || videoWithSound) return
        releasePlayer()
        index = position.coerceIn(0, list.lastIndex)
        val uri = list[index]
        val media = MediaPlayer()
        player = media
        runCatching {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            media.setDataSource(context, uri)
            media.setVolume(volume, volume)
            media.setOnPreparedListener {
                if (enabled && !videoWithSound) it.start()
            }
            media.setOnCompletionListener {
                if (playlist.isNotEmpty()) {
                    index = (index + 1) % playlist.size
                    play(index)
                }
            }
            media.setOnErrorListener { _, _, _ ->
                if (playlist.size > 1) {
                    index = (index + 1) % playlist.size
                    play(index)
                } else {
                    releasePlayer()
                }
                true
            }
            media.prepareAsync()
        }.onFailure {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        val old = player
        player = null
        runCatching { old?.stop() }
        runCatching { old?.reset() }
        runCatching { old?.release() }
    }

    private fun parsePlaylist(raw: String): List<Uri> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }?.let(Uri::parse)
        }
    }.getOrDefault(emptyList())
}
