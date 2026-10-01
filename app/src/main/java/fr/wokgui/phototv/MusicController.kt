package fr.wokgui.phototv

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import org.json.JSONArray

class MusicController(private val context: Context) {
    companion object {
        private const val PREFS = "photo_tv_music"
        private const val KEY_TRACKS = "tracks"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_VOLUME = "volume"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tracks = mutableListOf<Uri>()
    private var player: MediaPlayer? = null
    private var index = 0
    private var pausedForVideo = false
    private var hostPaused = false

    init {
        loadTracks()
    }

    fun trackCount(): Int = tracks.size

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun volume(): Int = prefs.getInt(KEY_VOLUME, 35).coerceIn(0, 100)

    fun setTracks(uris: List<Uri>) {
        stopPlayer()
        tracks.clear()
        tracks += uris.distinct()
        index = 0
        val arr = JSONArray()
        tracks.forEach { arr.put(it.toString()) }
        prefs.edit().putString(KEY_TRACKS, arr.toString()).apply()
        sync()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        sync()
    }

    fun setVolume(percent: Int) {
        val safe = percent.coerceIn(0, 100)
        prefs.edit().putInt(KEY_VOLUME, safe).apply()
        val volume = safe / 100f
        runCatching { player?.setVolume(volume, volume) }
    }

    fun pauseForVideo(pause: Boolean) {
        pausedForVideo = pause
        if (pause) {
            runCatching { player?.pause() }
        } else {
            sync()
        }
    }

    fun onHostPause() {
        hostPaused = true
        runCatching { player?.pause() }
    }

    fun onHostResume() {
        hostPaused = false
        sync()
    }

    fun sync() {
        if (!isEnabled() || pausedForVideo || hostPaused || tracks.isEmpty()) {
            if (!isEnabled() || tracks.isEmpty()) stopPlayer()
            else runCatching { player?.pause() }
            return
        }
        val current = player
        if (current != null) {
            runCatching {
                if (!current.isPlaying) current.start()
            }
            return
        }
        playCurrent()
    }

    fun release() {
        stopPlayer()
    }

    private fun playCurrent() {
        if (!isEnabled() || pausedForVideo || hostPaused || tracks.isEmpty()) return
        index = index.coerceIn(0, tracks.lastIndex)
        val uri = tracks[index]
        val volume = volume() / 100f
        val next = MediaPlayer()
        runCatching {
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            next.setDataSource(context, uri)
            next.setVolume(volume, volume)
            next.setOnCompletionListener {
                stopPlayer()
                if (tracks.isNotEmpty()) {
                    index = (index + 1) % tracks.size
                    playCurrent()
                }
            }
            next.setOnErrorListener { _, _, _ ->
                stopPlayer()
                if (tracks.isNotEmpty()) {
                    index = (index + 1) % tracks.size
                    playCurrent()
                }
                true
            }
            next.prepare()
            next.start()
            player = next
        }.onFailure {
            runCatching { next.release() }
            player = null
            if (tracks.size > 1) {
                index = (index + 1) % tracks.size
            }
        }
    }

    private fun stopPlayer() {
        val current = player
        player = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    private fun loadTracks() {
        tracks.clear()
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_TRACKS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { tracks += Uri.parse(it) }
            }
        }
    }
}
