package fr.wokgui.phototv

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheBudgetStressTest {
    @Test
    fun hundredThousandPhotoCacheUpdatesStayWithinBudget() {
        val limit = 96L * 1024L * 1024L
        val cache = CacheBudget(limit)
        val random = Random(20260930)

        repeat(100_000) { step ->
            val key = "photo-" + random.nextInt(0, 2_000)
            val size = random.nextLong(300_000L, 8_000_000L)
            cache.put(key, size)
            if (step % 11 == 0) cache.touch("photo-" + random.nextInt(0, 2_000))
            assertTrue(cache.totalBytes <= limit || cache.size() == 1)
        }
    }
}
