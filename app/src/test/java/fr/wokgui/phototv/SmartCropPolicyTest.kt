package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCropPolicyTest {
    @Test
    fun landscapeCropKeepsWeightedSubjectNearRightSide() {
        val crop = SmartCropPolicy.compute(
            imageWidth = 4000,
            imageHeight = 2000,
            targetWidth = 1000f,
            targetHeight = 1000f,
            anchors = listOf(SmartCropPolicy.Anchor(.82f, .5f, 3f))
        )
        assertEquals(2000f, crop.width, .5f)
        assertEquals(2000f, crop.height, .5f)
        assertTrue(crop.left > 1500f)
        assertTrue(crop.right <= 4000f)
    }

    @Test
    fun portraitCropKeepsSubjectNearTop() {
        val crop = SmartCropPolicy.compute(
            imageWidth = 2000,
            imageHeight = 4000,
            targetWidth = 1600f,
            targetHeight = 900f,
            anchors = listOf(SmartCropPolicy.Anchor(.5f, .18f, 2f))
        )
        assertEquals(2000f, crop.width, .5f)
        assertTrue(crop.top < 500f)
        assertTrue(crop.bottom < 2000f)
    }

    @Test
    fun emptyAnchorsFallsBackToCenteredCrop() {
        val crop = SmartCropPolicy.compute(4000, 2000, 1000f, 1000f)
        assertEquals(1000f, crop.left, .5f)
        assertEquals(3000f, crop.right, .5f)
    }

    @Test
    fun invalidAnchorsAreIgnored() {
        val crop = SmartCropPolicy.compute(
            3000, 2000, 1600f, 900f,
            listOf(SmartCropPolicy.Anchor(Float.NaN, .5f), SmartCropPolicy.Anchor(.2f, .2f, 0f))
        )
        assertTrue(crop.left >= 0f)
        assertTrue(crop.top >= 0f)
    }
}
