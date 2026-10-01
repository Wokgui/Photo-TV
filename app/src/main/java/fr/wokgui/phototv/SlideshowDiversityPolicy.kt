package fr.wokgui.phototv

import kotlin.math.abs

object SlideshowDiversityPolicy {
    data class Candidate(
        val index: Int,
        val source: String,
        val album: String,
        val width: Int,
        val height: Int,
        val takenAt: Long
    )

    fun reorder(indices: List<Int>, candidates: List<Candidate>, currentIndex: Int): List<Int> {
        if (indices.size < 2) return indices
        val byIndex = candidates.associateBy { it.index }
        val out = mutableListOf<Int>()
        val remaining = indices.toMutableList()
        var previous = byIndex[currentIndex]

        while (remaining.isNotEmpty()) {
            val pickPos = if (previous == null) 0 else {
                val limit = minOf(remaining.size, 24)
                (0 until limit).maxByOrNull { pos ->
                    separationScore(previous, byIndex[remaining[pos]])
                } ?: 0
            }
            val picked = remaining.removeAt(pickPos)
            out += picked
            previous = byIndex[picked] ?: previous
        }
        return out
    }

    internal fun separationScore(a: Candidate, b: Candidate?): Int {
        if (b == null) return 0
        var score = 0
        if (a.source != b.source) score += 12
        if (a.album != b.album) score += 8
        if (a.takenAt > 0L && b.takenAt > 0L) {
            val delta = abs(a.takenAt - b.takenAt)
            score += when {
                delta < 8_000L && similarGeometry(a, b) -> -100
                delta < 30_000L -> -30
                delta >= 60L * 60L * 1000L -> 12
                else -> 2
            }
        }
        return score
    }

    private fun similarGeometry(a: Candidate, b: Candidate): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return true
        val arA = a.width.toFloat() / a.height
        val arB = b.width.toFloat() / b.height
        return abs(arA - arB) <= .04f
    }
}
