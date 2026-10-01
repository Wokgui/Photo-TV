package fr.wokgui.phototv

import kotlin.math.abs

object MosaicSelectionPolicy {
    data class Candidate(
        val index: Int,
        val source: String,
        val album: String,
        val width: Int,
        val height: Int,
        val takenAt: Long,
        val mediaType: String,
        val scene: String = ""
    )

    fun select(candidates: List<Candidate>, currentIndex: Int, count: Int): List<Int> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val byIndex = candidates.associateBy { it.index }
        val current = byIndex[currentIndex]?.takeIf { it.mediaType != "video" }
            ?: candidates.firstOrNull { it.mediaType != "video" }
            ?: return emptyList()
        val selected = mutableListOf(current)
        val remaining = candidates.filter { it.index != current.index && it.mediaType != "video" }.toMutableList()

        while (selected.size < count && remaining.isNotEmpty()) {
            val best = remaining.maxByOrNull { candidate ->
                selected.minOf { existing -> diversityScore(existing, candidate) }
            } ?: break
            selected += best
            remaining.remove(best)
        }
        return selected.map { it.index }
    }

    internal fun diversityScore(a: Candidate, b: Candidate): Int {
        var score = 0
        if (a.source != b.source) score += 40
        if (a.album != b.album) score += 25
        if (a.scene.isNotBlank() && b.scene.isNotBlank() && a.scene != b.scene) score += 28
        if (orientation(a) != orientation(b)) score += 18
        if (a.takenAt > 0L && b.takenAt > 0L) {
            val delta = abs(a.takenAt - b.takenAt)
            score += when {
                delta >= 30L * 24L * 60L * 60L * 1000L -> 25
                delta >= 24L * 60L * 60L * 1000L -> 16
                delta >= 60L * 60L * 1000L -> 8
                delta < 15_000L -> -35
                else -> 2
            }
        }
        return score
    }

    private fun orientation(c: Candidate): Int = when {
        c.width <= 0 || c.height <= 0 -> 0
        c.width > c.height * 1.12f -> 1
        c.height > c.width * 1.12f -> 2
        else -> 3
    }
}
