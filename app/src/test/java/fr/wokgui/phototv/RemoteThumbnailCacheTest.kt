package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RemoteThumbnailCacheTest {
    @Test
    fun stableKeyIsDeterministicAndContentSensitive() {
        val first = RemoteThumbnailCache.stableKey("content://photo/1|10|20")
        assertEquals(first, RemoteThumbnailCache.stableKey("content://photo/1|10|20"))
        assertNotEquals(first, RemoteThumbnailCache.stableKey("content://photo/1|10|21"))
        assertEquals(64, first.length)
    }
}
