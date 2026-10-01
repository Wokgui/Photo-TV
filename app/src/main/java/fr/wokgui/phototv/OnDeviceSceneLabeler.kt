package fr.wokgui.phototv

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OnDeviceSceneLabeler {
    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(.55f)
                .build()
        )
    }

    fun classify(bitmap: Bitmap): String {
        val labels = mutableListOf<String>()
        val latch = CountDownLatch(1)
        runCatching {
            labeler.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    labels += result
                        .sortedByDescending { it.confidence }
                        .map { it.text }
                }
                .addOnCompleteListener { latch.countDown() }
            latch.await(2, TimeUnit.SECONDS)
        }
        return SceneClassifier.fromLabels(labels) ?: SceneClassifier.classify(bitmap)
    }
}
