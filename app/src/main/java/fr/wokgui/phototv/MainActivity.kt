package fr.wokgui.phototv

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.VideoView
import android.widget.Toast
import android.view.WindowManager
import android.text.InputType
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

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
    val mediaType: String = "image",
    val sourceCopies: Int = 1,
    val sourceId: String = ""
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
        private const val REQ_LOCAL_FOLDER = 46
    }

    private lateinit var ui: PhotoTvView
    private lateinit var videoView: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoView = VideoView(this).apply {
            visibility = View.GONE
        }

        ui = PhotoTvView(
            context = this,
            onExactSource = { openExactSource() },
            onFolderSource = { openLocalFolder() },
            onPickPhotos = { openPhotoPicker() },
            onWeatherLocation = { requestWeatherLocation() },
            onExportSettings = { exportSettings() },
            onImportSettings = { importSettings() },
            onAlbumSearch = { requestAlbumSearch() },
            onVideoPlayback = { uri, sound -> handleVideoPlayback(uri, sound) },
            supportsVideoPlayback = true
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                videoView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                ui,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(root)

        restoreSavedSource()
    }

    private fun handleVideoPlayback(uri: Uri?, sound: Boolean) {
        if (uri == null) {
            runCatching { videoView.stopPlayback() }
            videoView.visibility = View.GONE
            return
        }

        videoView.visibility = View.VISIBLE
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { player ->
            player.isLooping = true
            if (sound) player.setVolume(1f, 1f) else player.setVolume(0f, 0f)
            videoView.start()
        }
    }

    private fun requestAlbumSearch() {
        val input = EditText(this).apply {
            hint = "Nom de l'album"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rechercher un album")
            .setView(input)
            .setNeutralButton("Effacer") { _, _ -> ui.setAlbumSearch("") }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Rechercher") { _, _ ->
                ui.setAlbumSearch(input.text?.toString().orEmpty())
            }
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("Google Photos")
            .setMessage(
                "Pour conserver les noms d'albums exactement tels qu'ils existent dans Google Photos, " +
                    "Photo TV utilise les métadonnées d'un export Google Takeout. " +
                    "Sélectionnez le dossier exporté qui contient les fichiers JSON des albums."
            )
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Choisir Takeout") { _, _ ->
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    ),
                    REQ_EXACT_FOLDER
                )
            }
            .show()
    }

    private fun openLocalFolder() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            ),
            REQ_LOCAL_FOLDER
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
                SourceStore.saveTree(this, uri, exactMode = true)
                importTree(uri, silent = false, exactMode = true)
            }

            REQ_LOCAL_FOLDER -> data.data?.let { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                SourceStore.saveTree(this, uri, exactMode = false)
                importTree(uri, silent = false, exactMode = false)
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

                val distinct = uris.distinct()
                distinct.forEach { uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                SourceStore.savePicked(this, distinct)
                importPicked(distinct, silent = false)
            }
        }
    }

    private fun restoreSavedSource() {
        when (val source = SourceStore.load(this)) {
            is PhotoSourceSpec.Tree ->
                importTree(source.uri, silent = true, exactMode = source.exactMode)

            is PhotoSourceSpec.Picked ->
                importPicked(source.uris, silent = true)

            null -> Unit
        }
    }

    private fun importPicked(uris: List<Uri>, silent: Boolean) {
        if (!silent) ui.showLoading("Analyse des médias sélectionnés…")
        Thread {
            val items = PickedLibrary.load(this, uris)
            runOnUiThread {
                ui.setLibrary(
                    items,
                    exactAlbums = false,
                    sourceName = "Sélection de photos"
                )
                if (!silent) {
                    Toast.makeText(
                        this,
                        if (items.isEmpty()) "Aucun média compatible trouvé."
                        else "${items.size} média(s) sélectionné(s).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun importTree(uri: Uri, silent: Boolean, exactMode: Boolean) {
        if (!silent) {
            ui.showLoading(
                if (exactMode) "Analyse de Google Photos / Takeout…"
                else "Analyse du dossier local…"
            )
        }
        Thread {
            val loaded = TakeoutLibrary.load(this, uri, exactMode = exactMode)
            runOnUiThread {
                val source = if (exactMode) "Google Photos / Takeout" else "Dossier local"
                ui.setLibrary(
                    loaded.items,
                    exactAlbums = loaded.exactAlbums,
                    sourceName = source
                )

                if (!silent) {
                    val message = when {
                        loaded.items.isEmpty() -> "Aucun média compatible trouvé."
                        exactMode && loaded.exactAlbums ->
                            "${loaded.items.size} médias importés avec les noms d'albums exacts."
                        exactMode && loaded.missingExactAlbumFolders > 0 ->
                            "${loaded.items.size} médias importés. ${loaded.missingExactAlbumFolders} dossier(s) sans métadonnées d'album exactes."
                        else -> "${loaded.items.size} médias importés depuis le dossier local."
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }


}
