package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadPolicyTest {
    @Test
    fun lowMemoryAlwaysCutsAheadAggressively() {
        val d = PreloadPolicy.decide(
            freeRatio = .10,
            secondsPerItem = 3,
            avgDecodeMs = 3000,
            avgNetworkMs = 3000
        )
        assertEquals(1, d.ahead)
        assertEquals(1, d.hdAhead)
    }

    @Test
    fun slowNetworkIncreasesPrefetchWhenMemoryAllows() {
        val fast = PreloadPolicy.decide(.60, 8, 100, 100)
        val slow = PreloadPolicy.decide(.60, 8, 100, 2500)
        assertTrue(slow.ahead > fast.ahead)
        assertTrue(slow.hdAhead >= fast.hdAhead)
    }

    @Test
    fun fastCadenceKeepsMoreItemsReady() {
        val slowCadence = PreloadPolicy.decide(.45, 20, 100, 100)
        val fastCadence = PreloadPolicy.decide(.45, 3, 100, 100)
        assertTrue(fastCadence.ahead > slowCadence.ahead)
    }

    @Test
    fun outputAlwaysStaysBounded() {
        val d = PreloadPolicy.decide(1.0, 1, 10_000, 10_000)
        assertTrue(d.ahead in 1..10)
        assertTrue(d.hdAhead in 1..5)
        assertTrue(d.hdAhead <= d.ahead)
    }
}
