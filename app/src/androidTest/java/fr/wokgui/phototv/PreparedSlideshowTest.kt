package fr.wokgui.phototv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PreparedSlideshowTest {
    private fun field(name: String)=PhotoTvView::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun method(name: String,vararg types: Class<*>)=PhotoTvView::class.java.getDeclaredMethod(name,*types).apply { isAccessible=true }
    private fun restore(context:Context,saved:Map<String,Map<String,*>>) {
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
    private fun awaitMainContent(scenario:ActivityScenario<MainActivity>) {
        val root=MainActivity::class.java.getDeclaredField("ui").apply { isAccessible=true }
        val deadline=SystemClock.uptimeMillis()+30_000L
        var initialized=false
        do {
            scenario.onActivity { initialized=(root.get(it) as? PhotoTvView)?.isAttachedToWindow==true }
            if(!initialized)SystemClock.sleep(100)
        } while(!initialized && SystemClock.uptimeMillis()<deadline)
        assertTrue("Wait for the real startup view before installing the test view",initialized)
    }
    private fun attach(a:MainActivity,items:List<PhotoItem>):PhotoTvView {
        val ui=PhotoTvView(a,onExactSource={},onPickPhotos={},automationMode=false)
        listOf("startDirectly","resumeWasSlideshow","backgroundAnalysis","randomOrder","autonomousSlideshow","fixedImage","favoritesOnly").forEach { field(it).setBoolean(ui,false) }
        field("autoStartMinutes").setInt(ui,0)
        field("durationSeconds").setInt(ui,10)
        listOf("hiddenAlbums","excludedUris","sessionExcludedUris").forEach { (field(it).get(ui) as MutableSet<*>).clear() }
        listOf("albumRules","sourceRules").forEach { (field(it).get(ui) as MutableMap<*,*>).clear() }
        field("remoteSourceFilter").set(ui,null)
        field("transitionSeconds").setFloat(ui,.3f)
        a.setContentView(ui)
        ui.setLibrary(items,exactAlbums=true)
        field("currentPhoto").setInt(ui,0)
        ui.setAutomationPage(0)
        return ui
    }

    @Test fun pendingPhotoDoesNotConsumeQueueAndPreparedNextSurvivesMemoryPressure() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val saved=listOf("photo_tv_ui","photo_tv_network_settings").associateWith { context.getSharedPreferences(it,0).all.toMap() }
        val items=(0..2).map { PhotoItem(Uri.parse("phototv-drive://media/${it.toString().padStart(64,'0')}"),"Test $it",setOf("Test")) }
        val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("phototv_test_mode",true))
        try {
            awaitMainContent(scenario)
            lateinit var ui:PhotoTvView
            lateinit var first:Bitmap
            lateinit var second:Bitmap
            scenario.onActivity { a ->
                ui=attach(a,items)
                @Suppress("UNCHECKED_CAST") val thumbs=field("bitmapLoading").get(ui) as MutableSet<String>
                @Suppress("UNCHECKED_CAST") val hdLoading=field("highResLoading").get(ui) as MutableSet<String>
                items.forEach { thumbs.add(it.uri.toString());hdLoading.add(it.uri.toString()) }
                first=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                second=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                @Suppress("UNCHECKED_CAST") val cache=field("highResCache").get(ui) as MutableMap<String,Bitmap>
                cache[items[0].uri.toString()]=first
                field("highResCacheBytes").setLong(ui,first.allocationByteCount.toLong())
                assertSame(first,method("currentBitmap").invoke(ui))
                field("randomOrder").setBoolean(ui,true)
                field("slideshow").setBoolean(ui,true)
                @Suppress("UNCHECKED_CAST") val queue=field("shuffleBag").get(ui) as MutableList<Int>
                queue.clear();queue.addAll(listOf(1,2))
                method("preloadAroundCurrent").invoke(ui)
                method("slideshowNext",Int::class.javaPrimitiveType!!).invoke(ui,1)
                assertEquals(0,field("currentPhoto").getInt(ui))
                assertEquals(listOf(1,2),queue)
                assertSame(first,method("currentBitmap").invoke(ui))
                assertTrue((field("history").get(ui) as List<*>).isEmpty())
                cache[items[1].uri.toString()]=second
                cache[items[2].uri.toString()]=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
                field("highResCacheBytes").setLong(ui,cache.values.sumOf { it.allocationByteCount.toLong() })
                ui.onMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                assertSame(first,cache[items[0].uri.toString()])
                assertSame(second,cache[items[1].uri.toString()])
                assertFalse(cache.containsKey(items[2].uri.toString()))
                method("slideshowNext",Int::class.javaPrimitiveType!!).invoke(ui,1)
                assertEquals(1,field("currentPhoto").getInt(ui))
                assertEquals(listOf(2),queue)
                assertSame(second,method("currentBitmap").invoke(ui))
                assertSame(first,field("transitionPhotoBitmap").get(ui))
                method("stopSlideshow").invoke(ui)
            }
        } finally { scenario.close();restore(context,saved) }
    }

    @Test fun realDrivePhotosAreReadyBeforeTheirScheduledTransitions() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val saved=listOf("photo_tv_ui","photo_tv_network_settings").associateWith { context.getSharedPreferences(it,0).all.toMap() }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wakeUp()
        val items=DriveRemoteLibrary.load(context).filter { it.mediaType == "image" }.groupBy { it.albums.firstOrNull() }.values.take(8).map { it.first() }
        assertEquals(8,items.size)
        val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("phototv_test_mode",true))
        try {
            awaitMainContent(scenario)
            lateinit var ui:PhotoTvView
            scenario.onActivity { ui=attach(it,items);method("preloadAroundCurrent").invoke(ui) }
            val ready=method("photoReadyForTransition",PhotoItem::class.java)
            val warmDeadline=SystemClock.uptimeMillis()+90_000L
            var warm=false
            do {
                scenario.onActivity {
                    method("preloadAroundCurrent").invoke(ui)
                    assertTrue("The tested view must be visible on the actual device",ui.isAttachedToWindow)
                    warm=ready.invoke(ui,items[0])==true && ready.invoke(ui,items[1])==true
                }
                if(!warm)SystemClock.sleep(150)
            } while(!warm && SystemClock.uptimeMillis()<warmDeadline)
            assertTrue("Prepare initial and following Drive photos before timing transitions; loading="+(field("highResLoading").get(ui) as Set<*>).size+", retries="+(field("driveRetryAfter").get(ui) as Map<*,*>).size,warm)
            scenario.onActivity { method("startSlideshow").invoke(ui) }
            var transitions=0
            val preparedLeadMs=mutableListOf<Long>()
            for(index in 0 until 7) {
                var nextReadyAt=0L
                var changed=false
                val deadline=SystemClock.uptimeMillis()+14_000L
                do {
                    scenario.onActivity {
                        assertTrue(ui.isAttachedToWindow)
                        val actual=field("currentPhoto").getInt(ui)
                        if(actual==index && ready.invoke(ui,items[index+1])==true && nextReadyAt==0L)
                            nextReadyAt=SystemClock.uptimeMillis()
                        assertTrue("Displayed photo must stay available",method("currentPhotoLoaded").invoke(ui)==true)
                        assertTrue("No unexpected slide skipped",actual==index || actual==index+1)
                        changed=actual==index+1
                    }
                    if(!changed)SystemClock.sleep(200)
                } while(!changed && SystemClock.uptimeMillis()<deadline)
                assertTrue("Scheduled transition must show its already prepared photo",changed)
                val lead=SystemClock.uptimeMillis()-nextReadyAt
                assertTrue("Photo ${index+1} must be prepared at least one second before transition",nextReadyAt>0L && lead>=1000L)
                preparedLeadMs.add(lead)
                transitions++
            }
            scenario.onActivity { method("stopSlideshow").invoke(ui) }
            File(context.getExternalFilesDir(null),"prepared-slideshow-verification.json").writeText(JSONObject().put("secondsPerPhoto",10).put("realDrivePhotos",items.size).put("immediateTransitions",transitions).put("preparedLeadMs",org.json.JSONArray(preparedLeadMs)).put("nextReadyBeforeDeadline",true).put("loadingScreensDuringTransitions",0).toString(2))
        } finally { scenario.close();restore(context,saved) }
    }
}
