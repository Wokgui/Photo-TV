package fr.wokgui.phototv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object ImageQualityAnalyzer {
    data class Result(
        val score: Int,
        val sharpness: Float,
        val exposure: Float,
        val contrast: Float,
        val noiseQuality: Float
    )

    fun analyze(bitmap: Bitmap, portrait: Boolean = false, favorite: Boolean = false): Result {
        if (bitmap.width < 2 || bitmap.height < 2) {
            return Result(0, 0f, 0f, 0f, 0f)
        }

        val step = max(1, minOf(bitmap.width, bitmap.height) / 180)
        var count = 0L
        var sum = 0.0
        var sumSq = 0.0
        var clipped = 0L
        var gradientSum = 0.0
        var gradientCount = 0L
        var residualSum = 0.0
        var residualCount = 0L

        fun luma(x: Int, y: Int): Double {
            val c = bitmap.getPixel(x, y)
            return (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
        }

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val v = luma(x, y)
                count++
                sum += v
                sumSq += v * v
                if (v <= .02 || v >= .98) clipped++

                if (x + step < bitmap.width && y + step < bitmap.height) {
                    val right = luma(x + step, y)
                    val down = luma(x, y + step)
                    val grad = (abs(v - right) + abs(v - down)) * .5
                    gradientSum += grad
                    gradientCount++

                    if (x >= step && y >= step) {
                        val left = luma(x - step, y)
                        val up = luma(x, y - step)
                        val localMean = (left + right + up + down) * .25
                        residualSum += abs(v - localMean)
                        residualCount++
                    }
                }
                x += step
            }
            y += step
        }

        if (count <= 0L) return Result(0, 0f, 0f, 0f, 0f)

        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        val std = sqrt(variance)
        val clipRatio = clipped.toDouble() / count
        val avgGradient = if (gradientCount == 0L) 0.0 else gradientSum / gradientCount
        val avgResidual = if (residualCount == 0L) 0.0 else residualSum / residualCount

        val sharpness = (avgGradient / .16).coerceIn(0.0, 1.0).toFloat()
        val exposureCenter = (1.0 - abs(mean - .5) / .5).coerceIn(0.0, 1.0)
        val exposure = (exposureCenter * (1.0 - clipRatio * 2.5)).coerceIn(0.0, 1.0).toFloat()
        val contrast = (std / .24).coerceIn(0.0, 1.0).toFloat()

        // Fine residual is useful for detail up to a point; beyond that it increasingly
        // behaves like sensor/compression noise. Keep the estimate deliberately conservative.
        val excessResidual = ((avgResidual - .055) / .12).coerceIn(0.0, 1.0)
        val noiseQuality = (1.0 - excessResidual).toFloat()

        val metrics = QualityScorePolicy.Metrics(
            sharpness = sharpness,
            exposure = exposure,
            contrast = contrast,
            noiseQuality = noiseQuality,
            portrait = portrait,
            favorite = favorite,
            megapixels = bitmap.width.toFloat() * bitmap.height.toFloat() / 1_000_000f
        )
        return Result(
            score = QualityScorePolicy.score(metrics),
            sharpness = sharpness,
            exposure = exposure,
            contrast = contrast,
            noiseQuality = noiseQuality
        )
    }
}
