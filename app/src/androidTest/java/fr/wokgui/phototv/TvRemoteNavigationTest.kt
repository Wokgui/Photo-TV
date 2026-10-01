package fr.wokgui.phototv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvRemoteNavigationTest {
    private lateinit var device: UiDevice
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private fun launch(page: Int): ActivityScenario<MainActivity> {
        scenario?.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("phototv_test_mode", true)
            putExtra("phototv_test_page", page)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return ActivityScenario.launch<MainActivity>(intent).also {
            scenario = it
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitState(it, "page=$page")
        }
    }

    private fun currentState(s: ActivityScenario<MainActivity>): String {
        var value = ""
        s.onActivity { value = it.automationStateForTest() }
        return value
    }

    private fun awaitState(
        s: ActivityScenario<MainActivity>,
        vararg expected: String,
        timeoutMs: Long = 2500L
    ) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var state = currentState(s)
        while (SystemClock.uptimeMillis() < end) {
            if (expected.all { state.contains(it) }) return
            SystemClock.sleep(50)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            state = currentState(s)
        }
        fail("Expected ${expected.joinToString()} in state: $state")
    }

    private fun press(
        s: ActivityScenario<MainActivity>,
        action: () -> Boolean,
        vararg expected: String
    ) {
        action()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        awaitState(s, *expected)
    }

    private fun longCenter(s: ActivityScenario<MainActivity>) {
        s.onActivity { activity ->
            val downTime = SystemClock.uptimeMillis()
            val repeated = KeyEvent(
                downTime,
                downTime + 650L,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                2,
                0,
                0,
                0,
                KeyEvent.FLAG_LONG_PRESS
            )
            activity.dispatchKeyEvent(repeated)
            activity.dispatchKeyEvent(
                KeyEvent(
                    downTime,
                    downTime + 700L,
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    0
                )
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun bottomNavigationAndPhotoRowsAreRemoteReachable() {
        val s = launch(0)

        press(s, { device.pressDPadDown() }, "page=0", "navFocus=true")
        press(s, { device.pressDPadRight() }, "page=1", "navFocus=true")
        press(s, { device.pressDPadRight() }, "page=2", "navFocus=true")
        press(s, { device.pressDPadRight() }, "page=3", "navFocus=true")
        press(s, { device.pressDPadLeft() }, "page=2", "navFocus=true")
        press(s, { device.pressEnter() }, "page=2", "navFocus=false", "editorColumn=0")

        val photos = launch(1)
        press(photos, { device.pressDPadRight() }, "sourceFocus=1")
        press(photos, { device.pressDPadRight() }, "sourceFocus=2")
        press(photos, { device.pressDPadLeft() }, "sourceFocus=1")
        press(photos, { device.pressDPadLeft() }, "sourceFocus=0")

        press(photos, { device.pressDPadDown() }, "photosRow=1")
        repeat(5) { device.pressDPadRight() }
        awaitState(photos, "albumFocus=5")
        repeat(5) { device.pressDPadLeft() }
        awaitState(photos, "albumFocus=0")

        press(photos, { device.pressDPadDown() }, "photosRow=2")
        repeat(5) { device.pressDPadRight() }
        awaitState(photos, "photoFocus=5")
        repeat(5) { device.pressDPadLeft() }
        awaitState(photos, "photoFocus=0")

        press(photos, { device.pressDPadDown() }, "photosRow=3", "navFocus=true")
        press(photos, { device.pressDPadLeft() }, "page=0", "navFocus=true")
    }


    @Test
    fun accessibilityDescriptionTracksDpadFocus() {
        val s = launch(1)
        s.onActivity { activity ->
            val description = activity.accessibilityDescriptionForTest()
            assertTrue(description.contains("Sources"))
            assertTrue(description.contains("Google Photos"))
        }

        press(s, { device.pressDPadRight() }, "sourceFocus=1")
        s.onActivity { activity ->
            val description = activity.accessibilityDescriptionForTest()
            assertTrue(description.contains("Dossier local"))
        }

        press(s, { device.pressDPadDown() }, "photosRow=1")
        s.onActivity { activity ->
            val description = activity.accessibilityDescriptionForTest()
            assertTrue(description.contains("Albums"))
        }
    }

    @Test
    fun editorSupportsRemoteTraversalAndLongPressMoveMode() {
        val s = launch(2)

        repeat(9) { device.pressDPadDown() }
        awaitState(s, "editorElement=9")
        repeat(9) { device.pressDPadUp() }
        awaitState(s, "editorElement=0")

        press(s, { device.pressDPadRight() }, "editorColumn=1")
        longCenter(s)
        awaitState(s, "editorMoveMode=true")

        device.pressDPadRight()
        awaitState(s, "editorColumn=1", "editorMoveMode=true")
        press(s, { device.pressEnter() }, "editorMoveMode=false")
        press(s, { device.pressDPadRight() }, "editorColumn=2")

        repeat(11) { device.pressDPadDown() }
        awaitState(s, "editorControl=11")
        repeat(11) { device.pressDPadUp() }
        awaitState(s, "editorControl=0")
        press(s, { device.pressDPadLeft() }, "editorColumn=1")
        press(s, { device.pressDPadLeft() }, "editorColumn=0")
    }

    @Test
    fun settingsCategoriesControlsAndNestedAdvancedPagesAreReachable() {
        val s = launch(3)

        repeat(8) { device.pressDPadDown() }
        awaitState(s, "settingsCategory=8")
        repeat(8) { device.pressDPadUp() }
        awaitState(s, "settingsCategory=0")

        press(s, { device.pressDPadRight() }, "settingsColumn=1")
        repeat(7) { device.pressDPadDown() }
        awaitState(s, "settingsControl=7")
        repeat(7) { device.pressDPadUp() }
        awaitState(s, "settingsControl=0")
        press(s, { device.pressDPadLeft() }, "settingsColumn=0")

        repeat(7) { device.pressDPadDown() }
        awaitState(s, "settingsCategory=7")
        press(s, { device.pressDPadRight() }, "settingsColumn=1")
        repeat(6) { device.pressDPadDown() }
        awaitState(s, "settingsControl=6")
        repeat(6) { device.pressDPadUp() }
        awaitState(s, "settingsControl=0")
        press(s, { device.pressDPadLeft() }, "settingsColumn=0")

        press(s, { device.pressDPadDown() }, "settingsCategory=8")
        press(s, { device.pressDPadRight() }, "settingsColumn=1")

        repeat(12) { device.pressDPadDown() }
        awaitState(s, "settingsControl=12")
        press(s, { device.pressEnter() }, "diagnostics=true")

        press(s, { device.pressDPadUp() }, "settingsControl=11")
        press(s, { device.pressEnter() }, "rulesOpen=true", "settingsControl=0")

        repeat(8) { device.pressDPadDown() }
        awaitState(s, "settingsControl=8")
        press(s, { device.pressEnter() }, "rulesOpen=false", "settingsControl=0")

        press(s, { device.pressBack() }, "page=0")
    }
}
