package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceAndPinSecurityInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun reset() {
        context = ApplicationProvider.getApplicationContext()
        SourceStore.clear(context)
        SettingsPinStore.clear(context)
        context.getSharedPreferences("photo_tv_network_sources", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun settingsPinIsHashedAndVerifiable() {
        SettingsPinStore.setPin(context, "482731")

        assertTrue(SettingsPinStore.hasPin(context))
        assertTrue(SettingsPinStore.verify(context, "482731"))
        assertFalse(SettingsPinStore.verify(context, "111111"))

        val prefs = context.getSharedPreferences("photo_tv_settings_lock", Context.MODE_PRIVATE)
        val all = prefs.all.values.joinToString("|")
        assertFalse(all.contains("482731"))
    }

    @Test
    fun multipleLocalSourcesSurvivePersistenceAndRemoval() {
        val first = Uri.parse("content://test/tree/first")
        val second = Uri.parse("content://test/tree/second")
        SourceStore.addTree(context, first, exactMode = true)
        SourceStore.addTree(context, second, exactMode = false)
        SourceStore.addPicked(
            context,
            listOf(
                Uri.parse("content://test/photo/1"),
                Uri.parse("content://test/photo/2")
            )
        )

        val loaded = SourceStore.loadAll(context)
        assertEquals(3, loaded.size)
        assertTrue(loaded.any { it is PhotoSourceSpec.Tree && it.uri == first && it.exactMode })
        assertTrue(loaded.any { it is PhotoSourceSpec.Tree && it.uri == second && !it.exactMode })
        assertTrue(loaded.any { it is PhotoSourceSpec.Picked && it.uris.size == 2 })

        SourceStore.removeAt(context, 1)
        assertEquals(2, SourceStore.loadAll(context).size)
    }

    @Test
    fun networkSourceBackupNeverContainsPassword() {
        NetworkSourceStore.upsert(
            context = context,
            kind = NetworkLibrary.Kind.SMB,
            baseUrl = "smb://192.168.1.20/photos/",
            username = "user"
        )

        val exported = NetworkSourceStore.exportJson(context).toString()
        assertTrue(exported.contains("192.168.1.20"))
        assertTrue(exported.contains("user"))
        assertFalse(exported.contains("password"))
        assertFalse(exported.contains("secret"))
    }
}
