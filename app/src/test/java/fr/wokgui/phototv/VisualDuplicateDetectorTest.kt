package fr.wokgui.phototv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualDuplicateDetectorTest {
    @Test
    fun identicalAndTinyHashChangesAreNear() {
        val a = VisualDuplicateDetector.Fingerprint(0x123456789ABCDEFL, 120, 1.5f)
        val b = VisualDuplicateDetector.Fingerprint(0x123456789ABCDEEL, 124, 1.49f)
        assertTrue(VisualDuplicateDetector.areNear(a, b))
    }

    @Test
    fun visuallyDifferentHashesAreNotNear() {
        val a = VisualDuplicateDetector.Fingerprint(0x0000000000000000L, 120, 1.5f)
        val b = VisualDuplicateDetector.Fingerprint(-1L, 120, 1.5f)
        assertFalse(VisualDuplicateDetector.areNear(a, b))
    }

    @Test
    fun differentAspectOrBrightnessIsNotMerged() {
        val a = VisualDuplicateDetector.Fingerprint(0x55L, 80, 1.5f)
        assertFalse(VisualDuplicateDetector.areNear(a, VisualDuplicateDetector.Fingerprint(0x55L, 80, 1.2f)))
        assertFalse(VisualDuplicateDetector.areNear(a, VisualDuplicateDetector.Fingerprint(0x55L, 100, 1.5f)))
    }
}
