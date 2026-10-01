package fr.wokgui.phototv

import java.util.Calendar
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSelectionPolicyTest {
    private fun candidate(
        index: Int,
        favorite: Boolean = false,
        width: Int = 1920,
        height: Int = 1080,
        takenAt: Long = 0L,
        source: String = "A",
        album: String = "Album",
        mediaType: String = "image"
    ) = SmartSelectionPolicy.Candidate(
        index = index,
        favorite = favorite,
        width = width,
        height = height,
        takenAt = takenAt,
        sourceLabel = source,
        albumKey = album,
        mediaType = mediaType
    )

    @Test
    fun offModeKeepsEveryCandidateExactlyOnce() {
        val items = (0..7).map(::candidate)
        val order = SmartSelectionPolicy.buildOrder(
            candidates = items,
            currentIndex = 3,
            mode = SmartSelectionPolicy.OFF,
            random = Random(42)
        )
        assertEquals(7, order.size)
        assertEquals((0..7).filter { it != 3 }.toSet(), order.toSet())
    }

    @Test
    fun qualityModeStronglyFavorsFavoriteHighResolutionPhoto() {
        val items = listOf(
            candidate(0),
            candidate(1, width = 640, height = 480),
            candidate(2, favorite = true, width = 5000, height = 3500),
            candidate(3)
        )
        val order = SmartSelectionPolicy.buildOrder(
            candidates = items,
            currentIndex = 0,
            mode = SmartSelectionPolicy.QUALITY,
            random = Random(1)
        )
        assertEquals(2, order.first())
    }

    @Test
    fun antiRepeatAvoidsSameAlbumWhenAlternativeExists() {
        val items = listOf(
            candidate(0, source = "A", album = "X"),
            candidate(1, source = "A", album = "X"),
            candidate(2, source = "B", album = "Y"),
            candidate(3, source = "C", album = "Z")
        )
        val order = SmartSelectionPolicy.buildOrder(
            candidates = items,
            currentIndex = 0,
            mode = SmartSelectionPolicy.ANTI_REPEAT,
            random = Random(3)
        )
        assertTrue(order.first() == 2 || order.first() == 3)
    }

    @Test
    fun memoriesModeBoostsSameDayFromPreviousYears() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sameDay = Calendar.getInstance().apply {
            set(2019, Calendar.OCTOBER, 1, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val other = Calendar.getInstance().apply {
            set(2019, Calendar.MARCH, 5, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val items = listOf(
            candidate(0),
            candidate(1, takenAt = sameDay),
            candidate(2, takenAt = other)
        )
        val order = SmartSelectionPolicy.buildOrder(
            candidates = items,
            currentIndex = 0,
            mode = SmartSelectionPolicy.MEMORIES,
            now = now,
            random = Random(2)
        )
        assertEquals(1, order.first())
    }

    @Test
    fun completeModeNeverDropsMedia() {
        val items = (0 until 20).map { i ->
            candidate(
                index = i,
                favorite = i % 5 == 0,
                source = "S" + (i % 3),
                album = "A" + (i % 4)
            )
        }
        val order = SmartSelectionPolicy.buildOrder(
            candidates = items,
            currentIndex = 5,
            mode = SmartSelectionPolicy.COMPLETE,
            random = Random(99)
        )
        assertEquals(19, order.size)
        assertEquals(items.map { it.index }.filter { it != 5 }.toSet(), order.toSet())
    }
}
