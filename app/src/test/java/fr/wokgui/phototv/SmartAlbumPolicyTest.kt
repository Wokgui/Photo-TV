package fr.wokgui.phototv

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SmartAlbumPolicyTest {
    @Test fun classifiesOrientationFavoritesQualityAndVideo() {
        val c = SmartAlbumPolicy.Candidate(true, 4000, 3000, 0L, "image", 85)
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.FAVORITES, c))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.LANDSCAPE, c))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.HIGH_RES, c))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.BEST, c))
        assertFalse(SmartAlbumPolicy.matches(SmartAlbumPolicy.VIDEOS, c))
    }
}
