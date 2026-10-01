package fr.wokgui.phototv

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.VideoView
import android.widget.Toast
import android.view.WindowManager
import android.text.InputType
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.KeyEvent
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
    val sourceId: String = "",
    val sourceLabel: String = ""
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
        private const val REQ_EXPORT_DIAGNOSTICS = 47
    }

    private lateinit var ui: PhotoTvView
    private lateinit var videoView: VideoView
    private val networkRefreshHandler = Handler(Looper.getMainLooper())
    private var networkRefreshMinutes = 15
    private var currentNetworkSourceName = "Réseau"
    private var pendingDiagnosticText: String? = null
    private var settingsUnlockedSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val automationMode = intent.getBooleanExtra("phototv_test_mode", false)

        videoView = VideoView(this).apply {
            visibility = View.GONE
            isFocusable = false
            isFocusableInTouchMode = false
        }

        ui = PhotoTvView(
            context = this,
            onExactSource = { openExactSource() },
            onFolderSource = { openLocalFolder() },
            onPickPhotos = { openPhotoPicker() },
            onNetworkSource = { requestNetworkSource() },
            onPrepareNetworkSource = { kind, url, user -> requestNetworkCredentials(kind, url, user) },
            onSettingsPin = { manageSettingsPin() },
            canOpenSettings = { !SettingsPinStore.hasPin(this) || settingsUnlockedSession },
            onUnlockSettings = { requestSettingsUnlock() },
            onWeatherLocation = { requestWeatherLocation() },
            onExportSettings = { exportSettings() },
            onImportSettings = { importSettings() },
            onAlbumSearch = { requestAlbumSearch() },
            onVideoPlayback = { uri, sound -> handleVideoPlayback(uri, sound) },
            onVideoPause = { pause -> handleVideoPause(pause) },
            supportsVideoPlayback = true,
            automationMode = automationMode
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

        getSharedPreferences("photo_tv_network_settings", MODE_PRIVATE).let { prefs ->
            val cacheMb = prefs.getInt("cache_mb", 512)
            val ttlHours = prefs.getInt("ttl_hours", 24)
            networkRefreshMinutes = prefs.getInt("refresh_minutes", 15).coerceAtLeast(5)
            NetworkLibrary.configureCache(cacheMb, ttlHours)
        }
        if (automationMode) {
            val testCount = intent.getIntExtra("phototv_test_library_count", 0)
            if (testCount > 0) ui.installAutomationLibraryForTest(testCount)
            ui.setAutomationPage(intent.getIntExtra("phototv_test_page", 0))
            if (intent.getBooleanExtra("phototv_test_slideshow", false) && testCount > 0) {
                ui.startAutomationSlideshowForTest()
            }
        } else {
            restoreSavedSource()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::ui.isInitialized) {
            val tvKey = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_BACK -> true
                else -> false
            }

            if (tvKey) {
                return when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        val handled = ui.onKeyDown(event.keyCode, event)
                        if (handled) true else super.dispatchKeyEvent(event)
                    }
                    KeyEvent.ACTION_UP -> {
                        ui.onKeyUp(event.keyCode, event)
                        true
                    }
                    else -> true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::ui.isInitialized) ui.onMemoryPressure(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::ui.isInitialized) ui.onMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    fun automationStateForTest(): String =
        if (::ui.isInitialized) ui.automationStateDescription() else "PhotoTV uninitialized"

    fun installAutomationLibraryForTest(count: Int) {
        if (::ui.isInitialized) ui.installAutomationLibraryForTest(count)
    }

    fun startAutomationSlideshowForTest() {
        if (::ui.isInitialized) ui.startAutomationSlideshowForTest()
    }

    fun advanceAutomationForTest() {
        if (::ui.isInitialized) ui.advanceAutomationForTest()
    }

    fun importSettingsForTest(raw: String): Boolean =
        ::ui.isInitialized && ui.importSettingsForTest(raw)

    fun accessibilityDescriptionForTest(): String =
        if (::ui.isInitialized) ui.accessibilityDescriptionForTest() else ""

    fun automationLibrarySizeForTest(): Int =
        if (::ui.isInitialized) ui.automationLibrarySizeForTest() else 0

    fun automationActiveCountForTest(): Int =
        if (::ui.isInitialized) ui.automationActiveCountForTest() else 0

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

    private fun handleVideoPause(pause: Boolean) {
        if (videoView.visibility != View.VISIBLE) return
        runCatching {
            if (pause) videoView.pause()
            else if (!videoView.isPlaying) videoView.start()
        }
    }

    private fun requestSettingsUnlock() {
        if (!SettingsPinStore.hasPin(this)) {
            settingsUnlockedSession = true
            ui.openSettingsAfterUnlock()
            return
        }
        val input = EditText(this).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Réglages verrouillés")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Déverrouiller") { _, _ ->
                val pin = input.text?.toString().orEmpty()
                if (SettingsPinStore.verify(this, pin)) {
                    settingsUnlockedSession = true
                    ui.openSettingsAfterUnlock()
                } else {
                    Toast.makeText(this, "PIN incorrect", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun manageSettingsPin() {
        if (!SettingsPinStore.hasPin(this)) {
            promptNewSettingsPin()
            return
        }
        val current = EditText(this).apply {
            hint = "PIN actuel"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Protection des réglages")
            .setView(current)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Continuer") { _, _ ->
                if (!SettingsPinStore.verify(this, current.text?.toString().orEmpty())) {
                    Toast.makeText(this, "PIN incorrect", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Protection des réglages")
                        .setItems(arrayOf("Changer le PIN", "Désactiver le PIN")) { _, which ->
                            if (which == 0) {
                                promptNewSettingsPin()
                            } else {
                                SettingsPinStore.clear(this)
                                settingsUnlockedSession = true
                                Toast.makeText(this, "PIN désactivé", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            }
            .show()
    }

    private fun promptNewSettingsPin() {
        val pin1 = EditText(this).apply {
            hint = "Nouveau PIN (4 à 8 chiffres)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val pin2 = EditText(this).apply {
            hint = "Confirmer le PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(pin1)
            addView(pin2)
        }
        AlertDialog.Builder(this)
            .setTitle("Définir le PIN des réglages")
            .setView(box)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                val first = pin1.text?.toString().orEmpty()
                val second = pin2.text?.toString().orEmpty()
                when {
                    !first.matches(Regex("\\d{4,8}")) ->
                        Toast.makeText(this, "Le PIN doit contenir 4 à 8 chiffres", Toast.LENGTH_LONG).show()
                    first != second ->
                        Toast.makeText(this, "Les deux PIN ne correspondent pas", Toast.LENGTH_LONG).show()
                    else -> {
                        SettingsPinStore.setPin(this, first)
                        settingsUnlockedSession = true
                        Toast.makeText(this, "PIN activé", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
    private fun requestNetworkSource() {
        val saved = NetworkSourceStore.list(this)
        val labels = buildList {
            saved.forEach { add("↻ " + it.label) }
            add("＋ Ajouter WebDAV")
            add("＋ Ajouter SMB / NAS")
            add("Réglages cache et rescan")
            add("Vider le cache réseau")
            add("Exporter diagnostic")
            add("Gérer les sources locales")
            if (saved.isNotEmpty()) add("Supprimer une source enregistrée")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Sources réseau")
            .setItems(labels) { _, which ->
                when {
                    which < saved.size -> {
                        val source = saved[which]
                        requestNetworkCredentials(
                            kind = source.kind,
                            initialUrl = source.baseUrl,
                            initialUser = source.username
                        )
                    }
                    which == saved.size -> requestNetworkCredentials(NetworkLibrary.Kind.WEBDAV)
                    which == saved.size + 1 -> requestNetworkCredentials(NetworkLibrary.Kind.SMB)
                    which == saved.size + 2 -> requestNetworkCacheSettings()
                    which == saved.size + 3 -> {
                        NetworkLibrary.clearDiskCache(this)
                        Toast.makeText(this, "Cache réseau vidé", Toast.LENGTH_SHORT).show()
                    }
                    which == saved.size + 4 -> exportDiagnostics()
                    which == saved.size + 5 -> manageLocalSources()
                    else -> deleteSavedNetworkSource()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun manageLocalSources() {
        val labels = SourceStore.describe(this)
        if (labels.isEmpty()) {
            Toast.makeText(this, "Aucune source locale enregistrée", Toast.LENGTH_SHORT).show()
            return
        }
        val sources = SourceStore.loadAll(this)
        AlertDialog.Builder(this)
            .setTitle("Sources locales")
            .setItems(labels.toTypedArray()) { _, which ->
                val label = labels.getOrNull(which) ?: return@setItems
                val source = sources.getOrNull(which) ?: return@setItems
                val activeLabel = when (source) {
                    is PhotoSourceSpec.Tree -> sourceLabelForTree(source.uri, source.exactMode)
                    is PhotoSourceSpec.Picked -> "Sélection de photos"
                }
                AlertDialog.Builder(this)
                    .setTitle("Supprimer cette source ?")
                    .setMessage(label)
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Supprimer") { _, _ ->
                        SourceStore.removeAt(this, which)
                        ui.removeSourceByLabel(activeLabel)
                        Toast.makeText(this, "Source supprimée", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun requestNetworkCacheSettings() {
        val prefs = getSharedPreferences("photo_tv_network_settings", MODE_PRIVATE)
        val cacheMb = intArrayOf(128, 256, 512, 1024, 2048)
        val ttlHours = intArrayOf(6, 12, 24, 48, 168)
        val refreshMinutes = intArrayOf(5, 15, 30, 60)
        var cacheIndex = cacheMb.indexOf(prefs.getInt("cache_mb", 512)).coerceAtLeast(0)
        var ttlIndex = ttlHours.indexOf(prefs.getInt("ttl_hours", 24)).coerceAtLeast(0)
        var refreshIndex = refreshMinutes.indexOf(prefs.getInt("refresh_minutes", 15)).coerceAtLeast(0)

        val labels = arrayOf("Taille du cache", "Durée de conservation", "Rescan automatique")
        fun openChoice(which: Int) {
            val values = when (which) {
                0 -> cacheMb.map { value -> value.toString() + " Mo" }.toTypedArray()
                1 -> ttlHours.map { value -> if (value < 24) value.toString() + " h" else (value / 24).toString() + " j" }.toTypedArray()
                else -> refreshMinutes.map { value -> value.toString() + " min" }.toTypedArray()
            }
            val checked = when (which) { 0 -> cacheIndex; 1 -> ttlIndex; else -> refreshIndex }
            AlertDialog.Builder(this)
                .setTitle(labels[which])
                .setSingleChoiceItems(values, checked) { dialog, index ->
                    when (which) {
                        0 -> cacheIndex = index
                        1 -> ttlIndex = index
                        else -> refreshIndex = index
                    }
                    dialog.dismiss()
                    prefs.edit()
                        .putInt("cache_mb", cacheMb[cacheIndex])
                        .putInt("ttl_hours", ttlHours[ttlIndex])
                        .putInt("refresh_minutes", refreshMinutes[refreshIndex])
                        .apply()
                    networkRefreshMinutes = refreshMinutes[refreshIndex]
                    NetworkLibrary.configureCache(cacheMb[cacheIndex], ttlHours[ttlIndex])
                    scheduleNetworkRefresh()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle("Cache et synchronisation réseau")
            .setItems(arrayOf(
                "Cache : " + cacheMb[cacheIndex] + " Mo",
                "Conservation : " + ttlHours[ttlIndex] + " h",
                "Rescan : " + refreshMinutes[refreshIndex] + " min"
            )) { _, which -> openChoice(which) }
            .setNegativeButton("Fermer", null)
            .show()
    }
    private fun deleteSavedNetworkSource() {
        val saved = NetworkSourceStore.list(this)
        if (saved.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Supprimer une source")
            .setItems(saved.map { it.label }.toTypedArray()) { _, which ->
                saved.getOrNull(which)?.let { source ->
                    NetworkSourceStore.delete(this, source.id)
                    val activeLabel = NetworkLibrary.removeSource(
                        source.kind,
                        source.baseUrl,
                        source.username
                    ) ?: source.label
                    ui.removeSourceByLabel(activeLabel)
                    Toast.makeText(this, "Source supprimée", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun requestNetworkCredentials(
        kind: NetworkLibrary.Kind,
        initialUrl: String = "",
        initialUser: String = ""
    ) {
        val url = EditText(this).apply {
            hint = if (kind == NetworkLibrary.Kind.WEBDAV) {
                "https://nas.exemple/photos/"
            } else {
                "smb://192.168.1.20/photos/"
            }
            setText(initialUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val user = EditText(this).apply {
            hint = "Utilisateur (facultatif)"
            setText(initialUser)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val password = EditText(this).apply {
            hint = "Mot de passe (facultatif)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(url)
            addView(user)
            addView(password)
        }

        AlertDialog.Builder(this)
            .setTitle(if (kind == NetworkLibrary.Kind.WEBDAV) "Connexion WebDAV" else "Connexion SMB / NAS")
            .setView(box)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Connecter") { _, _ ->
                val address = url.text?.toString().orEmpty().trim()
                if (address.isBlank()) {
                    Toast.makeText(this, "Adresse réseau manquante", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ui.showLoading("Connexion au réseau…")
                Thread {
                    val result = runCatching {
                        NetworkLibrary.load(
                            kind = kind,
                            baseUrl = address,
                            username = user.text?.toString().orEmpty(),
                            password = password.text?.toString().orEmpty()
                        )
                    }
                    runOnUiThread {
                        result.onSuccess { items ->
                            if (items.isEmpty()) {
                                ui.showLoading("")
                                Toast.makeText(this, "Aucune photo compatible trouvée", Toast.LENGTH_LONG).show()
                            } else {
                                NetworkSourceStore.upsert(
                                    context = this,
                                    kind = kind,
                                    baseUrl = address,
                                    username = user.text?.toString().orEmpty()
                                )
                                currentNetworkSourceName = items.firstOrNull()?.sourceLabel
                                    ?.takeIf { it.isNotBlank() }
                                    ?: if (kind == NetworkLibrary.Kind.WEBDAV) "WebDAV" else "SMB / NAS"
                                ui.upsertSourceLibrary(
                                    items = items,
                                    exactAlbums = false,
                                    fallbackSourceName = currentNetworkSourceName
                                )
                                scheduleNetworkRefresh()
                                Toast.makeText(this, "${items.size} médias réseau chargés", Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure { error ->
                            ui.showLoading("")
                            Toast.makeText(
                                this,
                                "Connexion impossible : " + (error.message ?: "erreur réseau"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun scheduleNetworkRefresh() {
        networkRefreshHandler.removeCallbacksAndMessages(null)
        if (!NetworkLibrary.isConfigured()) return
        networkRefreshHandler.postDelayed({
            Thread {
                val result = runCatching { NetworkLibrary.reload() }
                runOnUiThread {
                    result.getOrNull()?.let { items ->
                        ui.replaceNetworkLibraries(items)
                    }
                    scheduleNetworkRefresh()
                }
            }.start()
        }, networkRefreshMinutes.coerceAtLeast(5) * 60_000L)
    }

    private fun exportDiagnostics() {
        pendingDiagnosticText = ui.diagnosticReport()
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "text/plain"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "Photo-TV-diagnostic.txt")
            },
            REQ_EXPORT_DIAGNOSTICS
        )
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

    override fun onDestroy() {
        networkRefreshHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android framework, retained for broad TV compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        when (requestCode) {
            REQ_EXPORT_DIAGNOSTICS -> data.data?.let { uri ->
                val text = pendingDiagnosticText ?: ui.diagnosticReport()
                val ok = runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                        writer.write(text)
                    } ?: error("Flux de sortie indisponible")
                }.isSuccess
                pendingDiagnosticText = null
                Toast.makeText(
                    this,
                    if (ok) "Diagnostic exporté." else "Échec de l’export du diagnostic.",
                    Toast.LENGTH_LONG
                ).show()
            }

            REQ_EXACT_FOLDER -> data.data?.let { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                SourceStore.addTree(this, uri, exactMode = true)
                importTree(uri, silent = false, exactMode = true, merge = true)
            }

            REQ_LOCAL_FOLDER -> data.data?.let { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                SourceStore.addTree(this, uri, exactMode = false)
                importTree(uri, silent = false, exactMode = false, merge = true)
            }

            REQ_EXPORT_SETTINGS -> data.data?.let { uri ->
                val ok = runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                        it.write(ui.exportSettingsJson())
                    } ?: error("Flux de sortie indisponible")
                }.isSuccess
                Toast.makeText(
                    this,
                    if (ok) "Réglages exportés." else "Échec de l'export des réglages.",
                    Toast.LENGTH_LONG
                ).show()
            }

            REQ_IMPORT_SETTINGS -> data.data?.let { uri ->
                val ok = runCatching {
                    val raw = contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                    raw.isNotBlank() && ui.importSettingsJson(raw)
                }.getOrDefault(false)
                Toast.makeText(
                    this,
                    if (ok) "Réglages importés." else "Fichier de réglages invalide.",
                    Toast.LENGTH_LONG
                ).show()
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
                val merged = SourceStore.addPicked(this, distinct)
                importPicked(merged, silent = false, merge = true)
            }
        }
    }

    private fun sourceLabelForTree(uri: Uri, exactMode: Boolean): String {
        val decoded = Uri.decode(uri.lastPathSegment.orEmpty())
        val name = decoded.substringAfterLast(':').substringAfterLast('/').ifBlank {
            if (exactMode) "Takeout" else "Dossier"
        }
        return (if (exactMode) "Takeout" else "Dossier") + " • " + name
    }

    private fun restoreSavedSource() {
        val sources = SourceStore.loadAll(this)
        if (sources.isEmpty()) return
        Thread {
            val loaded = mutableListOf<Triple<List<PhotoItem>, Boolean, String>>()
            sources.forEach { source ->
                when (source) {
                    is PhotoSourceSpec.Tree -> {
                        val result = TakeoutLibrary.load(this, source.uri, exactMode = source.exactMode)
                        loaded += Triple(result.items, result.exactAlbums, sourceLabelForTree(source.uri, source.exactMode))
                    }
                    is PhotoSourceSpec.Picked -> {
                        loaded += Triple(PickedLibrary.load(this, source.uris), false, "Sélection de photos")
                    }
                }
            }
            runOnUiThread {
                loaded.forEachIndexed { index, value ->
                    if (index == 0) {
                        ui.setLibrary(value.first, exactAlbums = value.second, sourceName = value.third)
                    } else {
                        ui.upsertSourceLibrary(value.first, exactAlbums = value.second, fallbackSourceName = value.third)
                    }
                }
            }
        }.start()
    }
    private fun importPicked(uris: List<Uri>, silent: Boolean, merge: Boolean = false) {
        if (!silent) ui.showLoading("Analyse des médias sélectionnés…")
        Thread {
            val items = PickedLibrary.load(this, uris)
            runOnUiThread {
                if (merge) {
                    ui.upsertSourceLibrary(items, exactAlbums = false, fallbackSourceName = "Sélection de photos")
                } else {
                    ui.setLibrary(items, exactAlbums = false, sourceName = "Sélection de photos")
                }
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

    private fun importTree(uri: Uri, silent: Boolean, exactMode: Boolean, merge: Boolean = false) {
        if (!silent) {
            ui.showLoading(
                if (exactMode) "Analyse de Google Photos / Takeout…"
                else "Analyse du dossier local…"
            )
        }
        Thread {
            val loaded = TakeoutLibrary.load(
                this,
                uri,
                exactMode = exactMode,
                onProgress = if (silent) null else { folders, media ->
                    ui.post {
                        ui.showLoading(
                            if (exactMode) {
                                "Analyse Takeout… $folders dossiers • $media médias"
                            } else {
                                "Analyse du dossier… $folders dossiers • $media médias"
                            }
                        )
                    }
                }
            )
            runOnUiThread {
                val source = sourceLabelForTree(uri, exactMode)
                if (merge) {
                    ui.upsertSourceLibrary(
                        loaded.items,
                        exactAlbums = loaded.exactAlbums,
                        fallbackSourceName = source
                    )
                } else {
                    ui.setLibrary(
                        loaded.items,
                        exactAlbums = loaded.exactAlbums,
                        sourceName = source
                    )
                }

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
