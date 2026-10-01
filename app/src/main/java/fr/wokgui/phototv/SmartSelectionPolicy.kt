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
        val pool = candidates.filter { it.index != currentIndex }
        if (pool.isEmpty()) return emptyList()
        if (mode == OFF) return pool.map { it.index }.shuffled(random)

        val useQuality = mode == QUALITY || mode == COMPLETE
        val useAntiRepeat = mode == ANTI_REPEAT || mode == COMPLETE
        val useMemories = mode == MEMORIES || mode == COMPLETE

        val scored = pool.map { candidate ->
            var score = random.nextDouble() * 100.0

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

            candidate to score
        }.sortedByDescending { it.second }.toMutableList()

        if (!useAntiRepeat) return scored.map { it.first.index }

        var previousSource = candidates.firstOrNull { it.index == currentIndex }?.sourceLabel.orEmpty()
        var previousAlbum = candidates.firstOrNull { it.index == currentIndex }?.albumKey.orEmpty()

        for (position in scored.indices) {
            fun conflicts(candidate: Candidate): Boolean =
                (candidate.sourceLabel.isNotBlank() && candidate.sourceLabel == previousSource) ||
                    (candidate.albumKey.isNotBlank() && candidate.albumKey == previousAlbum)

            if (conflicts(scored[position].first)) {
                val searchEnd = minOf(scored.lastIndex, position + 12)
                var replacement = -1
                for (j in position + 1..searchEnd) {
                    if (!conflicts(scored[j].first)) {
                        replacement = j
                        break
                    }
                }
                if (replacement >= 0) {
                    val tmp = scored[position]
                    scored[position] = scored[replacement]
                    scored[replacement] = tmp
                }
            }

            val chosen = scored[position].first
            previousSource = chosen.sourceLabel
            previousAlbum = chosen.albumKey
        }

        return scored.map { it.first.index }
    }

    fun modeLabel(mode: Int): String = when (mode.coerceIn(OFF, COMPLETE)) {
        QUALITY -> "Qualité"
        ANTI_REPEAT -> "Anti-répétition"
        MEMORIES -> "Souvenirs"
        COMPLETE -> "Complète"
        else -> "Désactivée"
    }
}
