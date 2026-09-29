package fr.wokgui.phototv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

data class PhotoItem(val uri: Uri, val title: String, val album: String, val takenAt: Long = 0L)

data class OverlayStyle(
    var sizeSp: Int,
    var xPercent: Int,
    var yPercent: Int,
    var opacity: Int = 100,
    var font: String = "Sans",
    var color: Int = Color.WHITE,
    var alignment: Int = Gravity.START,
    var visible: Boolean = true
)

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQ_FOLDER = 42
        private const val REQ_PHOTOS = 43
    }

    private val photos = mutableListOf<PhotoItem>()
    private val selectedAlbums = linkedSetOf<String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentIndex = 0
    private var currentPage = 0
    private var paused = false
    private var loadingStyleControls = false

    private val overlayStyles = linkedMapOf(
        "Titre de la photo" to OverlayStyle(34, 5, 70, font = "Sans"),
        "Nom de l'album" to OverlayStyle(20, 5, 80, font = "Sans"),
        "Date" to OverlayStyle(14, 5, 88, font = "Sans", color = Color.rgb(205, 214, 228)),
        "Heure" to OverlayStyle(14, 32, 88, font = "Sans", color = Color.rgb(205, 214, 228), visible = false),
        "Température" to OverlayStyle(14, 70, 88, font = "Sans", color = Color.rgb(205, 214, 228), visible = false)
    )

    private fun <T : View> view(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setupTabs()
        setupPickers()
        setupSpinners()
        setupSettings()
        setupEditor()
        setupSlideshow()
        showPage(0)
        refreshMetadataText()
        applyAllOverlayStyles()
        view<Button>(R.id.tabPreview).requestFocus()
    }

    private fun setupTabs() {
        listOf(view<Button>(R.id.tabPreview), view<Button>(R.id.tabPhotos), view<Button>(R.id.tabEditor), view<Button>(R.id.tabSettings))
            .forEachIndexed { index, button -> button.setOnClickListener { showPage(index) } }
    }

    private fun showPage(index: Int) {
        currentPage = index
        val pages = listOf(view<View>(R.id.pagePreview), view<View>(R.id.pagePhotos), view<View>(R.id.pageEditor), view<View>(R.id.pageSettings))
        val tabs = listOf(view<Button>(R.id.tabPreview), view<Button>(R.id.tabPhotos), view<Button>(R.id.tabEditor), view<Button>(R.id.tabSettings))
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        if (index == 2) {
            refreshEditorPreview()
            applyAllOverlayStyles()
        }
    }

    private fun setupPickers() {
        val openExactFolder = View.OnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQ_FOLDER)
        }
        view<Button>(R.id.pickFolder).setOnClickListener(openExactFolder)
        view<Button>(R.id.pickFolder2).setOnClickListener(openExactFolder)
        view<Button>(R.id.pickPhotos).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQ_PHOTOS)
        }
    }

    private fun setupSpinners() {
        spinner(R.id.editorElement, overlayStyles.keys.toList())
        spinner(R.id.editorFont, listOf("Sans", "Sans Light", "Sans Condensed", "Serif", "Monospace"))
        spinner(R.id.editorColor, listOf("Blanc", "Bleu clair", "Jaune", "Rouge", "Vert", "Gris clair"))
        spinner(R.id.editorAlign, listOf("Gauche", "Centre", "Droite"))
        spinner(R.id.dateFormat, listOf("29/09/2026", "29 septembre 2026", "29 sept. 2026"))
        spinner(R.id.timeFormat, listOf("24 h — 15:42", "12 h — 3:42 PM"))
        spinner(R.id.tempUnit, listOf("°C", "°F"))
        spinner(R.id.transitionType, listOf(
            "Fondu enchaîné", "Fondu au noir", "Fondu au blanc",
            "Glissement gauche", "Glissement droite", "Glissement haut", "Glissement bas",
            "Zoom avant doux", "Zoom arrière doux", "Ken Burns", "Flou progressif",
            "Dissolution", "Balayage", "Cube 3D", "Rotation douce", "Aucune"
        ))
    }

    private fun spinner(id: Int, values: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        view<Spinner>(id).adapter = adapter
    }

    private fun setupSettings() {
        view<Button>(R.id.settingsNavSlideshow).setOnClickListener { view<SeekBar>(R.id.durationSeek).requestFocus() }
        view<Button>(R.id.settingsNavElements).setOnClickListener { view<CheckBox>(R.id.showTitle).requestFocus() }
        view<Button>(R.id.settingsNavStyle).setOnClickListener { showPage(2); view<Button>(R.id.metaTitleCard).requestFocus() }
        view<Button>(R.id.settingsNavTransitions).setOnClickListener { view<Spinner>(R.id.transitionType).requestFocus() }
        view<Button>(R.id.settingsNavTime).setOnClickListener { view<Spinner>(R.id.dateFormat).requestFocus() }
        view<Button>(R.id.settingsNavTemp).setOnClickListener { view<Spinner>(R.id.tempUnit).requestFocus() }
        view<Button>(R.id.settingsNavSource).setOnClickListener { showPage(1); view<Button>(R.id.pickFolder).requestFocus() }
        view<SeekBar>(R.id.durationSeek).setOnSeekBarChangeListener(simpleSeek {
            val seconds = it + 2
            view<TextView>(R.id.durationLabel).text = "Durée : $seconds s"
            refreshPlaybackSummary()
            if (slideshowVisible()) scheduleNext()
        })
        view<SeekBar>(R.id.transitionDuration).setOnSeekBarChangeListener(simpleSeek {
            view<TextView>(R.id.transitionDurationLabel).text =
                "Durée transition : ${String.format(Locale.FRANCE, "%.1f", it / 10f)} s"
        })
        listOf(R.id.showTitle, R.id.showAlbum, R.id.showDate, R.id.showTime, R.id.showTemp, R.id.fixedImage, R.id.shuffle, R.id.loop)
            .forEach { id ->
                view<CheckBox>(id).setOnCheckedChangeListener { _, _ ->
                    refreshMetadataText()
                    applyAllOverlayStyles()
                    refreshPlaybackSummary()
                    if (slideshowVisible()) scheduleNext()
                }
            }
        listOf(R.id.dateFormat, R.id.timeFormat, R.id.tempUnit, R.id.transitionType).forEach { id ->
            view<Spinner>(id).onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, itemId: Long) {
                    refreshMetadataText()
                    refreshPlaybackSummary()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
    }

    private fun setupEditor() {
        listOf(
            R.id.metaTitleCard to 0,
            R.id.metaAlbumCard to 1,
            R.id.metaDateCard to 2,
            R.id.metaTimeCard to 3,
            R.id.metaTempCard to 4
        ).forEach { (id, position) ->
            view<Button>(id).setOnClickListener {
                view<Spinner>(R.id.editorElement).setSelection(position)
                loadSelectedStyleIntoControls()
            }
        }
        view<Spinner>(R.id.editorElement).onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, itemId: Long) = loadSelectedStyleIntoControls()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        view<Spinner>(R.id.editorFont).onItemSelectedListener = editorSpinnerListener { style, position ->
            style.font = listOf("Sans", "Sans Light", "Sans Condensed", "Serif", "Monospace")[position]
        }
        view<Spinner>(R.id.editorColor).onItemSelectedListener = editorSpinnerListener { style, position ->
            style.color = listOf(Color.WHITE, Color.rgb(173,196,255), Color.rgb(255,224,112), Color.rgb(255,126,126), Color.rgb(137,230,173), Color.rgb(205,214,228))[position]
        }
        view<Spinner>(R.id.editorAlign).onItemSelectedListener = editorSpinnerListener { style, position ->
            style.alignment = listOf(Gravity.START, Gravity.CENTER_HORIZONTAL, Gravity.END)[position]
        }
        view<SeekBar>(R.id.editorSize).setOnSeekBarChangeListener(simpleSeek {
            if (!loadingStyleControls) {
                selectedStyle().sizeSp = it + 10
                view<TextView>(R.id.sizeLabel).text = "Taille : ${it + 10}"
                applySelectedStyle()
            }
        })
        view<SeekBar>(R.id.editorX).setOnSeekBarChangeListener(simpleSeek {
            if (!loadingStyleControls) {
                selectedStyle().xPercent = it
                view<TextView>(R.id.xLabel).text = "Position X : $it %"
                applySelectedStyle()
            }
        })
        view<SeekBar>(R.id.editorY).setOnSeekBarChangeListener(simpleSeek {
            if (!loadingStyleControls) {
                selectedStyle().yPercent = it
                view<TextView>(R.id.yLabel).text = "Position Y : $it %"
                applySelectedStyle()
            }
        })
        view<SeekBar>(R.id.editorOpacity).setOnSeekBarChangeListener(simpleSeek {
            if (!loadingStyleControls) {
                selectedStyle().opacity = it
                view<TextView>(R.id.opacityLabel).text = "Opacité : $it %"
                applySelectedStyle()
            }
        })
        view<CheckBox>(R.id.editorVisible).setOnCheckedChangeListener { _, checked ->
            if (!loadingStyleControls) {
                selectedStyle().visible = checked
                applySelectedStyle()
            }
        }
    }

    private fun editorSpinnerListener(change: (OverlayStyle, Int) -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, itemId: Long) {
                if (!loadingStyleControls) {
                    change(selectedStyle(), position)
                    applySelectedStyle()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    private fun selectedKey() = view<Spinner>(R.id.editorElement).selectedItem?.toString() ?: "Titre de la photo"
    private fun selectedStyle() = overlayStyles[selectedKey()] ?: overlayStyles.getValue("Titre de la photo")

    private fun loadSelectedStyleIntoControls() {
        val style = selectedStyle()
        loadingStyleControls = true
        view<SeekBar>(R.id.editorSize).progress = (style.sizeSp - 10).coerceIn(0,54)
        view<SeekBar>(R.id.editorX).progress = style.xPercent
        view<SeekBar>(R.id.editorY).progress = style.yPercent
        view<SeekBar>(R.id.editorOpacity).progress = style.opacity
        view<TextView>(R.id.sizeLabel).text = "Taille : ${style.sizeSp}"
        view<TextView>(R.id.xLabel).text = "Position X : ${style.xPercent} %"
        view<TextView>(R.id.yLabel).text = "Position Y : ${style.yPercent} %"
        view<TextView>(R.id.opacityLabel).text = "Opacité : ${style.opacity} %"
        view<Spinner>(R.id.editorFont).setSelection(fontIndex(style.font))
        view<Spinner>(R.id.editorColor).setSelection(colorIndex(style.color))
        view<Spinner>(R.id.editorAlign).setSelection(if (style.alignment == Gravity.CENTER_HORIZONTAL) 1 else if (style.alignment == Gravity.END) 2 else 0)
        view<CheckBox>(R.id.editorVisible).isChecked = style.visible
        loadingStyleControls = false
        applySelectedStyle()
    }

    private fun fontIndex(name: String) = when(name) { "Sans Light"->1; "Sans Condensed"->2; "Serif"->3; "Monospace"->4; else->0 }
    private fun colorIndex(color: Int) = when(color) {
        Color.rgb(173,196,255)->1; Color.rgb(255,224,112)->2; Color.rgb(255,126,126)->3
        Color.rgb(137,230,173)->4; Color.rgb(205,214,228)->5; else->0
    }

    private fun setupSlideshow() {
        view<Button>(R.id.startSlideshow).setOnClickListener {
            if (activePhotos().isEmpty()) {
                showPage(1)
                view<TextView>(R.id.sourceStatus).text = "Choisissez d'abord des photos ou un dossier."
                view<Button>(R.id.pickPhotos).requestFocus()
            } else startSlideshow()
        }
    }

    private fun startSlideshow() {
        view<View>(R.id.mainShell).visibility = View.GONE
        view<View>(R.id.slideshowLayer).visibility = View.VISIBLE
        currentIndex = currentIndex.coerceIn(0, max(0, activePhotos().size - 1))
        paused = false
        showCurrentPhoto(true)
        scheduleNext()
    }

    private fun stopSlideshow() {
        handler.removeCallbacksAndMessages(null)
        view<View>(R.id.slideshowLayer).visibility = View.GONE
        view<View>(R.id.mainShell).visibility = View.VISIBLE
        view<Button>(R.id.startSlideshow).requestFocus()
    }

    private fun slideshowVisible() = view<View>(R.id.slideshowLayer).visibility == View.VISIBLE

    private fun togglePause() {
        paused = !paused
        view<TextView>(R.id.slideHelp).text = if (paused) "En pause  •  OK reprendre  •  ← → photo  •  Retour" else "← → photo  •  OK pause  •  Retour"
        scheduleNext()
    }

    private fun scheduleNext() {
        handler.removeCallbacksAndMessages(null)
        if (!slideshowVisible() || paused || view<CheckBox>(R.id.fixedImage).isChecked) return
        val delay = (view<SeekBar>(R.id.durationSeek).progress + 2L) * 1000L
        handler.postDelayed({ moveNext(false) }, delay)
    }

    private fun moveNext(manual: Boolean) {
        val list = activePhotos()
        if (list.isEmpty()) return
        if (view<CheckBox>(R.id.shuffle).isChecked) currentIndex = if (list.size == 1) 0 else Random.nextInt(list.size)
        else if (currentIndex + 1 >= list.size) {
            if (view<CheckBox>(R.id.loop).isChecked) currentIndex = 0 else { stopSlideshow(); return }
        } else currentIndex++
        showCurrentPhoto(false)
        if (manual) paused = false
        scheduleNext()
    }

    private fun movePrevious() {
        val list = activePhotos()
        if (list.isEmpty()) return
        currentIndex = if (currentIndex <= 0) list.lastIndex else currentIndex - 1
        showCurrentPhoto(false)
        scheduleNext()
    }

    private fun activePhotos(): List<PhotoItem> = if (selectedAlbums.isEmpty()) emptyList() else photos.filter { selectedAlbums.contains(it.album) }

    private fun showCurrentPhoto(first: Boolean) {
        val list = activePhotos()
        if (list.isEmpty()) return
        currentIndex = currentIndex.coerceIn(0, list.lastIndex)
        val item = list[currentIndex]
        animateImage(view(R.id.slideImage), item.uri, first)
        setMetadataForItem(item)
        applyAllOverlayStyles()
    }

    private fun animateImage(image: ImageView, uri: Uri, first: Boolean) {
        image.animate().cancel()
        image.alpha=1f; image.translationX=0f; image.translationY=0f; image.scaleX=1f; image.scaleY=1f; image.rotation=0f; image.rotationY=0f
        image.setImageURI(uri)
        if (first) return
        val name = view<Spinner>(R.id.transitionType).selectedItem?.toString() ?: "Fondu enchaîné"
        val duration = (view<SeekBar>(R.id.transitionDuration).progress * 100L).coerceAtLeast(80L)
        when(name) {
            "Aucune" -> Unit
            "Glissement gauche" -> { image.translationX=image.width*.18f; image.animate().translationX(0f).setDuration(duration).start() }
            "Glissement droite" -> { image.translationX=-image.width*.18f; image.animate().translationX(0f).setDuration(duration).start() }
            "Glissement haut" -> { image.translationY=image.height*.16f; image.animate().translationY(0f).setDuration(duration).start() }
            "Glissement bas" -> { image.translationY=-image.height*.16f; image.animate().translationY(0f).setDuration(duration).start() }
            "Zoom avant doux","Ken Burns" -> { image.scaleX=.92f; image.scaleY=.92f; image.alpha=.2f; image.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(duration).start() }
            "Zoom arrière doux" -> { image.scaleX=1.10f; image.scaleY=1.10f; image.alpha=.3f; image.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(duration).start() }
            "Cube 3D" -> { image.rotationY=18f; image.alpha=.25f; image.animate().rotationY(0f).alpha(1f).setDuration(duration).start() }
            "Rotation douce" -> { image.rotation=3f; image.alpha=.3f; image.animate().rotation(0f).alpha(1f).setDuration(duration).start() }
            else -> { image.alpha=0f; image.animate().alpha(1f).setDuration(duration).start() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        when(requestCode) {
            REQ_FOLDER -> data.data?.let { treeUri ->
                try { contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                val root = DocumentFile.fromTreeUri(this, treeUri) ?: return
                view<TextView>(R.id.sourceStatus).text = "Import Google Takeout en cours…"
                Thread {
                    val loaded = mutableListOf<PhotoItem>()
                    scanFolder(root, loaded)
                    runOnUiThread {
                        photos.clear()
                        photos.addAll(loaded.distinctBy { it.uri.toString() })
                        rebuildAlbums()
                        refreshPreviewFromLibrary()
                    }
                }.start()
            }
            REQ_PHOTOS -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                photos.clear()
                uris.distinct().forEach { uri ->
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    val name = displayName(uri)
                    photos += PhotoItem(uri, stripExtension(name), "Album indisponible — utilisez Google Takeout pour le nom exact", 0L)
                }
                rebuildAlbums()
                refreshPreviewFromLibrary()
            }
        }
    }

    private fun scanFolder(dir: DocumentFile, out: MutableList<PhotoItem>) {
        val children = try { dir.listFiles().toList() } catch (_: Exception) { emptyList() }
        val images = children.filter { it.isFile && it.type?.startsWith("image/") == true }
        val jsons = children.filter { it.isFile && it.name?.endsWith(".json", true) == true }
        val albumName = exactAlbumName(dir, jsons)
        for (image in images) {
            val imageName = image.name ?: continue
            val sidecar = jsons.firstOrNull { json ->
                val n = json.name?.removeSuffix(".json") ?: ""
                n == imageName || n.startsWith(imageName)
            }
            var takenAt = image.lastModified()
            if (sidecar != null) {
                try {
                    val text = contentResolver.openInputStream(sidecar.uri)!!.use { BufferedReader(InputStreamReader(it)).readText() }
                    val stamp = JSONObject(text).optJSONObject("photoTakenTime")?.optString("timestamp")?.toLongOrNull()
                    if (stamp != null) takenAt = stamp * 1000L
                } catch (_: Exception) {}
            }
            out += PhotoItem(image.uri, stripExtension(imageName), albumName, takenAt)
        }
        children.filter { it.isDirectory }.forEach { scanFolder(it, out) }
    }

    private fun exactAlbumName(dir: DocumentFile, jsons: List<DocumentFile>): String {
        for (jsonFile in jsons) {
            try {
                val text = contentResolver.openInputStream(jsonFile.uri)!!.use {
                    BufferedReader(InputStreamReader(it)).readText()
                }
                val root = JSONObject(text)
                val albumData = root.optJSONObject("albumData")
                val exact = albumData?.optString("title")?.trim().orEmpty()
                if (exact.isNotBlank()) return exact
            } catch (_: Exception) {}
        }
        return dir.name ?: "Album"
    }

    private fun rebuildAlbums() {
        selectedAlbums.clear()
        selectedAlbums.addAll(photos.map { it.album }.distinct())
        val row = view<LinearLayout>(R.id.albumRow)
        row.removeAllViews()

        val albumNames = selectedAlbums.toList()
        albumNames.forEachIndexed { index, albumName ->
            val albumPhotos = photos.filter { it.album == albumName }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setBackgroundResource(R.drawable.tv_tab)
                isSelected = true
                contentDescription = albumName
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(albumPhotos.firstOrNull()?.uri)
                background = getDrawable(R.drawable.tv_focus_card)
            }
            val title = TextView(this).apply {
                text = albumName
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 1
                setPadding(dp(6), dp(6), dp(6), 0)
            }
            val count = TextView(this).apply {
                text = "${albumPhotos.size} photo(s)"
                setTextColor(Color.rgb(166, 177, 194))
                textSize = 11f
                setPadding(dp(6), 0, dp(6), dp(4))
            }
            card.addView(thumb, LinearLayout.LayoutParams(dp(168), dp(92)))
            card.addView(title, LinearLayout.LayoutParams(dp(168), dp(28)))
            card.addView(count, LinearLayout.LayoutParams(dp(168), dp(24)))

            fun refreshCard() {
                val selected = selectedAlbums.contains(albumName)
                card.isSelected = selected
                card.alpha = if (selected) 1f else .48f
            }
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) rebuildPhotoRow(albumName)
            }
            card.setOnClickListener {
                if (selectedAlbums.contains(albumName)) selectedAlbums.remove(albumName) else selectedAlbums.add(albumName)
                refreshCard()
                rebuildPhotoRow(albumName)
                view<TextView>(R.id.albumsStatus).text =
                    "${selectedAlbums.size} album(s) sélectionné(s) • ${activePhotos().size} photo(s)"
            }
            refreshCard()
            row.addView(card, LinearLayout.LayoutParams(dp(184), dp(154)).apply { rightMargin = dp(10) })
            if (index == 0) rebuildPhotoRow(albumName)
        }

        view<TextView>(R.id.albumsStatus).text =
            if (photos.isEmpty()) "Aucun album chargé"
            else "${selectedAlbums.size} album(s) • ${photos.size} photo(s)"
        view<TextView>(R.id.sourceStatus).text =
            if (photos.isEmpty()) "Aucune photothèque sélectionnée"
            else "${photos.size} photo(s) • ${selectedAlbums.size} album(s)"
    }

    private fun rebuildPhotoRow(albumName: String) {
        val row = view<LinearLayout>(R.id.photoRow)
        row.removeAllViews()
        photos.filter { it.album == albumName }.take(40).forEach { item ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(item.uri)
                isFocusable = true
                isClickable = true
                contentDescription = item.title
                setBackgroundResource(R.drawable.tv_focus_card)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setOnClickListener {
                    view<ImageView>(R.id.previewImage).setImageURI(item.uri)
                    view<ImageView>(R.id.editorImage).setImageURI(item.uri)
                    view<TextView>(R.id.previewTitle).text = item.title
                    view<TextView>(R.id.previewAlbum).text = item.album
                    showPage(0)
                }
            }
            row.addView(image, LinearLayout.LayoutParams(dp(190), dp(112)).apply { rightMargin = dp(10) })
        }
    }

    private fun refreshPreviewFromLibrary() {
        val item = photos.firstOrNull() ?: run { view<TextView>(R.id.sourceStatus).text="Aucune photo trouvée."; return }
        view<ImageView>(R.id.previewImage).setImageURI(item.uri)
        view<ImageView>(R.id.editorImage).setImageURI(item.uri)
        view<TextView>(R.id.previewTitle).text=item.title
        view<TextView>(R.id.previewAlbum).text=item.album
        refreshMetadataText()
        showPage(0)
    }

    private fun refreshEditorPreview() {
        photos.firstOrNull()?.let {
            view<ImageView>(R.id.editorImage).setImageURI(it.uri)
            view<TextView>(R.id.editorTitle).text=it.title
            view<TextView>(R.id.editorAlbum).text=it.album
        }
        refreshMetadataText()
    }

    private fun setMetadataForItem(item: PhotoItem) {
        val at = if (item.takenAt > 0L) item.takenAt else System.currentTimeMillis()
        view<TextView>(R.id.slideTitle).text=item.title
        view<TextView>(R.id.slideAlbum).text=item.album
        view<TextView>(R.id.slideDate).text=formatDate(at)
        view<TextView>(R.id.slideTime).text=formatTime(at)
        view<TextView>(R.id.slideTemp).text=temperaturePlaceholder()
        view<TextView>(R.id.editorTitle).text=item.title
        view<TextView>(R.id.editorAlbum).text=item.album
        view<TextView>(R.id.editorDate).text=formatDate(at)
        view<TextView>(R.id.editorTime).text=formatTime(at)
        view<TextView>(R.id.editorTemp).text=temperaturePlaceholder()
    }

    private fun refreshMetadataText() {
        val at = photos.firstOrNull()?.takenAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        val parts = mutableListOf<String>()
        if (view<CheckBox>(R.id.showDate).isChecked) parts += formatDate(at)
        if (view<CheckBox>(R.id.showTime).isChecked) parts += formatTime(at)
        if (view<CheckBox>(R.id.showTemp).isChecked) parts += temperaturePlaceholder()
        view<TextView>(R.id.previewDate).text=parts.joinToString(" • ")
        view<TextView>(R.id.editorDate).text=formatDate(at)
        view<TextView>(R.id.editorTime).text=formatTime(at)
        view<TextView>(R.id.editorTemp).text=temperaturePlaceholder()
        applyAllOverlayStyles()
    }

    private fun formatDate(time: Long): String {
        val pattern = when(view<Spinner>(R.id.dateFormat).selectedItemPosition) { 1->"dd MMMM yyyy"; 2->"dd MMM yyyy"; else->"dd/MM/yyyy" }
        return SimpleDateFormat(pattern,Locale.FRANCE).format(Date(time))
    }
    private fun formatTime(time: Long): String =
        SimpleDateFormat(if (view<Spinner>(R.id.timeFormat).selectedItemPosition==1) "h:mm a" else "HH:mm", Locale.getDefault()).format(Date(time))
    private fun temperaturePlaceholder() = if (view<Spinner>(R.id.tempUnit).selectedItemPosition==1) "— °F" else "— °C"

    private fun refreshPlaybackSummary() {
        val duration=view<SeekBar>(R.id.durationSeek).progress+2
        val mode=if(view<CheckBox>(R.id.fixedImage).isChecked) "Image fixe" else "$duration s"
        val order=if(view<CheckBox>(R.id.shuffle).isChecked) "Aléatoire" else "Dans l'ordre"
        val transition=view<Spinner>(R.id.transitionType).selectedItem?.toString()?:"Fondu enchaîné"
        view<TextView>(R.id.previewPlayback).text="$order • $mode • $transition"
    }

    private fun applySelectedStyle() {
        val key=selectedKey(); val style=selectedStyle()
        applyOverlayStyle(editorViewFor(key),style,globalVisibleFor(key))
        applyOverlayStyle(slideViewFor(key),style,globalVisibleFor(key))
    }
    private fun applyAllOverlayStyles() {
        overlayStyles.forEach { (key,style) ->
            applyOverlayStyle(editorViewFor(key),style,globalVisibleFor(key))
            applyOverlayStyle(slideViewFor(key),style,globalVisibleFor(key))
        }
    }
    private fun globalVisibleFor(key:String)=when(key) {
        "Titre de la photo"->view<CheckBox>(R.id.showTitle).isChecked
        "Nom de l'album"->view<CheckBox>(R.id.showAlbum).isChecked
        "Date"->view<CheckBox>(R.id.showDate).isChecked
        "Heure"->view<CheckBox>(R.id.showTime).isChecked
        "Température"->view<CheckBox>(R.id.showTemp).isChecked
        else->true
    }
    private fun editorViewFor(key:String):TextView=when(key) {
        "Nom de l'album"->view(R.id.editorAlbum); "Date"->view(R.id.editorDate); "Heure"->view(R.id.editorTime); "Température"->view(R.id.editorTemp); else->view(R.id.editorTitle)
    }
    private fun slideViewFor(key:String):TextView=when(key) {
        "Nom de l'album"->view(R.id.slideAlbum); "Date"->view(R.id.slideDate); "Heure"->view(R.id.slideTime); "Température"->view(R.id.slideTemp); else->view(R.id.slideTitle)
    }

    private fun applyOverlayStyle(text:TextView, style:OverlayStyle, globalVisible:Boolean) {
        text.visibility=if(style.visible&&globalVisible) View.VISIBLE else View.GONE
        text.alpha=style.opacity/100f
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP,style.sizeSp.toFloat())
        text.setTextColor(style.color)
        val family=when(style.font) { "Sans Light"->"sans-serif-light"; "Sans Condensed"->"sans-serif-condensed"; "Serif"->"serif"; "Monospace"->"monospace"; else->"sans-serif" }
        text.typeface=Typeface.create(family,if(text.id==R.id.editorTitle||text.id==R.id.slideTitle) Typeface.BOLD else Typeface.NORMAL)
        text.gravity=style.alignment
        text.textAlignment=when(style.alignment) { Gravity.CENTER_HORIZONTAL->View.TEXT_ALIGNMENT_CENTER; Gravity.END->View.TEXT_ALIGNMENT_TEXT_END; else->View.TEXT_ALIGNMENT_TEXT_START }
        text.post {
            val parent=text.parent as? View ?: return@post
            text.x=parent.width*style.xPercent/100f
            text.y=parent.height*style.yPercent/100f
        }
    }

    private fun displayName(uri:Uri):String {
        var result="Photo"
        try {
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                if(cursor.moveToFirst()) {
                    val index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if(index>=0) result=cursor.getString(index)?:result
                }
            }
        } catch (_:Exception) {}
        return result
    }
    private fun stripExtension(name:String):String { val i=name.lastIndexOf('.'); return if(i>0) name.substring(0,i) else name }
    private fun simpleSeek(block:(Int)->Unit)=object:SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean)=block(progress)
        override fun onStartTrackingTouch(seekBar:SeekBar?)=Unit
        override fun onStopTrackingTouch(seekBar:SeekBar?)=Unit
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()

    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean {
        if(slideshowVisible()) {
            when(keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT->{moveNext(true);return true}
                KeyEvent.KEYCODE_DPAD_LEFT->{movePrevious();return true}
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->{togglePause();return true}
                KeyEvent.KEYCODE_BACK->{stopSlideshow();return true}
            }
        } else if(keyCode==KeyEvent.KEYCODE_BACK&&currentPage!=0) {
            showPage(0); view<Button>(R.id.tabPreview).requestFocus(); return true
        }
        return super.onKeyDown(keyCode,event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}