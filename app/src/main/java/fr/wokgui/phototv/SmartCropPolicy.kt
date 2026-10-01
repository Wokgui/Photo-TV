package fr.wokgui.phototv

import kotlin.math.max
import kotlin.math.min

object SmartCropPolicy {
    data class Anchor(
        val x: Float,
        val y: Float,
        val weight: Float = 1f
    )

    data class CropWindow(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun compute(
        imageWidth: Int,
        imageHeight: Int,
        targetWidth: Float,
        targetHeight: Float,
        anchors: List<Anchor> = emptyList()
    ): CropWindow {
        if (imageWidth <= 0 || imageHeight <= 0 || targetWidth <= 0f || targetHeight <= 0f) {
            return CropWindow(0f, 0f, imageWidth.coerceAtLeast(0).toFloat(), imageHeight.coerceAtLeast(0).toFloat())
        }

        val iw = imageWidth.toFloat()
        val ih = imageHeight.toFloat()
        val targetRatio = targetWidth / targetHeight
        val imageRatio = iw / ih

        val focus = weightedFocus(anchors)
        val focusX = (focus?.x ?: 0.5f).coerceIn(0f, 1f) * iw
        val focusY = (focus?.y ?: 0.5f).coerceIn(0f, 1f) * ih

        return if (imageRatio > targetRatio) {
            val cropWidth = ih * targetRatio
            val left = (focusX - cropWidth / 2f).coerceIn(0f, max(0f, iw - cropWidth))
            CropWindow(left, 0f, min(iw, left + cropWidth), ih)
        } else {
            val cropHeight = iw / targetRatio
            val top = (focusY - cropHeight / 2f).coerceIn(0f, max(0f, ih - cropHeight))
            CropWindow(0f, top, iw, min(ih, top + cropHeight))
        }
    }

    private fun weightedFocus(anchors: List<Anchor>): Anchor? {
        val valid = anchors.filter {
            it.x.isFinite() && it.y.isFinite() && it.weight.isFinite() && it.weight > 0f
        }
        if (valid.isEmpty()) return null
        val total = valid.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        val x = valid.sumOf { (it.x * it.weight).toDouble() }.toFloat() / total
        val y = valid.sumOf { (it.y * it.weight).toDouble() }.toFloat() / total
        return Anchor(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), total)
    }
}
