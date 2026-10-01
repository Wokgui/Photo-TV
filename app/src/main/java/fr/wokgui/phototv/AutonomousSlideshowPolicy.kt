package fr.wokgui.phototv

import java.util.Calendar

object AutonomousSlideshowPolicy {
    data class Presentation(
        val smartSelectionMode: Int,
        val imageModeOverride: Int?,
        val preferFavorites: Boolean,
        val durationSecondsOverride: Int?
    )

    fun presentation(
        hour: Int,
        slideNumber: Long,
        scene: String?,
        qualityScore: Int = -1
    ): Presentation {
        val smartMode = when (hour.coerceIn(0, 23)) {
            in 6..10 -> SmartSelectionPolicy.MEMORIES
            in 11..17 -> SmartSelectionPolicy.QUALITY
            else -> SmartSelectionPolicy.COMPLETE
        }
        val cadence = (slideNumber % 12L).toInt()
        val mosaic = when {
            scene == SceneClassifier.PORTRAIT && cadence == 4 -> 4
            cadence == 6 -> 5
            cadence == 10 -> 6
            else -> null
        }
        val duration = when {
            qualityScore >= 85 -> 13
            scene == SceneClassifier.PORTRAIT || scene == SceneClassifier.PEOPLE -> 12
            scene == SceneClassifier.NIGHT -> 11
            qualityScore in 0..39 -> 7
            else -> null
        }
        return Presentation(
            smartSelectionMode = smartMode,
            imageModeOverride = mosaic,
            preferFavorites = hour >= 18 || hour <= 1,
            durationSecondsOverride = duration
        )
    }

    fun current(
        slideNumber: Long,
        scene: String? = null,
        qualityScore: Int = -1,
        now: Calendar = Calendar.getInstance()
    ): Presentation = presentation(now.get(Calendar.HOUR_OF_DAY), slideNumber, scene, qualityScore)
}
