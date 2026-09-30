package fr.wokgui.phototv

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowOrderTest {
    @Test
    fun eachBagContainsEveryOtherPhotoExactlyOnce() {
        val random = Random(123456)
        for (size in 2..250) {
            for (current in 0 until size) {
                val bag = SlideshowOrder.newBag(size, current, random)
                assertEquals(size - 1, bag.size)
                assertEquals(size - 1, bag.toSet().size)
                assertFalse(bag.contains(current))
                assertEquals((0 until size).filter { it != current }.toSet(), bag.toSet())
            }
        }
    }

    @Test
    fun stressTenThousandShuffleCyclesNeverDuplicateWithinABag() {
        val random = Random(987654321)
        var current = 0
        repeat(10_000) {
            val bag = SlideshowOrder.newBag(300, current, random)
            assertEquals(299, bag.size)
            assertEquals(299, bag.toSet().size)
            assertFalse(bag.contains(current))
            current = bag.last()
            assertTrue(current in 0 until 300)
        }
    }

    @Test
    fun tinyLibrariesAreSafe() {
        assertTrue(SlideshowOrder.newBag(0, 0, Random(1)).isEmpty())
        assertTrue(SlideshowOrder.newBag(1, 0, Random(1)).isEmpty())
    }
}
