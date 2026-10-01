package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityScorePolicyTest {
    @Test fun excellentImageScoresAboveWeakImage() {
        val excellent = QualityScorePolicy.score(
            QualityScorePolicy.Metrics(.95f, .95f, .85f, .9f, portrait = true, favorite = true, megapixels = 12f)
        )
        val weak = QualityScorePolicy.score(
            QualityScorePolicy.Metrics(.15f, .2f, .15f, .25f, megapixels = 1f)
        )
        assertTrue(excellent >= 85)
        assertTrue(excellent > weak)
    }

    @Test fun labelsAreStable() {
        assertEquals("Excellente", QualityScorePolicy.label(90))
        assertEquals("Très bonne", QualityScorePolicy.label(75))
        assertEquals("Faible", QualityScorePolicy.label(10))
    }
}
