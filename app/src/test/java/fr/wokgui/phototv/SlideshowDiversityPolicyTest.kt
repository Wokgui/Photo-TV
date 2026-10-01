package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowDiversityPolicyTest {
    @Test
    fun burstNeighborIsDeferredWhenAnotherPhotoExists() {
        val candidates = listOf(
            SlideshowDiversityPolicy.Candidate(0, "A", "Album", 4000, 3000, 100_000L),
            SlideshowDiversityPolicy.Candidate(1, "A", "Album", 4000, 3000, 104_000L),
            SlideshowDiversityPolicy.Candidate(2, "B", "Other", 3000, 4000, 10_000_000L)
        )
        val order = SlideshowDiversityPolicy.reorder(listOf(1, 2), candidates, 0)
        assertEquals(2, order.first())
        assertEquals(setOf(1, 2), order.toSet())
    }

    @Test(timeout = 5000)
    fun fiftyThousandCandidatesRemainLinearAndUnique() {
        val count = 50_000
        val candidates = (0 until count).map { index ->
            SlideshowDiversityPolicy.Candidate(
                index = index,
                source = "S" + (index % 4),
                album = "A" + (index % 20),
                width = if (index % 2 == 0) 4000 else 3000,
                height = if (index % 2 == 0) 3000 else 4000,
                takenAt = index * 12_000L
            )
        }
        val input = (1 until count).toList()
        val output = SlideshowDiversityPolicy.reorder(input, candidates, 0)
        assertEquals(input.size, output.size)
        assertEquals(input.size, output.toSet().size)
        assertFalse(output.contains(0))
    }
}
