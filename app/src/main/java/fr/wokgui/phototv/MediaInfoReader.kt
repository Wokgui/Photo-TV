package fr.wokgui.phototv

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Locale

data class MediaInfo(
    val width: Int = 0,
    val height: Int = 0,
    val takenAt: Long = 0L,
    val camera: String = "",
    val location: String = ""
)

object MediaInfoReader {
    fun read(context: Context, uri: Uri, mime: String, name: String? = null): MediaInfo {
        val kind = MediaTypeDetector.classify(name, mime)
        val signature = MediaMetadataStore.signature(context, uri, mime)
        MediaMetadataStore.readInfo(context, uri, signature)?.let { cached ->
            return MediaInfo(
                width = cached.width,
                height = cached.height,
                takenAt = cached.takenAt,
                camera = cached.camera,
                location = cached.location
            )
        }

        val info = if (kind == "video" || mime.startsWith("video/")) {
            readVideo(context, uri)
        } else {
            readImage(context, uri)
        }
        MediaMetadataStore.writeInfo(context, uri, signature, info)
        return info
    }

    private fun readVideo(context: Context, uri: Uri): MediaInfo {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val dateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE).orEmpty()
                val takenAt = parseVideoDate(dateRaw)
                MediaInfo(width = width, height = height, takenAt = takenAt)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(MediaInfo())
    }

    private fun readImage(context: Context, uri: Uri): MediaInfo {
        val bounds = runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
        }.getOrDefault(0 to 0)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return MediaInfo(width = bounds.first, height = bounds.second)
        }

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE

                val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
                val baseCamera = listOf(make, model)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" ")

                val lens = exif.getAttribute("LensModel").orEmpty().trim()
                val focal = exifNumber(exif.getAttribute("FocalLength"))
                    ?.takeIf { it > 0.0 }
                    ?.let { value -> formatNumber(value) + " mm" }
                    .orEmpty()
                val aperture = exifNumber(exif.getAttribute("FNumber"))
                    ?.takeIf { it > 0.0 }
                    ?.let { value -> "f/" + formatNumber(value) }
                    .orEmpty()
                val iso = exif.getAttribute("PhotographicSensitivity")
                    ?.ifBlank { exif.getAttribute("ISOSpeedRatings").orEmpty() }
                    .orEmpty()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { "ISO " + it }
                    .orEmpty()
                val exposure = exifNumber(exif.getAttribute("ExposureTime"))
                    ?.takeIf { it > 0.0 }
                    ?.let(::formatExposure)
                    .orEmpty()

                val camera = listOf(baseCamera, lens, focal, aperture, iso, exposure)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" • ")

                val latLong = FloatArray(2)
                val hasLocation = runCatching { exif.getLatLong(latLong) }.getOrDefault(false)
                val location = if (hasLocation) {
                    String.format(Locale.US, "%.5f, %.5f", latLong[0], latLong[1])
                } else ""

                val dateRaw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: ""
                val takenAt = parseExifDate(dateRaw)

                MediaInfo(
                    width = if (rotated) bounds.second else bounds.first,
                    height = if (rotated) bounds.first else bounds.second,
                    takenAt = takenAt,
                    camera = camera,
                    location = location
                )
            } ?: MediaInfo(width = bounds.first, height = bounds.second)
        }.getOrDefault(MediaInfo(width = bounds.first, height = bounds.second))
    }

    private fun exifNumber(raw: String?): Double? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        value.toDoubleOrNull()?.let { return it }
        val slash = value.indexOf('/')
        if (slash > 0 && slash < value.lastIndex) {
            val a = value.substring(0, slash).toDoubleOrNull()
            val b = value.substring(slash + 1).toDoubleOrNull()
            if (a != null && b != null && b != 0.0) return a / b
        }
        return null
    }

    private fun formatNumber(value: Double): String =
        if (kotlin.math.abs(value - kotlin.math.round(value)) < 0.05) {
            kotlin.math.round(value).toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }

    private fun formatExposure(seconds: Double): String =
        when {
            seconds >= 1.0 -> formatNumber(seconds) + " s"
            seconds > 0.0 -> {
                val denominator = kotlin.math.round(1.0 / seconds).toInt().coerceAtLeast(1)
                "1/" + denominator + " s"
            }
            else -> ""
        }

    private fun parseExifDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                .parse(raw.trim())
                ?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun parseVideoDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val normalized = raw.trim()
        val formats = listOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(normalized)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }
}
