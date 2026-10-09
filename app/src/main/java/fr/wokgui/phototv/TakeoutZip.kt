package fr.wokgui.phototv

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extracts a complete multipart export into a new directory; never changes a saved library. */
object TakeoutZip {
    fun extract(parts: List<() -> InputStream>, destination: File, maxBytes: Long): Int {
        require(parts.isNotEmpty()) { "Aucune archive sélectionnée." }
        require(!destination.exists()) { "Le dossier de destination existe déjà." }
        require(maxBytes > 0) { "Stockage insuffisant." }
        check(destination.mkdirs()) { "Impossible de créer le dossier Takeout." }
        val root = destination.canonicalPath + File.separator
        var bytes = 0L
        var files = 0
        val seen = mutableSetOf<String>()
        try {
            parts.forEach { open ->
                ZipInputStream(open().buffered()).use { zip ->
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        check(++entries <= 250_000) { "Archive trop volumineuse." }
                        val name = entry.name.replace('\\', '/')
                        require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) { "Chemin ZIP invalide." }
                        val target = File(destination, name).canonicalFile
                        require(target.path.startsWith(root)) { "Chemin ZIP invalide." }
                        if (entry.isDirectory) {
                            check(target.isDirectory || target.mkdirs()) { "Impossible de créer un dossier." }
                        } else {
                            val duplicate = !seen.add(target.path)
                            val written = if (duplicate) File(target.path + ".duplicate-" + java.util.UUID.randomUUID()) else target
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Impossible de créer un dossier." }
                            written.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    check(bytes <= maxBytes) { "Stockage insuffisant pour décompresser Takeout." }
                                    out.write(buffer, 0, count)
                                }
                            }
                            if (duplicate) {
                                require(target.length() == written.length() && fingerprint(target).contentEquals(fingerprint(written))) { "Versions contradictoires dans les archives : $name" }
                                written.delete()
                            } else {
                                if (entry.time > 0) target.setLastModified(entry.time)
                                files++
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(files > 0) { "Archive ZIP vide ou invalide." }
            return files
        } catch (failure: Exception) {
            destination.deleteRecursively()
            throw failure
        }
    }
    private fun fingerprint(file: File): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest()
    }
}
