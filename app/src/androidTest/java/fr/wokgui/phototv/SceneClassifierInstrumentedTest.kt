package fr.wokgui.phototv

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneClassifierInstrumentedTest {
    @Test
    fun darkBitmapIsClassifiedAsNight() {
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(12, 14, 18))
        assertEquals(SceneClassifier.NIGHT, SceneClassifier.classify(bmp))
        bmp.recycle()
    }

    @Test
    fun blueLandscapeIsClassifiedAsSeaOrSky() {
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(70, 130, 220))
        assertEquals(SceneClassifier.SEA_SKY, SceneClassifier.classify(bmp))
        bmp.recycle()
    }

    @Test
    fun onDeviceLabelerRunsWithoutCrashing() {
        val bmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(70, 160, 80))
        val scene = OnDeviceSceneLabeler.classify(bmp)
        org.junit.Assert.assertTrue(scene.isNotBlank())
        bmp.recycle()
    }

}
