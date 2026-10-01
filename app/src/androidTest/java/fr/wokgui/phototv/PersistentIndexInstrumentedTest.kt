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
class PersistentIndexInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun mediaMetadataRoundTripsWithStableSignature() {
        val uri = Uri.parse("content://phototv-test/cache/metadata-1")
        val signature = MediaMetadataStore.Signature(
            modified = 123456L,
            size = 987654L,
            mime = "image/jpeg"
        )
        val info = MediaInfo(
            width = 4032,
            height = 3024,
            takenAt = 1_700_000_000_000L,
            camera = "Test Camera",
            location = "49.10000, 6.20000"
        )

        MediaMetadataStore.writeInfo(context, uri, signature, info)
        val cached = MediaMetadataStore.readInfo(context, uri, signature)

        assertNotNull(cached)
        assertEquals(info.width, cached!!.width)
        assertEquals(info.height, cached.height)
        assertEquals(info.takenAt, cached.takenAt)
        assertEquals(info.camera, cached.camera)
        assertEquals(info.location, cached.location)
    }

    @Test
    fun visualFingerprintRoundTrips() {
        val uri = Uri.parse("content://phototv-test/cache/fingerprint-1")
        val signature = MediaMetadataStore.Signature(100L, 200L, "image/png")
        val fp = MediaMetadataStore.CachedFingerprint(
            hash = 0x1234ABCDL,
            meanLuma = 127,
            aspectRatio = 1.5f
        )

        MediaMetadataStore.writeFingerprint(context, uri, signature, fp)
        val cached = MediaMetadataStore.readFingerprint(context, uri, signature)

        assertNotNull(cached)
        assertEquals(fp.hash, cached!!.hash)
        assertEquals(fp.meanLuma, cached.meanLuma)
        assertEquals(fp.aspectRatio, cached.aspectRatio, 0.0001f)
    }

    @Test
    fun folderScanIndexRoundTripsPhotoItems() {
        val folder = Uri.parse("content://phototv-test/folder/one")
        val item = PhotoItem(
            uri = Uri.parse("content://phototv-test/photo/one"),
            title = "Photo One",
            albums = linkedSetOf("Album A"),
            takenAt = 123L,
            description = "Description",
            location = "49.0, 6.0",
            camera = "Camera",
            width = 1000,
            height = 700,
            mediaType = "image",
            sourceId = "source-1"
        )

        FolderScanIndex.write(
            context = context,
            folderUri = folder,
            exactMode = true,
            signature = "signature-1",
            items = listOf(item),
            missingExactAlbumMetadata = false
        )

        val cached = FolderScanIndex.read(context, folder, true, "signature-1")
        assertNotNull(cached)
        assertEquals(1, cached!!.items.size)
        assertEquals(item.title, cached.items.first().title)
        assertEquals(item.albums, cached.items.first().albums)
    }
}
