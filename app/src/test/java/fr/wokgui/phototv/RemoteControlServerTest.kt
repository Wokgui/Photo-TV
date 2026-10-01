package fr.wokgui.phototv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlServerTest {
    private fun server(): RemoteControlServer =
        RemoteControlServer(
            token = "0123456789abcdef01234567",
            stateProvider = {
                RemoteControlState(
                    title = "Photo",
                    album = "Album",
                    slideshow = false,
                    paused = false,
                    durationSeconds = 10,
                    transition = "Fondu",
                    imageMode = "Adapter",
                    albums = emptyList(),
                    sources = emptyList(),
                    sourceFilter = null,
                    transitions = emptyList(),
                    imageModes = emptyList(),
                    smartModes = emptyList(),
                    smartMode = "Désactivée"
                )
            },
            onCommand = {}
        )

    @Test
    fun remotePageUsesPostForMutatingActions() {
        val page = server().pageForTest()
        assertTrue(page.contains("method:'POST'"))
        assertTrue(page.contains("'X-Photo-TV-Token':t"))
        assertFalse(page.contains("/action?t="))
        assertTrue(page.contains("fetch('/status',{headers:auth})"))
    }

    @Test
    fun onlyPrivateIpv4RangesAreAccepted() {
        assertTrue(RemoteControlServer.isPrivateIpv4("10.0.0.2"))
        assertTrue(RemoteControlServer.isPrivateIpv4("172.16.4.5"))
        assertTrue(RemoteControlServer.isPrivateIpv4("172.31.255.254"))
        assertTrue(RemoteControlServer.isPrivateIpv4("192.168.1.20"))
        assertFalse(RemoteControlServer.isPrivateIpv4("172.15.1.2"))
        assertFalse(RemoteControlServer.isPrivateIpv4("172.32.1.2"))
        assertFalse(RemoteControlServer.isPrivateIpv4("8.8.8.8"))
        assertFalse(RemoteControlServer.isPrivateIpv4("127.0.0.1"))
    }
}
