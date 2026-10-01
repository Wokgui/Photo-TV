package fr.wokgui.phototv

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousSlideshowPolicyTest {
    @Test fun morningUsesMemories() {
        assertEquals(SmartSelectionPolicy.MEMORIES, AutonomousSlideshowPolicy.presentation(8, 1, null).smartSelectionMode)
    }
    @Test fun daytimeUsesQuality() {
        assertEquals(SmartSelectionPolicy.QUALITY, AutonomousSlideshowPolicy.presentation(14, 1, null).smartSelectionMode)
    }
    @Test fun eveningUsesCompleteAndFavorites() {
        val p=AutonomousSlideshowPolicy.presentation(21,1,null)
        assertEquals(SmartSelectionPolicy.COMPLETE,p.smartSelectionMode)
        assertTrue(p.preferFavorites)
    }
    @Test fun cadenceInjectsMosaics() {
        assertEquals(5, AutonomousSlideshowPolicy.presentation(14,6,null).imageModeOverride)
        assertEquals(6, AutonomousSlideshowPolicy.presentation(14,10,null).imageModeOverride)
    }
    @Test fun durationAdaptsToContentQuality() {
        assertEquals(13, AutonomousSlideshowPolicy.presentation(14, 1, SceneClassifier.NATURE, 90).durationSecondsOverride)
        assertEquals(12, AutonomousSlideshowPolicy.presentation(14, 1, SceneClassifier.PORTRAIT, 60).durationSecondsOverride)
        assertEquals(7, AutonomousSlideshowPolicy.presentation(14, 1, SceneClassifier.GENERAL, 25).durationSecondsOverride)
    }
}
