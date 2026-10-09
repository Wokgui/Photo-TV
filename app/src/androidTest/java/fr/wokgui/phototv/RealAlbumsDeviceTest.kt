package fr.wokgui.phototv

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealAlbumsDeviceTest {
    private fun pairs(ui: PhotoTvView): List<Pair<String,Int>> {
        val method=PhotoTvView::class.java.getDeclaredMethod("albumPairs").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST") return method.invoke(ui) as List<Pair<String,Int>>
    }
    @Test fun realAlbumsHavePhotosAndCanBeDecoded() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val report=JSONObject()
        val output=File(context.getExternalFilesDir(null),"albums-device-verification.json")
        var scenario: ActivityScenario<MainActivity>?=null
        val saved=listOf("photo_tv_ui","photo_tv_network_settings").associateWith {
            context.getSharedPreferences(it,Context.MODE_PRIVATE).all.toMap()
        }
        try {
            assertTrue("Drive must already be configured",DriveSync.configured(context))
            val connection=DriveSync::class.java.getDeclaredMethod("connection",Context::class.java).apply { isAccessible=true }.invoke(DriveSync,context) as JSONObject
            val remote=DriveSync::class.java.getDeclaredMethod("json",JSONObject::class.java,String::class.java).apply { isAccessible=true }.invoke(DriveSync,connection,"/archives") as JSONObject
            val expected=remote.getJSONArray("files")
            fun missingArchives(): Int {
                val catalogs=File(context.filesDir,"drive-catalog").listFiles().orEmpty().filter { it.extension=="json" }.map { JSONObject(it.readText()) }
                return (0 until expected.length()).count { i ->
                    val id=expected.getJSONObject(i).getString("id")
                    catalogs.none { it.optString("archive")==id && (it.optBoolean("albumsDiscovered") || it.optBoolean("complete")) }
                }
            }
            // Only discover missing albums. Per-photo title indexing remains a
            // background job and must not delay verification of readable albums.
            if (missingArchives()>0) runCatching { DriveSync.sync(context) { missingArchives()==0 } }
            val missing=missingArchives()
            report.put("discoveryStatus",DriveSync.statusText(context))
            report.put("expectedArchives",expected.length()).put("missingArchives",missing)
            val items=DriveRemoteLibrary.load(context)
            val sources=SourceStore.loadAll(context)
            val picked=sources.filterIsInstance<PhotoSourceSpec.Picked>().flatMap { PickedLibrary.load(context,it.uris) }
            val trees=sources.filterIsInstance<PhotoSourceSpec.Tree>().flatMap { TakeoutLibrary.load(context,it.uri,exactMode=it.exactMode).items }
            val all=items+picked+trees
            val albums=all.flatMap { photo -> photo.albums.map { it to photo } }.groupBy({it.first},{it.second})
            report.put("photos",all.size).put("realAlbums",albums.size)
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).putExtra("phototv_test_mode",true))
            SystemClock.sleep(1000)
            var ui:PhotoTvView?=null
            scenario!!.onActivity { activity ->
                ui=PhotoTvView(activity,onExactSource={},onPickPhotos={},automationMode=false)
                activity.setContentView(ui)
                assertTrue("No examples in an empty production library",pairs(ui!!).isEmpty())
                ui!!.setLibrary(all,exactAlbums=true,sourceName="Google Photos • Drive en ligne")
                val shown=pairs(ui!!)
                val field=PhotoTvView::class.java.getDeclaredField("albumIndex").apply { isAccessible=true }
                @Suppress("UNCHECKED_CAST") val index=field.get(ui) as Map<String,List<PhotoItem>>
                assertTrue(shown.all { it.second>0 && index[it.first]?.size==it.second })
                assertTrue(albums.keys.all { name -> shown.any { it.first==name } })
                report.put("displayedAlbums",shown.size).put("emptyOrExampleAlbums",0)
                ui!!.setAutomationPage(1)
            }
            output.writeText(report.toString(2))
            val results=JSONArray()
            val failed=mutableListOf<String>()
            val decoded=mutableMapOf<String,Boolean>()
            albums.toSortedMap().forEach { (name,photos) ->
                var ok=false
                var lastError=""
                for(photo in photos.take(3)) {
                    val key=photo.uri.toString()
                    ok=decoded[key] ?: runCatching {
                        val options=BitmapFactory.Options().apply { inSampleSize=8 }
                        val bitmap=if(DriveRemoteLibrary.isRemote(photo.uri)) {
                            BitmapFactory.decodeFile(DriveRemoteLibrary.materialize(context,photo.uri).absolutePath,options)
                        } else context.contentResolver.openInputStream(photo.uri)?.use { BitmapFactory.decodeStream(it,null,options) }
                        check(bitmap!=null && bitmap.width>0 && bitmap.height>0) { "Image non décodable" }
                        bitmap.recycle();true
                    }.onFailure { lastError=it.message.orEmpty() }.getOrDefault(false).also { decoded[key]=it }
                    if(ok)break
                }
                if(!ok)failed+=name
                results.put(JSONObject().put("album",name).put("photos",photos.size).put("decoded",ok).put("error",if(ok) "" else lastError))
                report.put("albums",results).put("failedAlbums",failed.size)
                output.writeText(report.toString(2))
                android.util.Log.i("RealAlbumsAudit","Checked ${results.length()}/${albums.size}; failures=${failed.size}")
            }
            val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.takeScreenshot(File(context.getExternalFilesDir(null),"real-albums-screen.png"))
            output.writeText(report.toString(2))
            assertEquals("Missing Takeout archives",0,missing)
            assertTrue("No real albums loaded",albums.isNotEmpty())
            assertEquals("Albums whose first 3 photos could not be opened",0,failed.size)
        } finally {
            output.writeText(report.toString(2))
            scenario?.close()
            saved.forEach { (name,values) ->
                val edit=context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear()
                values.forEach { (key,value) -> when(value) {
                    is String -> edit.putString(key,value)
                    is Int -> edit.putInt(key,value)
                    is Long -> edit.putLong(key,value)
                    is Float -> edit.putFloat(key,value)
                    is Boolean -> edit.putBoolean(key,value)
                    is Set<*> -> edit.putStringSet(key,value.filterIsInstance<String>().toSet())
                } }
                assertTrue(edit.commit())
            }
        }
    }
}
