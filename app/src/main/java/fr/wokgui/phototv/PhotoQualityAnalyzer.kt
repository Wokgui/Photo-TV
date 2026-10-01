package fr.wokgui.phototv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

object PhotoQualityAnalyzer {
    fun analyze(
        bitmap: Bitmap,
        originalWidth: Int = bitmap.width,
        originalHeight: Int = bitmap.height
    ): PhotoQualityPolicy.Result {
        if (bitmap.isRecycled || bitmap.width <= 1 || bitmap.height <= 1) {
            return PhotoQualityPolicy.evaluate(PhotoQualityPolicy.Metrics(0f, 0f, 0f, 0f))
        }

        val cols = 16
        val rows = 12
        val values = FloatArray(cols * rows)
        var sum = 0f
        var index = 0
        for (row in 0 until rows) {
            val y = ((row + .5f) / rows * (bitmap.height - 1)).toInt().coerceIn(0, bitmap.height - 1)
            for (col in 0 until cols) {
                val x = ((col + .5f) / cols * (bitmap.width - 1)).toInt().coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(x, y)
                val luminance = (
                    Color.red(color) * .2126f +
                        Color.green(color) * .7152f +
                        Color.blue(color) * .0722f
                    ) / 255f
                values[index++] = luminance
                sum += luminance
            }
        }

        val mean = sum / values.size
        var variance = 0f
        values.forEach { value ->
            val d = value - mean
            variance += d * d
        }
        val contrast = (sqrt((variance / values.size).toDouble()).toFloat() * 3.2f).coerceIn(0f, 1f)

        var edgeSum = 0f
        var edgeCount = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val i = row * cols + col
                if (col + 1 < cols) {
                    edgeSum += abs(values[i] - values[i + 1])
                    edgeCount++
                }
                if (row + 1 < rows) {
                    edgeSum += abs(values[i] - values[i + cols])
                    edgeCount++
                }
            }
        }
        val edgeEnergy = if (edgeCount == 0) 0f else (edgeSum / edgeCount * 5f).coerceIn(0f, 1f)
        val scoreWidth = originalWidth.takeIf { it > 0 } ?: bitmap.width
        val scoreHeight = originalHeight.takeIf { it > 0 } ?: bitmap.height
        val megapixels = scoreWidth.toFloat() * scoreHeight.toFloat() / 1_000_000f

        return PhotoQualityPolicy.evaluate(
            PhotoQualityPolicy.Metrics(
                brightness = mean,
                contrast = contrast,
                edgeEnergy = edgeEnergy,
                megapixels = megapixels
            )
        )
    }
}
