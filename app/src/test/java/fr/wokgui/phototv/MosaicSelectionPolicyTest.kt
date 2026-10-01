package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicSelectionPolicyTest {
    @Test
    fun mosaicPrefersDifferentAlbumsSourcesAndDates() {
        val candidates = listOf(
            MosaicSelectionPolicy.Candidate(0, "A", "Album A", 4000, 3000, 1_000_000L, "image"),
            MosaicSelectionPolicy.Candidate(1, "A", "Album A", 4000, 3000, 1_004_000L, "image"),
            MosaicSelectionPolicy.Candidate(2, "B", "Album B", 3000, 4000, 1_000_000L + 40L * 24L * 3600L * 1000L, "image"),
            MosaicSelectionPolicy.Candidate(3, "C", "Album C", 4000, 3000, 1_000_000L + 5L * 24L * 3600L * 1000L, "image")
        )
        val selected = MosaicSelectionPolicy.select(candidates, 0, 3)
        assertEquals(3, selected.size)
        assertEquals(0, selected.first())
        assertTrue(selected.contains(2))
        assertFalse(selected.take(2).contains(1))
    }

    @Test
    fun videosAreNotAddedToMosaicPool() {
        val candidates = listOf(
            MosaicSelectionPolicy.Candidate(0, "A", "A", 100, 100, 0, "image"),
            MosaicSelectionPolicy.Candidate(1, "B", "B", 100, 100, 0, "video"),
            MosaicSelectionPolicy.Candidate(2, "C", "C", 100, 100, 0, "image")
        )
        val selected = MosaicSelectionPolicy.select(candidates, 0, 3)
        assertFalse(selected.contains(1))
    }
}
