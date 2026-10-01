package fr.wokgui.phototv

import kotlin.math.abs
import kotlin.math.roundToInt

object PhotoQualityPolicy {
    data class Result(
        val score: Int,
        val sharpness: Int,
        val exposure: Int,
        val label: String
    )

    fun analyzeLuma(samples: IntArray, width: Int, height: Int): Result {
        if (samples.isEmpty() || width <= 1 || height <= 1 || samples.size < width * height) {
            return Result(50, 50, 50, "À analyser")
        }

        val clipped = samples.map { it.coerceIn(0, 255) }
        val mean = clipped.average()
        val darkRatio = clipped.count { it < 18 }.toDouble() / clipped.size
        val brightRatio = clipped.count { it > 237 }.toDouble() / clipped.size

        var edgeSum = 0L
        var edgeCount = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (x + 1 < width) {
                    edgeSum += abs(clipped[i] - clipped[i + 1])
                    edgeCount++
                }
                if (y + 1 < height) {
                    edgeSum += abs(clipped[i] - clipped[i + width])
                    edgeCount++
                }
            }
        }
        val avgEdge = if (edgeCount == 0) 0.0 else edgeSum.toDouble() / edgeCount
        val sharpness = (avgEdge * 3.4).roundToInt().coerceIn(0, 100)

        val meanPenalty = (abs(mean - 127.5) / 127.5 * 45.0)
        val clippingPenalty = (darkRatio + brightRatio) * 70.0
        val exposure = (100.0 - meanPenalty - clippingPenalty).roundToInt().coerceIn(0, 100)

        val score = (sharpness * 0.58 + exposure * 0.42).roundToInt().coerceIn(0, 100)
        val label = when {
            score >= 75 -> "Excellente"
            score >= 58 -> "Bonne"
            score >= 42 -> "Moyenne"
            else -> "Faible"
        }
        return Result(score, sharpness, exposure, label)
    }
}
