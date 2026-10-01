package fr.wokgui.phototv

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSelectionStressTest {
    @Test
    fun completeModeHandlesOneHundredThousandMediaWithoutDroppingItems() {
        val count = 100_000
        val candidates = (0 until count).map { i ->
            SmartSelectionPolicy.Candidate(
                index = i,
                favorite = i % 31 == 0,
                width = if (i % 7 == 0) 6000 else 1920,
                height = if (i % 7 == 0) 4000 else 1080,
                takenAt = 0L,
                sourceLabel = "S" + (i % 8),
                albumKey = "A" + (i % 40),
                mediaType = "image"
            )
        }

        val order = SmartSelectionPolicy.buildOrder(
            candidates = candidates,
            currentIndex = 0,
            mode = SmartSelectionPolicy.COMPLETE,
            random = Random(1234)
        )

        assertEquals(count - 1, order.size)
        assertEquals(count - 1, order.toSet().size)
        assertTrue(0 !in order)
    }
}
