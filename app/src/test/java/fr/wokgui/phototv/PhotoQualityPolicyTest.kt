package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoQualityPolicyTest {
    @Test
    fun texturedBalancedImageScoresHigherThanFlatDarkImage() {
        val w = 12
        val h = 12
        val textured = IntArray(w * h) { i -> if ((i + i / w) % 2 == 0) 70 else 190 }
        val flatDark = IntArray(w * h) { 8 }

        val good = PhotoQualityPolicy.analyzeLuma(textured, w, h)
        val poor = PhotoQualityPolicy.analyzeLuma(flatDark, w, h)

        assertTrue(good.score > poor.score)
        assertTrue(good.sharpness > poor.sharpness)
        assertTrue(good.exposure > poor.exposure)
    }

    @Test
    fun invalidInputReturnsNeutralResult() {
        val result = PhotoQualityPolicy.analyzeLuma(intArrayOf(), 0, 0)
        assertEquals(50, result.score)
        assertEquals("À analyser", result.label)
    }
}
