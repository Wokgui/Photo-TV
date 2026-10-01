package fr.wokgui.phototv

import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQualityPolicyTest {
    @Test fun balancedDetailedImageScoresAboveFlatDarkImage() {
        val detailed = IntArray(64) { i -> ((i * 37) % 220) + 18 }
        val dark = IntArray(64) { 8 }
        val a = ImageQualityPolicy.evaluate(detailed, 8, 8)
        val b = ImageQualityPolicy.evaluate(dark, 8, 8)
        assertTrue(a.score > b.score)
        assertTrue(a.exposure > b.exposure)
    }
}
