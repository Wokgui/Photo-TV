package fr.wokgui.phototv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsMigrationInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("photo_tv_ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun legacyPreferencesAreSanitizedAndVersioned() {
        val prefs = context.getSharedPreferences("photo_tv_ui", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("duration", 999)
            .putInt("transition", 99)
            .putFloat("transition_seconds", 99f)
            .putInt("zoom", 8)
            .putInt("image_mode", 8)
            .putInt("smart_selection_mode", 8)
            .putInt("night_start", -4)
            .putInt("night_end", 44)
            .putInt("night_dim", 100)
            .putInt("auto_start", 300)
            .putString("remote_token", "court")
            .commit()

        SettingsMigration.migrate(prefs)

        assertEquals(SettingsMigration.CURRENT_VERSION, prefs.getInt(SettingsMigration.VERSION_KEY, -1))
        assertEquals(120, prefs.getInt("duration", -1))
        assertEquals(15, prefs.getInt("transition", -1))
        assertEquals(4f, prefs.getFloat("transition_seconds", -1f))
        assertEquals(2, prefs.getInt("zoom", -1))
        assertEquals(6, prefs.getInt("image_mode", -1))
        assertEquals(4, prefs.getInt("smart_selection_mode", -1))
        assertEquals(0, prefs.getInt("night_start", -1))
        assertEquals(23, prefs.getInt("night_end", -1))
        assertEquals(85, prefs.getInt("night_dim", -1))
        assertEquals(60, prefs.getInt("auto_start", -1))
        assertTrue(prefs.getString("remote_token", "").orEmpty().length >= 16)
    }

    @Test
    fun futurePreferenceSchemaIsLeftUntouched() {
        val prefs = context.getSharedPreferences("photo_tv_ui", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(SettingsMigration.VERSION_KEY, SettingsMigration.CURRENT_VERSION + 5)
            .putInt("duration", 999)
            .commit()

        SettingsMigration.migrate(prefs)

        assertEquals(999, prefs.getInt("duration", -1))
        assertEquals(
            SettingsMigration.CURRENT_VERSION + 5,
            prefs.getInt(SettingsMigration.VERSION_KEY, -1)
        )
    }
}
