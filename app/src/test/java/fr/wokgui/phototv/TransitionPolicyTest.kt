package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionPolicyTest {
    @Test
    fun normalImageKeepsRequestedTransition() {
        assertEquals(
            5,
            TransitionPolicy.choose(5, 0, 15, "image", 0, 4000, 3000, 58f, 2, .55)
        )
    }

    @Test
    fun videosAndMosaicsSkipCanvasTransitions() {
        assertEquals(15, TransitionPolicy.choose(5, 0, 15, "video", 0, 1920, 1080, 60f, 0, .6))
        assertEquals(15, TransitionPolicy.choose(5, 0, 15, "image", 4, 1920, 1080, 60f, 0, .6))
    }

    @Test
    fun gifAndSlowRuntimeFallBackToFade() {
        assertEquals(0, TransitionPolicy.choose(5, 0, 15, "gif", 0, 1200, 800, 60f, 0, .6))
        assertEquals(0, TransitionPolicy.choose(5, 0, 15, "image", 0, 4000, 3000, 18f, 4, .5))
        assertEquals(0, TransitionPolicy.choose(5, 0, 15, "image", 0, 4000, 3000, 55f, 30, .5))
    }

    @Test
    fun severeMemoryPressureDisablesTransition() {
        assertEquals(15, TransitionPolicy.choose(5, 0, 15, "image", 0, 8000, 6000, 55f, 5, .10))
    }

    @Test
    fun explicitNoneIsNeverOverridden() {
        assertEquals(15, TransitionPolicy.choose(15, 0, 15, "image", 0, 4000, 3000, 60f, 0, .9))
    }
}
