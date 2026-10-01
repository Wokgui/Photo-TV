package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThumbnailDiskCacheTest {
    @Test
    fun stableKeyIsDeterministicAndContentSensitive() {
        val first = ThumbnailDiskCache.stableKey("content://photo/1|10|20")
        assertEquals(first, ThumbnailDiskCache.stableKey("content://photo/1|10|20"))
        assertNotEquals(first, ThumbnailDiskCache.stableKey("content://photo/1|10|21"))
        assertEquals(64, first.length)
    }
}
