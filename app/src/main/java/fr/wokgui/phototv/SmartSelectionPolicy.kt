package fr.wokgui.phototv

import java.util.Calendar
import java.util.Random
import kotlin.math.max

object SmartSelectionPolicy {
    data class Candidate(
        val index: Int,
        val favorite: Boolean,
        val width: Int,
        val height: Int,
        val takenAt: Long,
        val sourceLabel: String,
        val albumKey: String,
        val mediaType: String
    )

    const val OFF = 0
    const val QUALITY = 1
    const val ANTI_REPEAT = 2
    const val MEMORIES = 3
    const val COMPLETE = 4

    fun buildOrder(
        candidates: List<Candidate>,
        currentIndex: Int,
        mode: Int,
        now: Calendar = Calendar.getInstance(),
        random: Random = Random()
    ): List<Int> {
        val pool = candidates.filter { it.index != currentIndex }.toMutableList()
        if (pool.isEmpty()) return emptyList()
        if (mode == OFF) return pool.map { it.index }.shuffled(random)

        val useQuality = mode == QUALITY || mode == COMPLETE
        val useAntiRepeat = mode == ANTI_REPEAT || mode == COMPLETE
        val useMemories = mode == MEMORIES || mode == COMPLETE
        val result = mutableListOf<Int>()
        var previousSource = candidates.firstOrNull { it.index == currentIndex }?.sourceLabel.orEmpty()
        var previousAlbum = candidates.firstOrNull { it.index == currentIndex }?.albumKey.orEmpty()

        while (pool.isNotEmpty()) {
            val scored = pool.map { candidate ->
                val base = random.nextDouble() * 100.0
                var score = base

                if (useQuality) {
                    val pixels = candidate.width.toLong().coerceAtLeast(0L) *
                        candidate.height.toLong().coerceAtLeast(0L)
                    score += when {
                        pixels >= 12_000_000L -> 35.0
                        pixels >= 6_000_000L -> 25.0
                        pixels >= 2_000_000L -> 12.0
                        pixels in 1..699_999L -> -20.0
                        else -> 0.0
                    }
                    if (candidate.favorite) score += 55.0
                    if (candidate.mediaType == "image") score += 8.0
                }

                if (useMemories && candidate.takenAt > 0L) {
                    val date = Calendar.getInstance().apply { timeInMillis = candidate.takenAt }
                    val sameMonth = date.get(Calendar.MONTH) == now.get(Calendar.MONTH)
                    val sameDay = sameMonth &&
                        date.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)
                    score += when {
                        sameDay -> 70.0
                        sameMonth -> 30.0
                        else -> 0.0
                    }
                }

                if (useAntiRepeat) {
                    if (candidate.sourceLabel.isNotBlank() && candidate.sourceLabel == previousSource) {
                        score -= 35.0
                    }
                    if (candidate.albumKey.isNotBlank() && candidate.albumKey == previousAlbum) {
                        score -= 55.0
                    }
                }
                candidate to score
            }

            val chosen = scored.maxByOrNull { it.second }!!.first
            result += chosen.index
            previousSource = chosen.sourceLabel
            previousAlbum = chosen.albumKey
            pool.remove(chosen)
        }

        return result
    }

    fun modeLabel(mode: Int): String = when (mode.coerceIn(OFF, COMPLETE)) {
        QUALITY -> "Qualité"
        ANTI_REPEAT -> "Anti-répétition"
        MEMORIES -> "Souvenirs"
        COMPLETE -> "Complète"
        else -> "Désactivée"
    }
}
