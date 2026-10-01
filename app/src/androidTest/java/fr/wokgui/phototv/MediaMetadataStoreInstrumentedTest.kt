package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaMetadataStoreInstrumentedTest {
    @Test
    fun smartCropAnchorRoundTripsThroughPersistentDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://phototv-test/crop-" + System.nanoTime())
        val signature = MediaMetadataStore.Signature(123L, 456L, "image/jpeg")
        val anchor = MediaMetadataStore.CachedCropAnchor(.23f, .71f, 2.5f)

        MediaMetadataStore.writeCropAnchor(context, uri, signature, anchor)
        val restored = MediaMetadataStore.readCropAnchor(context, uri, signature)

        assertNotNull(restored)
        assertEquals(anchor.x, restored!!.x, .0001f)
        assertEquals(anchor.y, restored.y, .0001f)
        assertEquals(anchor.weight, restored.weight, .0001f)
    }
}
