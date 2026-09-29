package fr.wokgui.phototv

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.text.InputType
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedHashMap

data class PhotoItem(
    val uri: Uri,
    val title: String,
    val albums: Set<String>,
    val takenAt: Long = 0L,
    val description: String = "",
    val location: String = "",
    val camera: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val mediaType: String = "image"
) {
    val album: String
        get() = albums.firstOrNull() ?: "Album"

    val orientationLabel: String
        get() = when {
            width <= 0 || height <= 0 -> ""
            width > height -> "Paysage"
            height > width -> "Portrait"
            else -> "Carré"
        }

    val dimensionsLabel: String
        get() = if (width > 0 && height > 0) "${width} × ${height}" else ""
}

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQ_EXACT_FOLDER = 42
        private const val REQ_PHOTOS = 43
        private const val REQ_EXPORT_SETTINGS = 44
        private const val REQ_IMPORT_SETTINGS = 45
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
            onPickPhotos = { openPhotoPicker() },
            onWeatherLocation = { requestWeatherLocation() },
            onExportSettings = { exportSettings() },
            onImportSettings = { importSettings() }
        )
        setContentView(ui)

        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null)?.let { saved ->
            val uri = runCatching { Uri.parse(saved) }.getOrNull()
            if (uri != null) importTree(uri, silent = true)
        }
    }

    private fun exportSettings() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "Photo-TV-settings.json")
            },
            REQ_EXPORT_SETTINGS
        )
    }

    private fun importSettings() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            REQ_IMPORT_SETTINGS
        )
    }

    private fun requestWeatherLocation() {
        val input = EditText(this).apply {
            hint = "Ville ou code postal"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Ville météo")
            .setMessage("Laissez vide pour la détection automatique par le service météo.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("OK") { _, _ ->
                ui.setWeatherLocation(input.text?.toString().orEmpty())
            }
            .show()
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
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "image/jpeg", "image/png", "image/webp", "image/gif",
                        "image/heic", "image/heif", "video/mp4", "video/webm"
                    )
                )
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
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_TREE, uri.toString())
                    .apply()
                importTree(uri, silent = false)
            }

            REQ_EXPORT_SETTINGS -> data.data?.let { uri ->
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                        it.write(ui.exportSettingsJson())
                    }
                }
            }

            REQ_IMPORT_SETTINGS -> data.data?.let { uri ->
                runCatching {
                    val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    ui.importSettingsJson(raw)
                }
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
                    val mime = contentResolver.getType(uri).orEmpty()
                    val dims = mediaDimensions(uri, mime)
                    PhotoItem(
                        uri = uri,
                        title = stripExtension(name),
                        albums = linkedSetOf("Album indisponible — utilisez le mode exact"),
                        takenAt = 0L,
                        width = dims.first,
                        height = dims.second,
                        mediaType = if (mime.startsWith("video/")) "video" else "image"
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
            val raw = mutableListOf<PhotoItem>()
            scanFolder(root, raw)
            val merged = mergeAlbumMemberships(raw)
            runOnUiThread {
                ui.setLibrary(merged, exactAlbums = true)
            }
        }.start()
    }

    private fun scanFolder(dir: DocumentFile, out: MutableList<PhotoItem>) {
        val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
        val media = children.filter {
            it.isFile && (
                it.type?.startsWith("image/") == true ||
                    it.type?.startsWith("video/") == true
                )
        }
        val jsons = children.filter { it.isFile && it.name?.endsWith(".json", true) == true }
        val albumName = exactAlbumName(dir, jsons)

        for (file in media) {
            val mediaName = file.name ?: continue
            val sidecar = jsons.firstOrNull { json ->
                val n = json.name?.removeSuffix(".json").orEmpty()
                n == mediaName || n.startsWith(mediaName)
            }

            var takenAt = file.lastModified()
            var description = ""
            var location = ""
            var camera = ""

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

                    description = rootJson.optString("description").trim()

                    val geo = rootJson.optJSONObject("geoDataExif")
                        ?: rootJson.optJSONObject("geoData")
                    if (geo != null) {
                        val lat = geo.optDouble("latitude", 0.0)
                        val lon = geo.optDouble("longitude", 0.0)
                        if (lat != 0.0 || lon != 0.0) {
                            location = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)
                        }
                    }

                    camera = listOf(
                        rootJson.optString("cameraMake").trim(),
                        rootJson.optString("cameraModel").trim()
                    ).filter { it.isNotBlank() }.joinToString(" ")
                }
            }

            val mime = file.type.orEmpty()
            val dims = mediaDimensions(file.uri, mime)
            out += PhotoItem(
                uri = file.uri,
                title = stripExtension(mediaName),
                albums = linkedSetOf(albumName),
                takenAt = takenAt,
                description = description,
                location = location,
                camera = camera,
                width = dims.first,
                height = dims.second,
                mediaType = if (mime.startsWith("video/")) "video" else "image"
            )
        }

        children.filter { it.isDirectory }.forEach { scanFolder(it, out) }
    }

    private fun mergeAlbumMemberships(raw: List<PhotoItem>): List<PhotoItem> {
        val merged = LinkedHashMap<String, PhotoItem>()
        for (item in raw) {
            val key = buildString {
                append(item.title.lowercase())
                append('|')
                append(item.takenAt)
                append('|')
                append(item.width)
                append('x')
                append(item.height)
                append('|')
                append(item.mediaType)
            }

            val existing = merged[key]
            if (existing == null) {
                merged[key] = item
            } else {
                merged[key] = existing.copy(
                    albums = LinkedHashSet<String>().apply {
                        addAll(existing.albums)
                        addAll(item.albums)
                    },
                    description = existing.description.ifBlank { item.description },
                    location = existing.location.ifBlank { item.location },
                    camera = existing.camera.ifBlank { item.camera },
                    width = if (existing.width > 0) existing.width else item.width,
                    height = if (existing.height > 0) existing.height else item.height
                )
            }
        }
        return merged.values.toList()
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

    private fun mediaDimensions(uri: Uri, mime: String): Pair<Int, Int> {
        return if (mime.startsWith("video/")) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                retriever.release()
                w to h
            }.getOrDefault(0 to 0)
        } else {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                opts.outWidth to opts.outHeight
            }.getOrDefault(0 to 0)
        }
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
