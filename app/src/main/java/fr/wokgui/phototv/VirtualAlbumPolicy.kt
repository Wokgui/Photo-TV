package fr.wokgui.phototv

import java.util.Calendar
import java.util.Locale

object VirtualAlbumPolicy {
    fun labels(
        favorite: Boolean,
        takenAt: Long,
        location: String,
        scene: String,
        qualityScore: Int?,
        now: Calendar = Calendar.getInstance()
    ): Set<String> {
        val out = linkedSetOf<String>()
        if (favorite) out += "★ Favoris"

        if (takenAt > 0L) {
            val date = Calendar.getInstance().apply { timeInMillis = takenAt }
            out += "Année · " + date.get(Calendar.YEAR)
            if (MemoriesPolicy.isSameDayPreviousYear(takenAt, now)) {
                out += "Souvenirs · Aujourd’hui"
            }
        }

        scene.trim().takeIf { it.isNotBlank() }?.let { out += "Scène · $it" }

        qualityScore?.coerceIn(0, 100)?.let { score ->
            if (score >= 70) out += "Qualité · " + QualityScorePolicy.label(score)
        }

        val place = location.trim()
        if (place.isNotBlank()) {
            val looksCoordinates = Regex("""^-?\d{1,3}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?$""")
                .matches(place)
            if (looksCoordinates) {
                out += "Avec localisation"
            } else {
                val compact = place.substringBefore(',').trim().take(36)
                if (compact.isNotBlank()) out += "Lieu · $compact"
            }
        }
        return out
    }
}
