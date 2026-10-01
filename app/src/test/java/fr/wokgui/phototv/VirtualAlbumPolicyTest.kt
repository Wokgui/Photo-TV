package fr.wokgui.phototv

import java.util.Calendar
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualAlbumPolicyTest {
    @Test fun buildsUsefulVirtualAlbums() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 1, 12, 0, 0) }
        val taken = Calendar.getInstance().apply { set(2024, Calendar.OCTOBER, 1, 10, 0, 0) }.timeInMillis
        val labels = VirtualAlbumPolicy.labels(
            favorite = true,
            takenAt = taken,
            location = "Paris, France",
            scene = "Portrait",
            qualityScore = 88,
            now = now
        )
        assertTrue("★ Favoris" in labels)
        assertTrue("Année · 2024" in labels)
        assertTrue("Souvenirs · Aujourd’hui" in labels)
        assertTrue("Scène · Portrait" in labels)
        assertTrue("Qualité · Excellente" in labels)
        assertTrue("Lieu · Paris" in labels)
    }
}
