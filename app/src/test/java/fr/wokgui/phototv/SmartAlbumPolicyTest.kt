package fr.wokgui.phototv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAlbumPolicyTest {
    @Test
    fun smartAlbumsMatchExpectedMetadata() {
        val now = 2_000_000_000_000L
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.FAVORITES, true, 100, 100, "image", 0L, 50, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.LANDSCAPE, false, 2000, 1000, "image", 0L, 50, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.PORTRAIT, false, 1000, 2000, "image", 0L, 50, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.HIGH_QUALITY, false, 2000, 1000, "image", 0L, 80, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.VIDEOS, false, 0, 0, "video", 0L, null, now))
        assertFalse(SmartAlbumPolicy.matches(SmartAlbumPolicy.HIGH_QUALITY, false, 2000, 1000, "image", 0L, 40, now))
    }
}
