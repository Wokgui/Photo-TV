package fr.wokgui.phototv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class PhotoItem(
    val uri: Uri,
    val title: String,
    val album: String,
    val takenAt: Long = 0L
)

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQ_EXACT_FOLDER = 42
        private const val REQ_PHOTOS = 43
        private const val PREFS = "photo_tv"
        private const val KEY_TREE = "takeout_tree"
    }

    private lateinit var ui: PhotoTvView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        ui = PhotoTvView(
            context = this,
            onExactSource = { openExactSource() },
            onPickPhotos = { openPhotoPicker() }
        )
        setContentView(ui)

        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null)?.let { saved ->
            val uri = runCatching { Uri.parse(saved) }.getOrNull()
            if (uri != null) importTree(uri, silent = true)
        }
    }

    private fun openExactSource() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            ),
            REQ_EXACT_FOLDER
        )
    }

    private fun openPhotoPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            REQ_PHOTOS
        )
    }

    @Deprecated("Deprecated in Android framework, retained for broad TV compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        when (requestCode) {
            REQ_EXACT_FOLDER -> data.data?.let { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
                importTree(uri, silent = false)
            }

            REQ_PHOTOS -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
                }
                if (uris.isEmpty()) data.data?.let { uris += it }

                val items = uris.distinct().map { uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val name = displayName(uri)
                    PhotoItem(
                        uri = uri,
                        title = stripExtension(name),
                        album = "Album indisponible — utilisez le mode exact",
                        takenAt = 0L
                    )
                }
                ui.setLibrary(items, exactAlbums = false)
            }
        }
    }

    private fun importTree(uri: Uri, silent: Boolean) {
        val root = DocumentFile.fromTreeUri(this, uri) ?: return
        if (!silent) ui.showLoading("Analyse de Google Photos / Takeout…")

        Thread {
            val loaded = mutableListOf<PhotoItem>()
            scanFolder(root, loaded)
            runOnUiThread {
                ui.setLibrary(loaded, exactAlbums = true)
            }
        }.start()
    }

    private fun scanFolder(dir: DocumentFile, out: MutableList<PhotoItem>) {
        val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
        val images = children.filter { it.isFile && it.type?.startsWith("image/") == true }
        val jsons = children.filter { it.isFile && it.name?.endsWith(".json", true) == true }

        val albumName = exactAlbumName(dir, jsons)

        for (image in images) {
            val imageName = image.name ?: continue
            val sidecar = jsons.firstOrNull { json ->
                val n = json.name?.removeSuffix(".json").orEmpty()
                n == imageName || n.startsWith(imageName)
            }

            var takenAt = image.lastModified()
            if (sidecar != null) {
                runCatching {
                    val text = contentResolver.openInputStream(sidecar.uri)!!.use {
                        BufferedReader(InputStreamReader(it)).readText()
                    }
                    val rootJson = JSONObject(text)
                    val stamp = rootJson
                        .optJSONObject("photoTakenTime")
                        ?.optString("timestamp")
                        ?.toLongOrNull()
                    if (stamp != null) takenAt = stamp * 1000L
                }
            }

            out += PhotoItem(
                uri = image.uri,
                title = stripExtension(imageName),
                album = albumName,
                takenAt = takenAt
            )
        }

        children.filter { it.isDirectory }.forEach { scanFolder(it, out) }
    }

    private fun exactAlbumName(dir: DocumentFile, jsons: List<DocumentFile>): String {
        val ordered = jsons.sortedBy { if (it.name.equals("metadata.json", true)) 0 else 1 }
        for (jsonFile in ordered) {
            val exact = runCatching {
                val text = contentResolver.openInputStream(jsonFile.uri)!!.use {
                    BufferedReader(InputStreamReader(it)).readText()
                }
                JSONObject(text)
                    .optJSONObject("albumData")
                    ?.optString("title")
                    ?.trim()
                    .orEmpty()
            }.getOrDefault("")
            if (exact.isNotBlank()) return exact
        }
        return dir.name ?: "Album"
    }

    private fun displayName(uri: Uri): String {
        var result = "Photo"
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = cursor.getString(index) ?: result
                }
            }
        }
        return result
    }

    private fun stripExtension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
