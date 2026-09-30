package fr.wokgui.phototv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateRecoveryInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun resetState() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("photo_tv_ui", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsPinStore.clear(context)
    }

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .putExtra("phototv_test_mode", true)
                .putExtra("phototv_test_page", 0)
        )

    @Test
    fun slideshowResumesAfterActivityRecreation() {
        val first = launch()
        first.onActivity { activity ->
            activity.installAutomationLibraryForTest(8)
            activity.startAutomationSlideshowForTest()
            activity.advanceAutomationForTest()
            val state = activity.automationStateForTest()
            assertTrue(state.contains("slideshow=true"))
            assertTrue(state.contains("currentPhoto=1"))
        }
        first.close()

        val second = launch()
        second.onActivity { activity ->
            activity.installAutomationLibraryForTest(8)
        }
        SystemClock.sleep(750)
        second.onActivity { activity ->
            val state = activity.automationStateForTest()
            assertTrue(state.contains("slideshow=true"))
            assertTrue(state.contains("currentPhoto=1"))
        }
        second.close()
    }

    @Test
    fun oldSettingsRemainImportableAndFutureSchemaIsRejected() {
        val scenario = launch()
        scenario.onActivity { activity ->
            assertTrue(
                activity.importSettingsForTest(
                    """{"version":1,"durationSeconds":17,"randomOrder":true,"showDate":false}"""
                )
            )
            assertFalse(
                activity.importSettingsForTest(
                    """{"version":999,"durationSeconds":10}"""
                )
            )
        }
        scenario.close()
    }
}
