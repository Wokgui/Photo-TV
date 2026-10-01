package fr.wokgui.phototv

import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoriesPolicyTest {
    private fun date(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun sameDayRequiresPreviousYear() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertTrue(MemoriesPolicy.isSameDayPreviousYear(date(2020, Calendar.OCTOBER, 1), now))
        assertFalse(MemoriesPolicy.isSameDayPreviousYear(date(2026, Calendar.OCTOBER, 1), now))
        assertFalse(MemoriesPolicy.isSameDayPreviousYear(date(2020, Calendar.OCTOBER, 2), now))
    }

    @Test
    fun sameMonthWorksAcrossYears() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 12, 0, 0)
        }
        assertTrue(MemoriesPolicy.isSameMonth(date(2018, Calendar.OCTOBER, 20), now))
        assertFalse(MemoriesPolicy.isSameMonth(date(2018, Calendar.SEPTEMBER, 30), now))
    }

    @Test
    fun favoritesDominateBestScoreThenResolution() {
        val favorite = MemoriesPolicy.bestScore(true, 1000, 1000)
        val huge = MemoriesPolicy.bestScore(false, 8000, 6000)
        assertTrue(favorite > huge)
        assertEquals(
            MemoriesPolicy.bestScore(false, 4000, 3000),
            12_000_000L
        )
    }
}
