package fr.wokgui.phototv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object VisualDuplicateDetector {
    data class Fingerprint(
        val hash: Long,
        val meanLuma: Int,
        val aspectRatio: Float
    )

    fun merge(context: Context, items: List<PhotoItem>): List<PhotoItem> {
        if (items.size < 2) return items

        val output = mutableListOf<PhotoItem>()
        val fingerprints = mutableListOf<Fingerprint?>()

        fun combine(existing: PhotoItem, item: PhotoItem): PhotoItem =
            existing.copy(
                albums = LinkedHashSet<String>().apply {
                    addAll(existing.albums)
                    addAll(item.albums)
                },
                description = existing.description.ifBlank { item.description },
                location = existing.location.ifBlank { item.location },
                camera = existing.camera.ifBlank { item.camera },
                width = if (existing.width > 0) existing.width else item.width,
                height = if (existing.height > 0) existing.height else item.height,
                takenAt = when {
                    existing.takenAt > 0L -> existing.takenAt
                    else -> item.takenAt
                },
                sourceCopies = existing.sourceCopies + item.sourceCopies,
                sourceId = existing.sourceId.ifBlank { item.sourceId }
            )

        items.forEach { item ->
            if (item.mediaType != "image") {
                output += item
                fingerprints += null
                return@forEach
            }

            val fp = fingerprint(context, item.uri)
            if (fp == null) {
                output += item
                fingerprints += null
                return@forEach
            }

            var duplicateIndex = -1
            for (i in output.indices) {
                val other = fingerprints.getOrNull(i) ?: continue
                if (areNear(fp, other)) {
                    duplicateIndex = i
                    break
                }
            }

            if (duplicateIndex >= 0) {
                output[duplicateIndex] = combine(output[duplicateIndex], item)
            } else {
                output += item
                fingerprints += fp
            }
        }

        return output
    }

    fun areNear(a: Fingerprint, b: Fingerprint): Boolean {
        val aspectBase = max(.01f, max(a.aspectRatio, b.aspectRatio))
        val aspectDifference = abs(a.aspectRatio - b.aspectRatio) / aspectBase
        if (aspectDifference > .03f) return false
        if (abs(a.meanLuma - b.meanLuma) > 8) return false
        return java.lang.Long.bitCount(a.hash xor b.hash) <= 2
    }

    fun fingerprint(context: Context, uri: Uri): Fingerprint? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val small = try {
            Bitmap.createScaledBitmap(decoded, 9, 8, true)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }

        return try {
            var hash = 0L
            var sum = 0
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 9) {
                    val color = small.getPixel(x, y)
                    val r = android.graphics.Color.red(color)
                    val g = android.graphics.Color.green(color)
                    val b = android.graphics.Color.blue(color)
                    sum += (r * 299 + g * 587 + b * 114) / 1000
                }
                for (x in 0 until 8) {
                    val left = luma(small.getPixel(x, y))
                    val right = luma(small.getPixel(x + 1, y))
                    if (left > right) hash = hash or (1L shl bit)
                    bit++
                }
            }
            Fingerprint(
                hash = hash,
                meanLuma = sum / (9 * 8),
                aspectRatio = bounds.outWidth.toFloat() / bounds.outHeight.toFloat()
            )
        } finally {
            if (!small.isRecycled) small.recycle()
        }
    }

    private fun luma(color: Int): Int {
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
