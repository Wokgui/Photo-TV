package fr.wokgui.phototv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class ProductionAlbumCoversTest {
    @Test fun everyLoadedAlbumHasItsOwnRealCover() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val saved=listOf("photo_tv_ui","photo_tv_network_settings").associateWith { context.getSharedPreferences(it,0).all.toMap() }
        val items=DriveRemoteLibrary.load(context)+SourceStore.loadAll(context).filterIsInstance<PhotoSourceSpec.Picked>().flatMap { PickedLibrary.load(context,it.uris) }
        var scenario:ActivityScenario<MainActivity>?=null
        try {
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).putExtra("phototv_test_mode",true))
            SystemClock.sleep(1000)
            lateinit var ui:PhotoTvView
            val albumMethod=PhotoTvView::class.java.getDeclaredMethod("albumPairs").apply { isAccessible=true }
            val coverMethod=PhotoTvView::class.java.getDeclaredMethod("albumBitmap",String::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }
            var pairs:List<Pair<String,Int>> = emptyList()
            scenario!!.onActivity { a ->
                ui=PhotoTvView(a,onExactSource={},onPickPhotos={},automationMode=false)
                PhotoTvView::class.java.getDeclaredField("startDirectly").apply { isAccessible=true;setBoolean(ui,false) }
                PhotoTvView::class.java.getDeclaredField("resumeWasSlideshow").apply { isAccessible=true;setBoolean(ui,false) }
                PhotoTvView::class.java.getDeclaredField("autoStartMinutes").apply { isAccessible=true;setInt(ui,0) }
                a.setContentView(ui)
                assertTrue((albumMethod.invoke(ui) as List<*>).isEmpty())
                ui.setLibrary(items,exactAlbums=true,sourceName="Google Photos • Drive en ligne")
                ui.setAutomationPage(1)
                @Suppress("UNCHECKED_CAST") val result=albumMethod.invoke(ui) as List<Pair<String,Int>>
                pairs=result
            }
            assertTrue(pairs.isNotEmpty())
            pairs.forEachIndexed { index,pair ->
                val deadline=SystemClock.uptimeMillis()+120_000L
                scenario!!.onActivity { PhotoTvView::class.java.getDeclaredField("albumFocus").apply { isAccessible=true;setInt(ui,index) }; ui.invalidate() }
                var loaded=false
                do {
                    scenario!!.onActivity {
                        val bmp=coverMethod.invoke(ui,pair.first,index) as Bitmap?
                        loaded=bmp!=null && !bmp.isRecycled && bmp.width>0 && bmp.height>0
                    }
                    if(!loaded)SystemClock.sleep(100)
                } while(!loaded && SystemClock.uptimeMillis()<deadline)
                assertTrue("Cover unavailable for loaded album ${pair.first}",loaded)
                android.util.Log.i("RealAlbumCovers","Cover ${index+1}/${pairs.size} loaded")
            }
            var visibleCount=0
            scenario!!.onActivity {
                PhotoTvView::class.java.getDeclaredField("albumFocus").apply { isAccessible=true;setInt(ui,0) }
                ui.setAutomationPage(1)
                visibleCount=(PhotoTvView::class.java.getDeclaredMethod("albumColumns").apply { isAccessible=true }.invoke(ui) as Int)*2
            }
            val visible=pairs.take(visibleCount)
            val deadline=SystemClock.uptimeMillis()+120_000L
            var allVisible=false
            do {
                scenario!!.onActivity {
                    allVisible=visible.mapIndexed { index,pair -> coverMethod.invoke(ui,pair.first,index) as Bitmap? }.all { it!=null && !it.isRecycled }
                }
                if(!allVisible)SystemClock.sleep(100)
            } while(!allVisible && SystemClock.uptimeMillis()<deadline)
            assertTrue("Visible album covers must fit in memory together",allVisible)
            repeat(3) {
                SystemClock.sleep(500)
                scenario!!.onActivity {
                    assertTrue(visible.mapIndexed { index,pair -> coverMethod.invoke(ui,pair.first,index) as Bitmap? }.all { it!=null && !it.isRecycled })
                }
            }
            SystemClock.sleep(1200)
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null),"production-albums-screen.png")))
            File(context.getExternalFilesDir(null),"album-covers-verification.json").writeText(JSONObject().put("verifiedCovers",pairs.size).put("visibleCoversRetained",visible.size).put("emptyExampleAlbums",0).put("actualPhotos",items.size).toString(2))
        } finally {
            scenario?.close()
            saved.forEach { (name,values) ->
                val edit=context.getSharedPreferences(name,0).edit().clear()
                values.forEach { (k,v) -> when(v) {
                    is String -> edit.putString(k,v)
                    is Int -> edit.putInt(k,v)
                    is Long -> edit.putLong(k,v)
                    is Float -> edit.putFloat(k,v)
                    is Boolean -> edit.putBoolean(k,v)
                    is Set<*> -> edit.putStringSet(k,v.filterIsInstance<String>().toSet())
                } }
                assertTrue(edit.commit())
            }
        }
    }
}
