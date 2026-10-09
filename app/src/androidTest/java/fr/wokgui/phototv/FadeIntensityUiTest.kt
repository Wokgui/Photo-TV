package fr.wokgui.phototv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FadeIntensityUiTest {
    @Test fun remoteControlPersistenceAndBackupKeepFadeIntensity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stores = listOf("photo_tv_ui", "photo_tv_network_settings")
        val saved = stores.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        var scenario: ActivityScenario<MainActivity>? = null
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun view(activity: MainActivity): PhotoTvView {
            val field = MainActivity::class.java.getDeclaredField("ui").apply { isAccessible = true }
            return field.get(activity) as PhotoTvView
        }
        fun waitState(expected: String) {
            val end = SystemClock.uptimeMillis() + 8000L
            var state = ""
            while (SystemClock.uptimeMillis() < end) {
                scenario!!.onActivity { state = it.automationStateForTest() }
                if (state.contains(expected)) return
                SystemClock.sleep(80)
            }
            fail("Missing $expected in $state")
        }
        try {
            device.wakeUp()
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("phototv_test_mode", true)
                putExtra("phototv_test_page", 3)
                putExtra("phototv_test_library_count", 3)
            }
            scenario = ActivityScenario.launch(intent)
            waitState("page=3")
            val prefs = context.getSharedPreferences("photo_tv_ui", Context.MODE_PRIVATE)
            scenario!!.onActivity {
                assertTrue(view(it).importSettingsJson("{\"version\":1,\"fadeIntensityPercent\":100}"))
            }
            device.pressDPadRight()
            waitState("settingsColumn=1")
            repeat(7) { device.pressDPadDown(); SystemClock.sleep(80) }
            waitState("settingsControl=11")
            device.pressDPadCenter()
            device.pressDPadLeft()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(95, prefs.getInt("fade_intensity_percent", -1))
            scenario!!.recreate()
            waitState("page=3")
            scenario!!.onActivity {
                val ui = view(it)
                assertEquals(95, JSONObject(ui.exportSettingsJson()).getInt("fadeIntensityPercent"))
                assertTrue(ui.importSettingsJson("{\"version\":1,\"fadeIntensityPercent\":50}"))
                assertEquals(50, JSONObject(ui.exportSettingsJson()).getInt("fadeIntensityPercent"))
                assertTrue(ui.importSettingsJson("{\"version\":1}"))
                assertEquals(50, JSONObject(ui.exportSettingsJson()).getInt("fadeIntensityPercent"))
            }
            assertEquals(50, prefs.getInt("fade_intensity_percent", -1))
            device.pressDPadRight()
            repeat(7) { device.pressDPadDown(); SystemClock.sleep(80) }
            waitState("settingsControl=11")
            val screenshot = File(context.getExternalFilesDir(null), "Photos-TV-0.33-intensite-fondu.png")
            assertTrue(device.takeScreenshot(screenshot))
        } finally {
            scenario?.close()
            saved.forEach { (name, values) ->
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                values.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                assertTrue(editor.commit())
            }
        }
    }
}
