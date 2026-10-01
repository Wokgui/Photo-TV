package fr.wokgui.phototv

import kotlin.math.max
import kotlin.math.min

object PreloadPolicy {
    data class Decision(
        val ahead: Int,
        val hdAhead: Int
    )

    fun decide(
        freeRatio: Double,
        secondsPerItem: Int,
        avgDecodeMs: Long,
        avgNetworkMs: Long
    ): Decision {
        val free = freeRatio.coerceIn(0.0, 1.0)
        val seconds = secondsPerItem.coerceAtLeast(1)
        val observedLoadMs = max(avgDecodeMs, avgNetworkMs)

        val latencyAhead = when {
            observedLoadMs >= 2500L -> 7
            observedLoadMs >= 1200L -> 6
            observedLoadMs >= 600L -> 5
            observedLoadMs >= 250L -> 4
            else -> 3
        }
        val cadenceAhead = when {
            seconds <= 3 -> 6
            seconds <= 5 -> 5
            seconds <= 10 -> 4
            else -> 3
        }

        val ahead = when {
            free < .18 -> 1
            free < .25 -> min(2, max(latencyAhead, cadenceAhead))
            free > .55 -> min(10, max(latencyAhead, cadenceAhead) + 2)
            free > .40 -> min(8, max(latencyAhead, cadenceAhead) + 1)
            else -> min(6, max(latencyAhead, cadenceAhead))
        }

        val hdAhead = when {
            free < .22 -> 1
            observedLoadMs >= 1200L && free > .45 -> min(5, ahead)
            free > .45 -> min(4, ahead)
            else -> min(2, ahead)
        }

        return Decision(ahead = ahead, hdAhead = hdAhead)
    }
}
