package fr.wokgui.phototv

import android.content.Context
import java.io.File
import java.security.MessageDigest

object ThumbnailDiskCache {
    private const val MAX_BYTES = 256L * 1024L * 1024L
    private const val MAX_FILES = 3000
    private const val TRIM_EVERY_WRITES = 32
    private val writesSinceTrim = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var legacyCleaned = false

    fun read(context: Context, key: String): ByteArray? {
        val file = fileFor(context, key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun write(context: Context, key: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val dir = directory(context)
        runCatching {
            val target = fileFor(context, key)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
            if (writesSinceTrim.incrementAndGet() >= TRIM_EVERY_WRITES) {
                writesSinceTrim.set(0)
                trim(dir)
            }
        }
    }

    fun stats(context: Context): Pair<Int, Long> {
        val files = directory(context).listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    internal fun stableKey(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun directory(context: Context): File {
        if (!legacyCleaned) {
            synchronized(this) {
                if (!legacyCleaned) {
                    runCatching { File(context.cacheDir, "remote_thumbnails").deleteRecursively() }
                    legacyCleaned = true
                }
            }
        }
        return File(context.cacheDir, "photo_tv_thumbnails").apply { mkdirs() }
    }

    private fun fileFor(context: Context, key: String): File =
        File(directory(context), stableKey(key) + ".jpg")

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "jpg" }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (total <= MAX_BYTES && count <= MAX_FILES) break
            total -= file.length()
            count--
            file.delete()
        }
    }
}
