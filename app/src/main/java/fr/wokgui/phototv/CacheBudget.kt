package fr.wokgui.phototv

class CacheBudget(private val limitBytes: Long) {
    private val entries = LinkedHashMap<String, Long>(32, .75f, true)
    var totalBytes: Long = 0
        private set

    fun put(key: String, sizeBytes: Long) {
        require(sizeBytes >= 0)
        entries.remove(key)?.let { totalBytes -= it }
        entries[key] = sizeBytes
        totalBytes += sizeBytes
        trim()
    }

    fun touch(key: String): Boolean = entries[key] != null

    fun contains(key: String): Boolean = entries.containsKey(key)

    fun size(): Int = entries.size

    private fun trim() {
        while (totalBytes > limitBytes && entries.size > 1) {
            val first = entries.entries.firstOrNull() ?: break
            totalBytes -= first.value
            entries.remove(first.key)
        }
    }
}
