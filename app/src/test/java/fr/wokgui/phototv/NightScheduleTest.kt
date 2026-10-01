package fr.wokgui.phototv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightScheduleTest {
    @Test
    fun overnightWindowCrossesMidnight() {
        assertTrue(NightSchedule.isActive(true, 22, 7, 22))
        assertTrue(NightSchedule.isActive(true, 22, 7, 2))
        assertFalse(NightSchedule.isActive(true, 22, 7, 12))
        assertFalse(NightSchedule.isActive(true, 22, 7, 7))
    }

    @Test
    fun daytimeWindowUsesExclusiveEnd() {
        assertFalse(NightSchedule.isActive(true, 8, 18, 7))
        assertTrue(NightSchedule.isActive(true, 8, 18, 8))
        assertTrue(NightSchedule.isActive(true, 8, 18, 17))
        assertFalse(NightSchedule.isActive(true, 8, 18, 18))
    }

    @Test
    fun equalStartAndEndMeansAllDayWhenEnabled() {
        for (hour in 0..23) {
            assertTrue(NightSchedule.isActive(true, 6, 6, hour))
        }
    }

    @Test
    fun disabledScheduleNeverActivates() {
        for (hour in 0..23) {
            assertFalse(NightSchedule.isActive(false, 22, 7, hour))
        }
    }
}
