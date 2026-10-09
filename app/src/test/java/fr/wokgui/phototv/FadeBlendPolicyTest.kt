package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeBlendPolicyTest {
    @Test fun existingFadeIsPreservedAtFullStrength() {
        for (i in 0..100) assertEquals(i / 100f, FadeBlendPolicy.progress(i / 100f, 100), 0f)
    }
    @Test fun zeroFadeCutsAndHalfStrengthLimitsOverlap() {
        assertEquals(0f, FadeBlendPolicy.progress(.49f, 0), 0f)
        assertEquals(1f, FadeBlendPolicy.progress(.5f, 0), 0f)
        assertEquals(0f, FadeBlendPolicy.progress(.25f, 50), 0f)
        assertEquals(.5f, FadeBlendPolicy.progress(.5f, 50), 0f)
        assertEquals(1f, FadeBlendPolicy.progress(.75f, 50), 0f)
    }
    @Test fun neverFadesBackwardsAndAlwaysEndsOnNewPhoto() {
        for (strength in listOf(-1, 0, 5, 50, 100, 101)) {
            var previous = 0f
            for (i in 0..1000) {
                val current = FadeBlendPolicy.progress(i / 1000f, strength)
                assertTrue(current in 0f..1f && current >= previous)
                previous = current
            }
            assertEquals(1f, previous, 0f)
        }
        assertEquals(1f, FadeBlendPolicy.progress(Float.NaN, 50), 0f)
    }
}
