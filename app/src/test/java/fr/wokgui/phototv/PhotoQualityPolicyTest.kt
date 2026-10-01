package fr.wokgui.phototv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoQualityPolicyTest {
    @Test
    fun balancedDetailedPhotoScoresAboveBadPhoto() {
        val good = PhotoQualityPolicy.evaluate(
            PhotoQualityPolicy.Metrics(.52f, .55f, .62f, 12f)
        )
        val bad = PhotoQualityPolicy.evaluate(
            PhotoQualityPolicy.Metrics(.05f, .08f, .04f, 1f)
        )
        assertTrue(good.score > bad.score)
        assertFalse(good.blurred)
        assertTrue(bad.blurred)
        assertTrue(bad.underexposed)
    }

    @Test
    fun smartAlbumsUseOrientationRecencyFavoritesAndQuality() {
        val now = 1_800_000_000_000L
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.LANDSCAPES, 4000, 2000, 0, false, null, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.PORTRAITS, 1800, 3000, 0, false, null, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.BEST, 1000, 1000, 0, true, 20, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.BEST, 1000, 1000, 0, false, 82, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.LOW_QUALITY, 1000, 1000, 0, false, 30, now))
        assertTrue(SmartAlbumPolicy.matches(SmartAlbumPolicy.RECENT, 1000, 1000, now - 7L * 24L * 60L * 60L * 1000L, false, 60, now))
    }
}
