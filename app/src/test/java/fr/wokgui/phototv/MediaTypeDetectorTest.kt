package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTypeDetectorTest {
    @Test
    fun detectsModernImagesFromExtensionWhenMimeIsGeneric() {
        assertEquals("image", MediaTypeDetector.classify("photo.HEIC", "application/octet-stream"))
        assertEquals("image", MediaTypeDetector.classify("photo.heif", null))
        assertEquals("image", MediaTypeDetector.classify("photo.avif", "application/octet-stream"))
    }

    @Test
    fun detectsVideosFromExtensionWhenMimeIsMissing() {
        assertEquals("video", MediaTypeDetector.classify("clip.mov", null))
        assertEquals("video", MediaTypeDetector.classify("clip.m4v", "application/octet-stream"))
        assertEquals("video", MediaTypeDetector.classify("clip.webm", ""))
    }

    @Test
    fun mimeTakesPriorityAndGifRemainsAnimated() {
        assertEquals("video", MediaTypeDetector.classify("weird.jpg", "video/mp4"))
        assertEquals("gif", MediaTypeDetector.classify("animation.bin", "image/gif"))
        assertEquals("image", MediaTypeDetector.classify("photo.bin", "image/jpeg"))
    }

    @Test
    fun rejectsUnknownFiles() {
        assertNull(MediaTypeDetector.classify("notes.txt", "text/plain"))
        assertNull(MediaTypeDetector.classify("archive.zip", "application/octet-stream"))
    }
}
