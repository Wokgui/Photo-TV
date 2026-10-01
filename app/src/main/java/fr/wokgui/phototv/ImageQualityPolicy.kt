package fr.wokgui.phototv

import kotlin.math.abs
import kotlin.math.sqrt

object ImageQualityPolicy {
    data class Result(
        val score: Int,
        val exposure: Int,
        val contrast: Int,
        val sharpness: Int
    )

    fun evaluate(luminance: IntArray, width: Int, height: Int): Result {
        if (width <= 1 || height <= 1 || luminance.size < width * height) return Result(50, 50, 50, 50)
        var sum = 0.0
        var sumSq = 0.0
        for (v0 in luminance) {
            val v = v0.coerceIn(0, 255)
            sum += v
            sumSq += v.toDouble() * v.toDouble()
        }
        val n = (width * height).toDouble()
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        val std = sqrt(variance)
        val exposure = (100.0 - abs(mean - 127.5) / 1.275).toInt().coerceIn(0, 100)
        val contrast = (std / 64.0 * 100.0).toInt().coerceIn(0, 100)

        var edge = 0.0
        var count = 0
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val i = y * width + x
                edge += abs(luminance[i] - luminance[i + 1])
                edge += abs(luminance[i] - luminance[i + width])
                count += 2
            }
        }
        val sharpness = if (count == 0) 50 else ((edge / count) / 32.0 * 100.0).toInt().coerceIn(0, 100)
        val score = (exposure * 0.35 + contrast * 0.25 + sharpness * 0.40).toInt().coerceIn(0, 100)
        return Result(score, exposure, contrast, sharpness)
    }
}
