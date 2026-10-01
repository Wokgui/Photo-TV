package fr.wokgui.phototv

import android.content.SharedPreferences
import java.util.UUID

object SettingsMigration {
    const val CURRENT_VERSION = 3
    const val VERSION_KEY = "_prefs_schema_version"

    fun migrate(prefs: SharedPreferences) {
        val storedVersion = prefs.getInt(VERSION_KEY, 0)
        if (storedVersion > CURRENT_VERSION) return

        var version = storedVersion
        if (version < 1) {
            prefs.edit()
                .putInt("duration", prefs.getInt("duration", 10).coerceIn(2, 120))
                .putInt("transition", prefs.getInt("transition", 0).coerceIn(0, 15))
                .putFloat("transition_seconds", prefs.getFloat("transition_seconds", 2f).coerceIn(.2f, 4f))
                .putInt("zoom", prefs.getInt("zoom", 0).coerceIn(0, 2))
                .putInt("image_mode", prefs.getInt("image_mode", 0).coerceIn(0, 3))
                .putInt("smart_selection_mode", prefs.getInt("smart_selection_mode", 0).coerceIn(0, 4))
                .putInt("date_format", prefs.getInt("date_format", 0).coerceIn(0, 2))
                .putInt("night_start", prefs.getInt("night_start", 22).coerceIn(0, 23))
                .putInt("night_end", prefs.getInt("night_end", 7).coerceIn(0, 23))
                .putInt("night_dim", prefs.getInt("night_dim", 45).coerceIn(0, 85))
                .putInt("auto_start", prefs.getInt("auto_start", 5).coerceIn(0, 60))
                .putInt("layout_preset", prefs.getInt("layout_preset", 0).coerceIn(0, 3))
                .putInt("album_sort", prefs.getInt("album_sort", 0).coerceIn(0, 1))
                .putInt(VERSION_KEY, 1)
                .commit()
            version = 1
        }

        if (version < 2) {
            val token = prefs.getString("remote_token", "").orEmpty().trim()
            val editor = prefs.edit()
            if (token.length < 16) {
                editor.putString(
                    "remote_token",
                    UUID.randomUUID().toString().replace("-", "").take(24)
                )
            }
            editor.putInt(VERSION_KEY, 2).commit()
            version = 2
        }

        if (version < 3) {
            prefs.edit()
                .putInt("image_mode", prefs.getInt("image_mode", 0).coerceIn(0, 6))
                .putInt(VERSION_KEY, 3)
                .commit()
        }
    }
}
