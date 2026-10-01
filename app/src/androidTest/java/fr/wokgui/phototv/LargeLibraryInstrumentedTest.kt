package fr.wokgui.phototv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeLibraryInstrumentedTest {
    @Test
    fun tenThousandMediaRemainNavigableAndIndexed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java)
                .putExtra("phototv_test_mode", true)
                .putExtra("phototv_test_page", 0)
                .putExtra("phototv_test_library_count", 10_000)
        )

        scenario.onActivity { activity ->
            assertEquals(10_000, activity.automationLibrarySizeForTest())
            assertEquals(10_000, activity.automationActiveCountForTest())
            assertTrue(activity.automationStateForTest().contains("currentPhoto=0"))
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        assertTrue("Large library setup took ${elapsed}ms", elapsed < 12_000L)
        scenario.close()
    }
}
