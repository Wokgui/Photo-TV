package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePolicyTest {
    @Test fun normalizesAndLabelsProfiles() {
        assertEquals("Principal", ProfilePolicy.label(-5))
        assertEquals("Invités", ProfilePolicy.label(99))
        assertTrue(ProfilePolicy.isGuest(2))
    }
}
