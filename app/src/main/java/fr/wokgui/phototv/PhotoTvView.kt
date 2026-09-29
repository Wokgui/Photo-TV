package fr.wokgui.phototv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.os.Build
import android.media.MediaMetadataRetriever
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import java.text.SimpleDateFormat
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class PhotoTvView(
    context: Context,
    private val onExactSource: () -> Unit,
    private val onFolderSource: () -> Unit = {},
    private val onPickPhotos: () -> Unit,
    private val onWeatherLocation: () -> Unit = {},
    private val onExportSettings: () -> Unit = {},
    private val onImportSettings: () -> Unit = {},
    private val onAlbumSearch: () -> Unit = {},
    private val onVideoPlayback: (Uri?, Boolean) -> Unit = { _, _ -> },
    private val supportsVideoPlayback: Boolean = false
) : View(context) {

    private data class Style(
        var size: Float,
        var x: Float,
        var y: Float,
        var font: Int = 0,
        var color: Int = Color.WHITE,
        var align: Int = 0,
        var shadow: Boolean = true,
        var visible: Boolean = true
    )

    private data class AlbumRule(
        var enabled: Boolean = false,
        var daysMode: Int = 0,
        var startHour: Int = 0,
        var endHour: Int = 24,
        var durationSeconds: Int = 0,
        var transitionIndex: Int = -1,
        var showMetadata: Boolean = true
    )

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val clockHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val bitmapCache = LinkedHashMap<String, Bitmap>()
    private val gifCache = LinkedHashMap<String, Movie>()
    private val gifLoading = linkedSetOf<String>()
    private var softSource: Bitmap? = null
    private var softBitmap: Bitmap? = null

    private val demoUrl =
        "https://commons.wikimedia.org/wiki/Special:Redirect/file/Oslo%20-%20Op%C3%A9ra%20-%20Ext%C3%A9rieur%2001.JPG?width=1800"
    private var demoBitmap: Bitmap? = null

    private var library: List<PhotoItem> = emptyList()
    private var exactAlbums = false
    private var page = 0
    private var navFocus = false
    private var currentPhoto = 0
    private var slideshow = false
    private var paused = false
    private var previousPhoto = 0
    private var transitionProgress = 1f
    private var transitionAnimator: ValueAnimator? = null

    private var sourceFocus = 0
    private var albumFocus = 0
    private var photoFocus = 0
    private var photosRow = 0
    private val selectedAlbums = linkedSetOf<String>()
    private val savedSelectedAlbums = linkedSetOf<String>()

    private var editorElement = 0
    private var editorControl = 0
    private var editorColumn = 0

    private var settingsCategory = 0
    private var settingsControl = 0
    private var settingsColumn = 0

    private var durationSeconds = 10
    private var fixedImage = false
    private var loop = true
    private var randomOrder = false
    private var transitionIndex = 0
    private var transitionSeconds = 2f
    private var kenBurns = true
    private var zoomLevel = 0
    private var showDate = true
    private var showTime = true
    private var showTemp = true
    private var tempCelsius = true
    private var dateFormatIndex = 0
    private var time24h = true
    private var showSeconds = false
    private var temperatureC = 17f
    private var feelsLikeC = 17f
    private var forecastMinC = 12f
    private var forecastMaxC = 19f
    private var weatherSummary = "—"
    private var weatherLocation = ""
    private val forecastLines = mutableListOf<String>()
    private var imageMode = 0
    private var gridSnap = true
    private var oledProtection = true
    private var overlaysAutoHide = false
    private var autoStartMinutes = 5
    private var startDirectly = false
    private var favoritesOnly = false
    private val favorites = linkedSetOf<String>()
    private val hiddenAlbums = linkedSetOf<String>()
    private val excludedUris = linkedSetOf<String>()
    private val sessionExcludedUris = linkedSetOf<String>()
    private val history = mutableListOf<Int>()
    private val shuffleBag = mutableListOf<Int>()
    private var quickMenuVisible = false
    private var quickMenuIndex = 0
    private var infoPanelVisible = false
    private var slideStartedAt = System.currentTimeMillis()
    private var inactivityToken = 0L
    private var editorMoveMode = false
    private var longActionLatched = false
    private var layoutPreset = 0
    private var albumSearch = ""
    private var albumSort = 0
    private var videoSound = false
    private var ruleAlbumIndex = 0
    private var advancedRulesOpen = false
    private val albumRules = linkedMapOf<String, AlbumRule>()

    private val transitions = listOf(
        "Fondu", "Glissement", "Zoom", "Ken Burns", "Dissolution", "Cube 3D",
        "Fondu au noir", "Fondu au blanc", "Glissement droite", "Glissement haut",
        "Glissement bas", "Zoom arrière", "Flou progressif", "Balayage",
        "Rotation douce", "Aucune"
    )

    private val elementNames = listOf(
        "Titre de la photo", "Nom de l'album", "Date", "Heure", "Température",
        "Description", "Lieu", "Appareil photo", "Dimensions", "Orientation"
    )

    private val styles = mutableListOf(
        Style(31f, 7f, 76f, font = 0, color = Color.WHITE),
        Style(19f, 7f, 84f, font = 0, color = Color.WHITE),
        Style(11f, 73f, 10f, font = 0, color = Color.WHITE, align = 2),
        Style(26f, 80f, 15f, font = 0, color = Color.WHITE, align = 2),
        Style(18f, 80f, 5f, font = 0, color = Color.WHITE, align = 2),
        Style(14f, 7f, 90f, font = 0, color = Color.WHITE, visible = false),
        Style(13f, 7f, 94f, font = 0, color = Color.WHITE, visible = false),
        Style(12f, 72f, 90f, font = 0, color = Color.WHITE, align = 2, visible = false),
        Style(12f, 72f, 94f, font = 0, color = Color.WHITE, align = 2, visible = false),
        Style(12f, 72f, 98f, font = 0, color = Color.WHITE, align = 2, visible = false)
    )

    private val mockAlbums = listOf(
        "Norvège 2026" to 142,
        "Copenhague" to 318,
        "Châteaux" to 87,
        "Montagne" to 203,
        "Animaux" to 96,
        "Vacances" to 154
    )

    private val prefs = context.getSharedPreferences("photo_tv_ui", Context.MODE_PRIVATE)
    private var loadingText: String? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        loadPrefs()
        requestFocus()
        loadDemo()
        loadWeather()
        scheduleInactivity()
        scheduleClock()
    }

    private fun loadPrefs() {
        durationSeconds = prefs.getInt("duration", 10)
        fixedImage = prefs.getBoolean("fixed", false)
        loop = prefs.getBoolean("loop", true)
        randomOrder = prefs.getBoolean("random", false)
        transitionIndex = prefs.getInt("transition", 0).coerceIn(0, transitions.lastIndex)
        transitionSeconds = prefs.getFloat("transition_seconds", 2f)
        kenBurns = prefs.getBoolean("ken", true)
        zoomLevel = prefs.getInt("zoom", 0)
        showDate = prefs.getBoolean("date", true)
        showTime = prefs.getBoolean("time", true)
        showTemp = prefs.getBoolean("temp", true)
        tempCelsius = prefs.getBoolean("celsius", true)
        dateFormatIndex = prefs.getInt("date_format", 0).coerceIn(0, 2)
        time24h = prefs.getBoolean("time_24h", true)
        showSeconds = prefs.getBoolean("show_seconds", false)
        weatherLocation = prefs.getString("weather_location", "") ?: ""
        imageMode = prefs.getInt("image_mode", 0).coerceIn(0, 3)
        gridSnap = prefs.getBoolean("grid_snap", true)
        oledProtection = prefs.getBoolean("oled", true)
        overlaysAutoHide = prefs.getBoolean("overlay_hide", false)
        autoStartMinutes = prefs.getInt("auto_start", 5).coerceIn(0, 60)
        startDirectly = prefs.getBoolean("start_direct", false)
        layoutPreset = prefs.getInt("layout_preset", 0).coerceIn(0, 3)
        albumSearch = prefs.getString("album_search", "") ?: ""
        albumSort = prefs.getInt("album_sort", 0).coerceIn(0, 1)
        videoSound = prefs.getBoolean("video_sound", false)
        restoreAlbumRules(prefs.getString("album_rules", null))
        favoritesOnly = prefs.getBoolean("favorites_only", false)
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        hiddenAlbums.addAll(prefs.getStringSet("hidden_albums", emptySet()) ?: emptySet())
        excludedUris.addAll(prefs.getStringSet("excluded_uris", emptySet()) ?: emptySet())
        savedSelectedAlbums.addAll(prefs.getStringSet("selected_albums", emptySet()) ?: emptySet())
        currentPhoto = prefs.getInt("resume_index", 0)
        styles.forEachIndexed { i, s ->
            s.size = prefs.getFloat("s${i}_size", s.size)
            s.x = prefs.getFloat("s${i}_x", s.x)
            s.y = prefs.getFloat("s${i}_y", s.y)
            s.font = prefs.getInt("s${i}_font", s.font)
            s.color = prefs.getInt("s${i}_color", s.color)
            s.align = prefs.getInt("s${i}_align", s.align)
            s.shadow = prefs.getBoolean("s${i}_shadow", s.shadow)
            s.visible = prefs.getBoolean("s${i}_visible", s.visible)
        }
    }

    private fun savePrefs() {
        prefs.edit().apply {
            putInt("duration", durationSeconds)
            putBoolean("fixed", fixedImage)
            putBoolean("loop", loop)
            putBoolean("random", randomOrder)
            putInt("transition", transitionIndex)
            putFloat("transition_seconds", transitionSeconds)
            putBoolean("ken", kenBurns)
            putInt("zoom", zoomLevel)
            putBoolean("date", showDate)
            putBoolean("time", showTime)
            putBoolean("temp", showTemp)
            putBoolean("celsius", tempCelsius)
            putInt("date_format", dateFormatIndex)
            putBoolean("time_24h", time24h)
            putBoolean("show_seconds", showSeconds)
            putString("weather_location", weatherLocation)
            putInt("image_mode", imageMode)
            putBoolean("grid_snap", gridSnap)
            putBoolean("oled", oledProtection)
            putBoolean("overlay_hide", overlaysAutoHide)
            putInt("auto_start", autoStartMinutes)
            putBoolean("start_direct", startDirectly)
            putInt("layout_preset", layoutPreset)
            putString("album_search", albumSearch)
            putInt("album_sort", albumSort)
            putBoolean("video_sound", videoSound)
            putString("album_rules", albumRulesJson().toString())
            putBoolean("favorites_only", favoritesOnly)
            putStringSet("favorites", HashSet(favorites))
            putStringSet("hidden_albums", HashSet(hiddenAlbums))
            putStringSet("excluded_uris", HashSet(excludedUris))
            putStringSet("selected_albums", HashSet(selectedAlbums))
            putInt("resume_index", currentPhoto)
            styles.forEachIndexed { i, s ->
                putFloat("s${i}_size", s.size)
                putFloat("s${i}_x", s.x)
                putFloat("s${i}_y", s.y)
                putInt("s${i}_font", s.font)
                putInt("s${i}_color", s.color)
                putInt("s${i}_align", s.align)
                putBoolean("s${i}_shadow", s.shadow)
                putBoolean("s${i}_visible", s.visible)
            }
        }.apply()
    }

    private fun scheduleClock() {
        clockHandler.removeCallbacksAndMessages(null)
        val delay = if (showSeconds) 1000L else 30_000L
        clockHandler.postDelayed({
            invalidate()
            scheduleClock()
        }, delay)
    }

    fun setWeatherLocation(value: String) {
        weatherLocation = value.trim()
        savePrefs()
        loadWeather()
        invalidate()
    }

    private fun loadWeather() {
        executor.execute {
            runCatching {
                val encoded = if (weatherLocation.isBlank()) "" else java.net.URLEncoder.encode(weatherLocation, "UTF-8")
                val url = if (encoded.isBlank()) {
                    "https://wttr.in/?format=j1"
                } else {
                    "https://wttr.in/$encoded?format=j1"
                }
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "PhotoTV/0.4")
                }
                val json = connection.getInputStream().bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val current = root.optJSONArray("current_condition")?.optJSONObject(0)
                val c = current?.optString("temp_C")?.toFloatOrNull()
                if (c != null) temperatureC = c
                feelsLikeC = current?.optString("FeelsLikeC")?.toFloatOrNull() ?: temperatureC
                weatherSummary = current
                    ?.optJSONArray("weatherDesc")
                    ?.optJSONObject(0)
                    ?.optString("value")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "—" }

                val weather = root.optJSONArray("weather")
                val today = weather?.optJSONObject(0)
                forecastMinC = today?.optString("mintempC")?.toFloatOrNull() ?: forecastMinC
                forecastMaxC = today?.optString("maxtempC")?.toFloatOrNull() ?: forecastMaxC

                forecastLines.clear()
                if (weather != null) {
                    for (i in 0 until min(3, weather.length())) {
                        val day = weather.optJSONObject(i) ?: continue
                        val rawDate = day.optString("date")
                        val label = runCatching {
                            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(rawDate)
                            SimpleDateFormat("EEE dd/MM", Locale.FRANCE).format(parsed ?: Date())
                        }.getOrDefault(rawDate)
                        val minC = day.optString("mintempC").toFloatOrNull() ?: 0f
                        val maxC = day.optString("maxtempC").toFloatOrNull() ?: 0f
                        val minShown = if (tempCelsius) minC.toInt() else (minC * 9f / 5f + 32f).toInt()
                        val maxShown = if (tempCelsius) maxC.toInt() else (maxC * 9f / 5f + 32f).toInt()
                        forecastLines += "$label  $minShown° / $maxShown°"
                    }
                }
                postInvalidate()
            }
        }
    }

    private fun loadDemo() {
        val req = ImageRequest.Builder(context)
            .data(demoUrl)
            .size(Size.ORIGINAL)
            .target(
                onSuccess = { d: Drawable ->
                    demoBitmap = d.toBitmap()
                    invalidate()
                }
            )
            .build()
        context.imageLoader.enqueue(req)
    }

    fun exportSettingsJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("durationSeconds", durationSeconds)
        root.put("fixedImage", fixedImage)
        root.put("loop", loop)
        root.put("randomOrder", randomOrder)
        root.put("transitionIndex", transitionIndex)
        root.put("transitionSeconds", transitionSeconds.toDouble())
        root.put("kenBurns", kenBurns)
        root.put("zoomLevel", zoomLevel)
        root.put("showDate", showDate)
        root.put("showTime", showTime)
        root.put("showTemp", showTemp)
        root.put("tempCelsius", tempCelsius)
        root.put("dateFormatIndex", dateFormatIndex)
        root.put("time24h", time24h)
        root.put("showSeconds", showSeconds)
        root.put("weatherLocation", weatherLocation)
        root.put("imageMode", imageMode)
        root.put("gridSnap", gridSnap)
        root.put("oledProtection", oledProtection)
        root.put("overlaysAutoHide", overlaysAutoHide)
        root.put("autoStartMinutes", autoStartMinutes)
        root.put("startDirectly", startDirectly)
        root.put("favoritesOnly", favoritesOnly)
        root.put("layoutPreset", layoutPreset)
        root.put("albumSearch", albumSearch)
        root.put("albumSort", albumSort)
        root.put("videoSound", videoSound)
        root.put("albumRules", albumRulesJson())

        fun strings(values: Collection<String>): JSONArray =
            JSONArray().apply { values.forEach { put(it) } }

        root.put("favorites", strings(favorites))
        root.put("hiddenAlbums", strings(hiddenAlbums))
        root.put("excludedUris", strings(excludedUris))
        root.put("selectedAlbums", strings(selectedAlbums))

        val styleArray = JSONArray()
        styles.forEach { st ->
            styleArray.put(
                JSONObject()
                    .put("size", st.size.toDouble())
                    .put("x", st.x.toDouble())
                    .put("y", st.y.toDouble())
                    .put("font", st.font)
                    .put("color", st.color)
                    .put("align", st.align)
                    .put("shadow", st.shadow)
                    .put("visible", st.visible)
            )
        }
        root.put("styles", styleArray)
        return root.toString(2)
    }

    fun importSettingsJson(raw: String): Boolean {
        return runCatching {
            val root = JSONObject(raw)
            durationSeconds = root.optInt("durationSeconds", durationSeconds).coerceIn(2, 120)
            fixedImage = root.optBoolean("fixedImage", fixedImage)
            loop = root.optBoolean("loop", loop)
            randomOrder = root.optBoolean("randomOrder", randomOrder)
            transitionIndex = root.optInt("transitionIndex", transitionIndex).coerceIn(0, transitions.lastIndex)
            transitionSeconds = root.optDouble("transitionSeconds", transitionSeconds.toDouble()).toFloat().coerceIn(.2f, 4f)
            kenBurns = root.optBoolean("kenBurns", kenBurns)
            zoomLevel = root.optInt("zoomLevel", zoomLevel).coerceIn(0, 2)
            showDate = root.optBoolean("showDate", showDate)
            showTime = root.optBoolean("showTime", showTime)
            showTemp = root.optBoolean("showTemp", showTemp)
            tempCelsius = root.optBoolean("tempCelsius", tempCelsius)
            dateFormatIndex = root.optInt("dateFormatIndex", dateFormatIndex).coerceIn(0, 2)
            time24h = root.optBoolean("time24h", time24h)
            showSeconds = root.optBoolean("showSeconds", showSeconds)
            weatherLocation = root.optString("weatherLocation", weatherLocation)
            imageMode = root.optInt("imageMode", imageMode).coerceIn(0, 3)
            gridSnap = root.optBoolean("gridSnap", gridSnap)
            oledProtection = root.optBoolean("oledProtection", oledProtection)
            overlaysAutoHide = root.optBoolean("overlaysAutoHide", overlaysAutoHide)
            autoStartMinutes = root.optInt("autoStartMinutes", autoStartMinutes).coerceIn(0, 60)
            startDirectly = root.optBoolean("startDirectly", startDirectly)
            favoritesOnly = root.optBoolean("favoritesOnly", favoritesOnly)
            layoutPreset = root.optInt("layoutPreset", layoutPreset).coerceIn(0, 3)
            albumSearch = root.optString("albumSearch", albumSearch)
            albumSort = root.optInt("albumSort", albumSort).coerceIn(0, 1)
            videoSound = root.optBoolean("videoSound", videoSound)
            root.optJSONObject("albumRules")?.let {
                albumRules.clear()
                restoreAlbumRules(it.toString())
            }

            fun restoreSet(name: String, target: MutableSet<String>) {
                val arr = root.optJSONArray(name) ?: return
                target.clear()
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { target += it }
                }
            }
            restoreSet("favorites", favorites)
            restoreSet("hiddenAlbums", hiddenAlbums)
            restoreSet("excludedUris", excludedUris)
            restoreSet("selectedAlbums", selectedAlbums)

            root.optJSONArray("styles")?.let { arr ->
                for (i in 0 until min(arr.length(), styles.size)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val st = styles[i]
                    st.size = o.optDouble("size", st.size.toDouble()).toFloat().coerceIn(8f, 100f)
                    st.x = o.optDouble("x", st.x.toDouble()).toFloat().coerceIn(0f, 100f)
                    st.y = o.optDouble("y", st.y.toDouble()).toFloat().coerceIn(0f, 100f)
                    st.font = o.optInt("font", st.font).coerceIn(0, 3)
                    st.color = o.optInt("color", st.color)
                    st.align = o.optInt("align", st.align).coerceIn(0, 2)
                    st.shadow = o.optBoolean("shadow", st.shadow)
                    st.visible = o.optBoolean("visible", st.visible)
                }
            }

            savePrefs()
            loadWeather()
            scheduleInactivity()
            invalidate()
            true
        }.getOrDefault(false)
    }

    private fun albumRulesJson(): JSONObject {
        val root = JSONObject()
        albumRules.forEach { (name, rule) ->
            root.put(
                name,
                JSONObject()
                    .put("enabled", rule.enabled)
                    .put("daysMode", rule.daysMode)
                    .put("startHour", rule.startHour)
                    .put("endHour", rule.endHour)
                    .put("durationSeconds", rule.durationSeconds)
                    .put("transitionIndex", rule.transitionIndex)
                    .put("showMetadata", rule.showMetadata)
            )
        }
        return root
    }

    private fun restoreAlbumRules(raw: String?) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val root = JSONObject(raw)
            val names = root.keys()
            while (names.hasNext()) {
                val name = names.next()
                val o = root.optJSONObject(name) ?: continue
                albumRules[name] = AlbumRule(
                    enabled = o.optBoolean("enabled", false),
                    daysMode = o.optInt("daysMode", 0).coerceIn(0, 2),
                    startHour = o.optInt("startHour", 0).coerceIn(0, 23),
                    endHour = o.optInt("endHour", 24).coerceIn(1, 24),
                    durationSeconds = o.optInt("durationSeconds", 0).coerceIn(0, 120),
                    transitionIndex = o.optInt("transitionIndex", -1).coerceIn(-1, transitions.lastIndex),
                    showMetadata = o.optBoolean("showMetadata", true)
                )
            }
        }
    }

    private fun albumAllowed(album: String, now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val rule = albumRules[album] ?: return true
        if (!rule.enabled) return true

        val dow = now.get(java.util.Calendar.DAY_OF_WEEK)
        val weekend = dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
        if (rule.daysMode == 1 && weekend) return false
        if (rule.daysMode == 2 && !weekend) return false

        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val start = rule.startHour
        val end = rule.endHour
        return when {
            start == 0 && end == 24 -> true
            start < end -> hour in start until end
            else -> hour >= start || hour < end
        }
    }

    private fun ruleForItem(item: PhotoItem?): AlbumRule? {
        if (item == null) return null
        return item.albums
            .asSequence()
            .filter { selectedAlbums.contains(it) && !hiddenAlbums.contains(it) }
            .mapNotNull { albumRules[it] }
            .firstOrNull { it.enabled }
    }

    fun setAlbumSearch(value: String) {
        albumSearch = value.trim()
        albumFocus = 0
        photoFocus = 0
        savePrefs()
        invalidate()
    }

    fun showLoading(message: String) {
        loadingText = message
        invalidate()
    }

    fun setLibrary(items: List<PhotoItem>, exactAlbums: Boolean) {
        library = items
        this.exactAlbums = exactAlbums
        selectedAlbums.clear()
        val allAlbums = items.flatMap { it.albums }.distinct()
        if (savedSelectedAlbums.isNotEmpty()) {
            selectedAlbums.addAll(allAlbums.filter { savedSelectedAlbums.contains(it) })
        }
        if (selectedAlbums.isEmpty()) selectedAlbums.addAll(allAlbums)
        currentPhoto = prefs.getInt("resume_index", 0).coerceAtLeast(0)
        albumFocus = 0
        photoFocus = 0
        loadingText = null
        preload(items.take(48).map { it.uri })
        val count = activePhotos().size
        if (count > 0) currentPhoto = currentPhoto.coerceIn(0, count - 1)
        invalidate()
        scheduleInactivity()
        if (startDirectly && count > 0) postDelayed({ if (!slideshow) startSlideshow() }, 450)
    }

    fun startAsDream(items: List<PhotoItem>) {
        setLibrary(items, exactAlbums = true)
        if (activePhotos().isNotEmpty()) {
            postDelayed({ startSlideshow() }, 150)
        }
    }

    private fun preload(uris: List<Uri>) {
        uris.distinct().forEach { uri ->
            val key = uri.toString()
            if (bitmapCache.containsKey(key)) return@forEach
            executor.execute {
                val bmp = decodeThumb(uri)
                if (bmp != null) {
                    synchronized(bitmapCache) {
                        bitmapCache[key] = bmp
                        while (bitmapCache.size > 80) {
                            val first = bitmapCache.entries.firstOrNull() ?: break
                            first.value.recycle()
                            bitmapCache.remove(first.key)
                        }
                    }
                    postInvalidate()
                }
            }
        }
    }

    private fun ensureGif(uri: Uri) {
        val key = uri.toString()
        synchronized(gifCache) {
            if (gifCache.containsKey(key) || gifLoading.contains(key)) return
            gifLoading += key
        }
        executor.execute {
            val movie = runCatching {
                context.contentResolver.openInputStream(uri)?.use { Movie.decodeStream(it) }
            }.getOrNull()
            synchronized(gifCache) {
                gifLoading.remove(key)
                if (movie != null) gifCache[key] = movie
            }
            postInvalidate()
        }
    }

    private fun drawAnimatedGif(c: Canvas, item: PhotoItem, x: Float, y: Float, w: Float, h: Float): Boolean {
        val key = item.uri.toString()
        val movie = synchronized(gifCache) { gifCache[key] }
        if (movie == null) {
            ensureGif(item.uri)
            return false
        }
        val mw = movie.width().coerceAtLeast(1).toFloat()
        val mh = movie.height().coerceAtLeast(1).toFloat()
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        movie.setTime((android.os.SystemClock.uptimeMillis() % duration).toInt())

        val scale = when (imageMode) {
            1, 3 -> min(w / mw, h / mh)
            2 -> min(1f, min(w / mw, h / mh))
            else -> max(w / mw, h / mh)
        }

        if (imageMode == 3) {
            drawSoftBackground(c, currentBitmap(), x, y, w, h)
            fill(c, x, y, x + w, y + h, Color.argb(75, 0, 0, 0))
        } else {
            fill(c, x, y, x + w, y + h, Color.BLACK)
        }

        val dx = x + (w - mw * scale) / 2f
        val dy = y + (h - mh * scale) / 2f
        c.save()
        c.clipRect(x, y, x + w, y + h)
        c.translate(dx, dy)
        c.scale(scale, scale)
        movie.draw(c, 0f, 0f)
        c.restore()
        postInvalidateDelayed(50L)
        return true
    }

    private fun decodeThumb(uri: Uri): Bitmap? {
        return runCatching {
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("video/")) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    runCatching { r.release() }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 900) sample *= 2
                val opt = BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
            }
        }.getOrNull()
    }

    private fun activePhotos(): List<PhotoItem> {
        if (library.isEmpty()) return emptyList()
        return library.filter { item ->
            item.albums.any { selectedAlbums.contains(it) && !hiddenAlbums.contains(it) && albumAllowed(it) } &&
                !excludedUris.contains(item.uri.toString()) &&
                !sessionExcludedUris.contains(item.uri.toString()) &&
                (!favoritesOnly || favorites.contains(item.uri.toString()))
        }
    }

    private fun currentItem(): PhotoItem? {
        val items = activePhotos()
        if (items.isEmpty()) return null
        currentPhoto = currentPhoto.coerceIn(0, items.lastIndex)
        return items[currentPhoto]
    }

    private fun currentBitmap(): Bitmap? {
        val item = currentItem()
        return if (item == null) demoBitmap else {
            val key = item.uri.toString()
            synchronized(bitmapCache) { bitmapCache[key] }.also {
                if (it == null) preload(listOf(item.uri))
            } ?: demoBitmap
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val sx = width / 1280f
        val sy = height / 720f
        canvas.save()
        canvas.scale(sx, sy)

        if (slideshow) drawSlideshow(canvas)
        else {
            when (page) {
                0 -> drawPreview(canvas)
                1 -> drawPhotos(canvas)
                2 -> drawEditor(canvas)
                else -> drawSettings(canvas)
            }
            drawBottomNav(canvas)
        }

        loadingText?.let { drawLoading(canvas, it) }
        canvas.restore()
    }

    private fun drawLoading(c: Canvas, msg: String) {
        fill(c, 0f, 0f, 1280f, 720f, Color.argb(170, 0, 0, 0))
        round(c, 430f, 305f, 850f, 415f, 18f, Color.rgb(12, 24, 37))
        strokeRound(c, 430f, 305f, 850f, 415f, 18f, Color.rgb(48, 88, 126), 1.5f)
        text(c, "Photo TV", 640f, 340f, 23f, Color.WHITE, 1, 1)
        text(c, msg, 640f, 380f, 14f, Color.rgb(190, 203, 220), 0, 1)
    }

    private fun drawPreview(c: Canvas) {
        drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, currentBitmap())
        drawBottomGradient(c, 0f, 0f, 1280f, 720f)
        drawBrand(c, 60f, 20f, "DIAPORAMA")
        drawClock(c, 1218f, 34f)

        circle(c, 95f, 352f, 30f, Color.argb(190, 7, 15, 24))
        text(c, "‹", 95f, 363f, 40f, Color.WHITE, 0, 1)
        circle(c, 1183f, 352f, 30f, Color.argb(190, 7, 15, 24))
        text(c, "›", 1183f, 363f, 40f, Color.WHITE, 0, 1)

        val item = currentItem()
        val title = item?.title ?: "001. Opéra d'Oslo"
        val album = albumDisplay(item)
        text(c, title, 78f, 568f, 31f, Color.WHITE, 1)
        text(c, album, 78f, 607f, 19f, Color.WHITE, 0)

        val count = activePhotos().size
        val countText = if (count > 0) "${currentPhoto + 1} / $count" else "3 / 142"
        text(c, countText, 1215f, 607f, 15f, Color.WHITE, 0, 2)
    }

    private fun drawPhotos(c: Canvas) {
        drawAppBackground(c)
        drawBrand(c, 58f, 20f, "PHOTOS ET ALBUMS")

        val sourceY = 108f
        drawSourceCard(c, 47f, sourceY, 388f, 102f, "Google Photos", "Se connecter", 0, sourceFocus == 0 && !navFocus, true)
        drawSourceCard(c, 450f, sourceY, 362f, 102f, "Choisir un dossier", "Stockage local", 1, sourceFocus == 1 && !navFocus, false)
        drawSourceCard(c, 828f, sourceY, 410f, 102f, "Sélectionner des photos", "Choisir plusieurs fichiers", 2, sourceFocus == 2 && !navFocus, false)

        val albums = albumPairs()
        text(c, "Mes albums Google Photos", 45f, 250f, 19f, Color.WHITE, 1)
        text(c, "${albums.size} albums", 1235f, 250f, 13f, Color.rgb(186, 196, 210), 0, 2)

        val albumY = 270f
        val cardW = 185f
        val cardH = 198f
        val gap = 14f
        albums.take(6).forEachIndexed { i, pair ->
            val x = 47f + i * (cardW + gap)
            val focused = photosRow == 1 && albumFocus == i && !navFocus
            drawAlbumCard(c, x, albumY, cardW, cardH, pair.first, pair.second, i, focused)
        }

        text(c, "Photos de l'album sélectionné", 45f, 500f, 19f, Color.WHITE, 1)
        val thumbs = currentAlbumPhotos()
        val py = 522f
        val tw = 185f
        thumbs.take(6).forEachIndexed { i, item ->
            val x = 47f + i * (tw + gap)
            val focused = photosRow == 2 && photoFocus == i && !navFocus
            drawPhotoThumb(c, x, py, tw, 104f, item, focused)
        }

    }

    private fun albumPairs(): List<Pair<String, Int>> {
        val base = if (library.isEmpty()) {
            mockAlbums
        } else {
            val counts = linkedMapOf<String, Int>()
            library.forEach { item ->
                item.albums.forEach { album -> counts[album] = (counts[album] ?: 0) + 1 }
            }
            counts.entries.map { it.key to it.value }
        }

        val filtered = if (albumSearch.isBlank()) base else {
            base.filter { it.first.contains(albumSearch, ignoreCase = true) }
        }

        return when (albumSort) {
            1 -> filtered.sortedByDescending { it.second }
            else -> filtered.sortedBy { it.first.lowercase(Locale.FRANCE) }
        }
    }

    private fun currentAlbumName(): String {
        val pairs = albumPairs()
        if (pairs.isEmpty()) return ""
        return pairs[albumFocus.coerceIn(0, pairs.lastIndex)].first
    }

    private fun currentAlbumPhotos(): List<PhotoItem?> {
        if (library.isEmpty()) return List(6) { null }
        val name = currentAlbumName()
        return library.filter { it.albums.contains(name) }.map { it as PhotoItem? }
    }

    private fun albumDisplay(item: PhotoItem?): String {
        if (item == null) return "Norvège 2026"
        val preferred = item.albums.filter { selectedAlbums.contains(it) && !hiddenAlbums.contains(it) }
        val values = if (preferred.isNotEmpty()) preferred else item.albums.toList()
        return values.joinToString(" • ").ifBlank { "Album" }
    }

    private fun metadataValues(item: PhotoItem? = currentItem()): List<String> = listOf(
        item?.title ?: "001. Opéra d'Oslo",
        albumDisplay(item),
        itemDate(item),
        currentTime(),
        tempText(),
        item?.description?.ifBlank { "—" } ?: "—",
        item?.location?.ifBlank { "—" } ?: "—",
        item?.camera?.ifBlank { "—" } ?: "—",
        item?.dimensionsLabel?.ifBlank { "—" } ?: "—",
        item?.orientationLabel?.ifBlank { "—" } ?: "—"
    )

    private fun drawEditor(c: Canvas) {
        drawAppBackground(c)
        drawBrand(c, 50f, 20f, "ÉDITEUR")

        val leftX = 32f
        val leftW = 320f
        val startY = 120f
        val h = 78f
        val gap = 9f
        val values = metadataValues()
        val visibleCount = 5
        val first = (editorElement - 2).coerceIn(0, max(0, elementNames.size - visibleCount))
        for (slot in 0 until visibleCount) {
            val i = first + slot
            if (i !in elementNames.indices) break
            val y = startY + slot * (h + gap)
            drawMetaCard(
                c, leftX, y, leftW, h,
                elementNames[i], values[i],
                editorElement == i,
                editorColumn == 0 && !navFocus && editorElement == i
            )
        }
        if (elementNames.size > visibleCount) {
            text(c, "${first + 1}–${min(first + visibleCount, elementNames.size)} / ${elementNames.size}", leftX + leftW, 623f, 10f, Color.rgb(128, 149, 173), 0, 2)
        }

        val canvasX = 370f
        val canvasY = 64f
        val canvasW = 548f
        val canvasH = 574f
        round(c, canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, 5f, Color.rgb(10, 22, 32))
        drawBackgroundPhoto(c, canvasX, canvasY, canvasW, canvasH, currentBitmap())
        if (gridSnap) drawEditorGrid(c, canvasX, canvasY, canvasW, canvasH)
        drawBottomGradient(c, canvasX, canvasY, canvasW, canvasH)
        drawEditorOverlays(c, canvasX, canvasY, canvasW, canvasH)

        val panelX = 940f
        val panelY = 64f
        val panelW = 308f
        val panelH = 574f
        round(c, panelX, panelY, panelX + panelW, panelY + panelH, 12f, Color.rgb(17, 29, 43))
        strokeRound(c, panelX, panelY, panelX + panelW, panelY + panelH, 12f, Color.rgb(38, 55, 74), 1f)
        drawEditorPanel(c, panelX, panelY, panelW)
    }

    private fun drawEditorGrid(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 0.7f
        stroke.color = Color.argb(65, 150, 200, 255)
        for (i in 1 until 10) {
            val gx = x + w * i / 10f
            val gy = y + h * i / 10f
            c.drawLine(gx, y, gx, y + h, stroke)
            c.drawLine(x, gy, x + w, gy, stroke)
        }
        stroke.strokeWidth = 1.3f
        stroke.color = Color.argb(110, 90, 170, 255)
        c.drawLine(x + w / 2f, y, x + w / 2f, y + h, stroke)
        c.drawLine(x, y + h / 2f, x + w, y + h / 2f, stroke)
    }

    private fun drawEditorOverlays(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val item = currentItem()
        val vals = metadataValues(item)
        styles.forEachIndexed { i, s ->
            val global = when (i) {
                2 -> showDate
                3 -> showTime
                4 -> showTemp
                else -> true
            }
            if (!s.visible || !global) return@forEachIndexed

            val tx = x + w * s.x / 100f
            val ty = y + h * s.y / 100f
            applyStylePaint(s)
            p.textSize = s.size
            val align = when (s.align) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
            p.textAlign = align
            c.drawText(vals[i], tx, ty, p)

            if (i == editorElement) {
                val width = p.measureText(vals[i])
                val fm = p.fontMetrics
                val left = when (align) {
                    Paint.Align.CENTER -> tx - width / 2
                    Paint.Align.RIGHT -> tx - width
                    else -> tx
                }
                val top = ty + fm.ascent - 8f
                val right = left + width + 10f
                val bottom = ty + fm.descent + 8f
                strokeRound(c, left - 5f, top, right, bottom, 2f, Color.rgb(27, 130, 255), 2f)
                listOf(left - 5f to top, right to top, left - 5f to bottom, right to bottom).forEach { (hx, hy) ->
                    circle(c, hx, hy, 5f, Color.WHITE)
                    stroke.style = Paint.Style.STROKE
                    stroke.strokeWidth = 2f
                    stroke.color = Color.rgb(27, 130, 255)
                    c.drawCircle(hx, hy, 5f, stroke)
                }
            }
        }
        p.clearShadowLayer()
    }

    private fun drawEditorPanel(c: Canvas, x: Float, y: Float, w: Float) {
        val s = styles[editorElement]
        text(c, "Paramètres du titre", x + 16f, y + 28f, 15f, Color.WHITE, 1)
        text(c, "Texte", x + 16f, y + 60f, 11f, Color.rgb(180, 191, 207))
        controlBox(c, x + 16f, y + 67f, w - 32f, 35f, currentTextForElement(), editorControl == 0 && editorColumn == 2 && !navFocus)

        text(c, "Police", x + 16f, y + 125f, 11f, Color.rgb(180, 191, 207))
        controlBox(c, x + 108f, y + 111f, w - 124f, 35f, fontName(s.font), editorControl == 1 && editorColumn == 2 && !navFocus)

        drawSliderRow(c, "Taille", s.size, 12f, 80f, x + 16f, y + 167f, w - 32f, editorControl == 2 && editorColumn == 2 && !navFocus)
        text(c, "Couleur", x + 16f, y + 240f, 12f, Color.WHITE)
        round(c, x + 110f, y + 224f, x + 148f, y + 256f, 5f, s.color)
        if (editorControl == 3 && editorColumn == 2 && !navFocus) strokeRound(c, x + 106f, y + 220f, x + 152f, y + 260f, 7f, Color.rgb(29, 134, 255), 2f)

        drawSliderRow(c, "Position X", s.x, 0f, 100f, x + 16f, y + 276f, w - 32f, editorControl == 4 && editorColumn == 2 && !navFocus)
        drawSliderRow(c, "Position Y", s.y, 0f, 100f, x + 16f, y + 337f, w - 32f, editorControl == 5 && editorColumn == 2 && !navFocus)

        text(c, "Alignement", x + 16f, y + 420f, 12f, Color.WHITE)
        val segX = x + 108f
        val segY = y + 398f
        val segW = 126f
        round(c, segX, segY, segX + segW, segY + 36f, 7f, Color.rgb(31, 44, 59))
        for (i in 0..2) {
            if (s.align == i) round(c, segX + i * 42f, segY, segX + (i + 1) * 42f, segY + 36f, 7f, Color.rgb(18, 108, 195))
            drawAlignIcon(c, segX + i * 42f + 21f, segY + 18f, i)
        }
        if (editorControl == 6 && editorColumn == 2 && !navFocus) strokeRound(c, segX - 3f, segY - 3f, segX + segW + 3f, segY + 39f, 8f, Color.rgb(29, 134, 255), 2f)

        text(c, "Ombre", x + 16f, y + 478f, 12f, Color.WHITE)
        drawToggle(c, x + 205f, y + 464f, s.shadow, editorControl == 7 && editorColumn == 2 && !navFocus)
    }

    private fun currentTextForElement(): String =
        metadataValues().getOrElse(editorElement) { "—" }

    private fun drawSettings(c: Canvas) {
        drawAppBackground(c)
        drawBrand(c, 50f, 20f, "RÉGLAGES")

        val sideX = 32f
        val sideY = 108f
        val sideW = 320f
        val sideH = 475f
        round(c, sideX, sideY, sideX + sideW, sideY + sideH, 13f, Color.rgb(6, 18, 29))
        strokeRound(c, sideX, sideY, sideX + sideW, sideY + sideH, 13f, Color.rgb(22, 45, 69), 1f)

        val cats = listOf(
            "Diaporama", "Éléments affichés", "Style et position", "Transitions",
            "Heure et date", "Température", "Source des photos", "Avancés"
        )
        cats.forEachIndexed { i, name ->
            val yy = sideY + 8f + i * 55f
            val active = settingsCategory == i
            if (active) gradientRound(c, sideX + 8f, yy, sideX + sideW - 8f, yy + 50f, 11f, Color.rgb(12, 119, 255), Color.rgb(10, 91, 237))
            if (settingsColumn == 0 && settingsCategory == i && !navFocus) strokeRound(c, sideX + 5f, yy - 3f, sideX + sideW - 5f, yy + 53f, 12f, Color.rgb(136, 197, 255), 2f)
            drawSideIcon(c, sideX + 30f, yy + 25f, i)
            text(c, name, sideX + 58f, yy + 31f, 14f, Color.WHITE)
        }

        val panelX = 380f
        val panelY = 77f
        val panelW = 868f
        val panelH = 536f
        round(c, panelX, panelY, panelX + panelW, panelY + panelH, 13f, Color.rgb(17, 30, 44))
        strokeRound(c, panelX, panelY, panelX + panelW, panelY + panelH, 13f, Color.rgb(38, 56, 76), 1f)

        when (settingsCategory) {
            0 -> drawSettingsSlideshow(c, panelX, panelY)
            1 -> drawSettingsElements(c, panelX, panelY, panelW)
            2 -> drawSettingsStyle(c, panelX, panelY, panelW, panelH)
            3 -> drawSettingsTransitions(c, panelX, panelY)
            4 -> drawSettingsTime(c, panelX, panelY)
            5 -> drawSettingsTemp(c, panelX, panelY)
            6 -> drawSettingsSource(c, panelX, panelY, panelW)
            else -> if (advancedRulesOpen) drawSettingsRules(c, panelX, panelY, panelW) else drawSettingsAdvanced(c, panelX, panelY)
        }
    }

    private fun drawSettingsSlideshow(c: Canvas, x: Float, y: Float) {
        text(c, "Diaporama", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        settingsSlider(c, "Durée d'affichage par photo", durationSeconds.toFloat(), 2f, 120f, "$durationSeconds secondes", x, y + 40f, 0)
        settingsSegment(c, "Mode d'affichage", listOf("Automatique", "Image fixe"), if (fixedImage) 1 else 0, x, y + 86f, 1)
        settingsToggle(c, "Lecture en boucle", loop, x, y + 133f, 2)
        settingsChoice(c, "Ordre des photos", if (randomOrder) "Aléatoire" else "Dans l'ordre de l'album", x, y + 180f, 3)

        text(c, "Transition entre les photos", x + 22f, y + 252f, 13f, Color.WHITE)
        drawTransitionCards(c, x + 22f, y + 267f, settingsControl == 4 && settingsColumn == 1 && !navFocus)

        settingsSlider(c, "Durée de la transition", transitionSeconds, .2f, 4f, "${format1(transitionSeconds)} secondes", x, y + 390f, 5)
        settingsToggle(c, "Effet panoramique (Ken Burns)", kenBurns, x, y + 430f, 6)
        settingsSegment(c, "Style d'agrandissement", listOf("Léger", "Moyen", "Fort"), zoomLevel, x, y + 471f, 7)
    }

    private fun drawSettingsElements(c: Canvas, x: Float, y: Float, w: Float) {
        text(c, "Éléments affichés", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        val visibleCount = 6
        val first = (settingsControl - 2).coerceIn(0, max(0, elementNames.size - visibleCount))
        for (slot in 0 until visibleCount) {
            val i = first + slot
            if (i !in elementNames.indices) break
            val yy = y + 64f + slot * 70f
            round(c, x + 22f, yy, x + w - 22f, yy + 54f, 10f, Color.rgb(11, 25, 38))
            if (settingsColumn == 1 && settingsControl == i && !navFocus) {
                strokeRound(c, x + 19f, yy - 3f, x + w - 19f, yy + 57f, 11f, Color.rgb(31, 132, 255), 2f)
            }
            text(c, elementNames[i], x + 42f, yy + 33f, 14f, Color.WHITE, 1)
            drawToggle(c, x + w - 80f, yy + 13f, elementVisible(i), false)
        }
        text(c, "${first + 1}–${min(first + visibleCount, elementNames.size)} / ${elementNames.size}", x + w - 24f, y + 515f, 11f, Color.rgb(128, 149, 173), 0, 2)
    }

    private fun elementVisible(i: Int): Boolean = when (i) {
        2 -> showDate
        3 -> showTime
        4 -> showTemp
        else -> styles.getOrNull(i)?.visible ?: false
    }

    private fun drawSettingsStyle(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        text(c, "Style et position", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        text(c, "Chaque élément peut être déplacé, redimensionné et masqué indépendamment.", x + 22f, y + 67f, 13f, Color.rgb(184, 196, 212))
        val visibleCount = 5
        val first = (editorElement - 2).coerceIn(0, max(0, elementNames.size - visibleCount))
        for (slot in 0 until visibleCount) {
            val i = first + slot
            if (i !in elementNames.indices) break
            val yy = y + 92f + slot * 64f
            round(c, x + 22f, yy, x + 420f, yy + 50f, 10f, if (i == editorElement) Color.rgb(10, 101, 214) else Color.rgb(11, 25, 38))
            text(c, elementNames[i], x + 40f, yy + 31f, 14f, Color.WHITE)
            text(c, "${styles[i].size.toInt()} px  •  X ${styles[i].x.toInt()}  •  Y ${styles[i].y.toInt()}", x + 445f, yy + 31f, 13f, Color.rgb(177, 191, 209))
        }
        text(c, "OK ouvre l'Éditeur sur l'élément sélectionné.", x + 22f, y + h - 27f, 12f, Color.rgb(126, 151, 181))
    }

    private fun drawSettingsTransitions(c: Canvas, x: Float, y: Float) {
        text(c, "Transitions", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        val cols = 4
        transitions.forEachIndexed { i, name ->
            val row = i / cols
            val col = i % cols
            val xx = x + 22f + col * 211f
            val yy = y + 70f + row * 104f
            val active = transitionIndex == i
            round(c, xx, yy, xx + 194f, yy + 86f, 10f, Color.rgb(12, 25, 38))
            drawBitmapCenterCrop(c, currentBitmap(), xx + 5f, yy + 5f, 184f, 51f, 7f)
            if (active) strokeRound(c, xx - 2f, yy - 2f, xx + 196f, yy + 88f, 11f, Color.rgb(29, 145, 255), 2f)
            if (settingsColumn == 1 && settingsControl == i && !navFocus) strokeRound(c, xx - 5f, yy - 5f, xx + 199f, yy + 91f, 12f, Color.rgb(154, 211, 255), 2f)
            text(c, name, xx + 10f, yy + 75f, 11f, Color.WHITE)
        }
    }

    private fun drawSettingsTime(c: Canvas, x: Float, y: Float) {
        text(c, "Heure et date", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        settingsToggle(c, "Afficher la date", showDate, x, y + 68f, 0)
        settingsChoice(c, "Format de date", dateFormatLabel(), x, y + 124f, 1)
        settingsToggle(c, "Afficher l'heure", showTime, x, y + 180f, 2)
        settingsChoice(c, "Format de l'heure", if (time24h) "24 h" else "12 h", x, y + 236f, 3)
        settingsToggle(c, "Afficher les secondes", showSeconds, x, y + 292f, 4)
        text(c, "Aperçu", x + 22f, y + 377f, 13f, Color.rgb(177, 191, 209))
        text(c, mockDate(), x + 22f, y + 419f, 22f, Color.WHITE, 1)
        text(c, currentTime(), x + 22f, y + 468f, 34f, Color.WHITE, 1)
    }

    private fun drawSettingsTemp(c: Canvas, x: Float, y: Float) {
        text(c, "Température", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        settingsToggle(c, "Afficher la température", showTemp, x, y + 72f, 0)
        settingsSegment(c, "Unité", listOf("°C", "°F"), if (tempCelsius) 0 else 1, x, y + 132f, 1)
        settingsChoice(
            c,
            "Ville météo",
            if (weatherLocation.isBlank()) "Automatique" else weatherLocation,
            x,
            y + 192f,
            2
        )

        text(c, "Aperçu météo", x + 22f, y + 285f, 13f, Color.rgb(177, 191, 209))
        text(c, "☀  ${tempText()}", x + 22f, y + 335f, 31f, Color.WHITE, 1)
        text(c, weatherSummary.take(48), x + 22f, y + 370f, 14f, Color.WHITE)
        val feels = if (tempCelsius) "${feelsLikeC.toInt()} °C" else "${(feelsLikeC * 9f / 5f + 32f).toInt()} °F"
        val minT = if (tempCelsius) "${forecastMinC.toInt()}°" else "${(forecastMinC * 9f / 5f + 32f).toInt()}°"
        val maxT = if (tempCelsius) "${forecastMaxC.toInt()}°" else "${(forecastMaxC * 9f / 5f + 32f).toInt()}°"
        text(c, "Ressenti $feels • Aujourd'hui $minT / $maxT", x + 22f, y + 401f, 12f, Color.rgb(169, 184, 203))
        forecastLines.take(3).forEachIndexed { i, line ->
            text(c, line, x + 22f + i * 210f, y + 438f, 11f, Color.rgb(196, 210, 228))
        }
        text(c, "OK sur « Ville météo » pour choisir une ville.", x + 22f, y + 485f, 11f, Color.rgb(129, 153, 181))
    }

    private fun drawSettingsSource(c: Canvas, x: Float, y: Float, w: Float) {
        text(c, "Source des photos", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        drawSourceCard(c, x + 22f, y + 72f, 395f, 105f, "Google Photos", "Se connecter — mode exact", 0, settingsControl == 0 && settingsColumn == 1 && !navFocus, true)
        drawSourceCard(c, x + 438f, y + 72f, 395f, 105f, "Sélectionner des photos", "Album non garanti", 2, settingsControl == 1 && settingsColumn == 1 && !navFocus, false)

        val status = when {
            library.isEmpty() -> "Aucune photothèque connectée."
            exactAlbums -> "${library.size} médias • ${library.flatMap { it.albums }.distinct().size} albums exacts"
            else -> "${library.size} médias • noms d'albums non disponibles"
        }
        text(c, status, x + 22f, y + 215f, 14f, if (exactAlbums) Color.rgb(91, 213, 145) else Color.rgb(187, 198, 212))

        settingsChoice(
            c,
            "Tri des albums",
            if (albumSort == 0) "Alphabétique" else "Nombre de photos",
            x,
            y + 245f,
            2
        )
        settingsChoice(
            c,
            "Recherche d'album",
            if (albumSearch.isBlank()) "Aucune" else albumSearch,
            x,
            y + 305f,
            3
        )

        val allAlbums = library.flatMap { it.albums }.distinct()
        val allSelected = allAlbums.isNotEmpty() && selectedAlbums.containsAll(allAlbums)
        controlBox(
            c,
            x + 350f,
            y + 365f,
            380f,
            39f,
            if (allSelected) "Désélectionner tous les albums" else "Sélectionner tous les albums",
            settingsColumn == 1 && settingsControl == 4 && !navFocus
        )

        text(c, "Appui long OK sur un album : masquer / réafficher.", x + 22f, y + 440f, 11f, Color.rgb(130, 154, 181))
        text(c, "Masquées : ${excludedUris.size} permanentes • ${sessionExcludedUris.size} session • ${hiddenAlbums.size} albums", x + 22f, y + 470f, 11f, Color.rgb(130, 154, 181))
        controlBox(
            c,
            x + 350f,
            y + 480f,
            380f,
            39f,
            "Tout réafficher",
            settingsColumn == 1 && settingsControl == 5 && !navFocus
        )
    }

    private fun drawSettingsRules(c: Canvas, x: Float, y: Float, w: Float) {
        text(c, "Règles par album", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        controlBox(c, x + w - 190f, y + 10f, 165f, 38f, "Retour avancés", settingsColumn == 1 && settingsControl == 8 && !navFocus)
        val albums = albumPairs()
        if (albums.isEmpty()) {
            text(c, "Chargez d'abord une photothèque.", x + 22f, y + 85f, 14f, Color.rgb(174, 188, 205))
            return
        }

        ruleAlbumIndex = ruleAlbumIndex.coerceIn(0, albums.lastIndex)
        val albumName = albums[ruleAlbumIndex].first
        val rule = albumRules.getOrPut(albumName) { AlbumRule() }

        settingsChoice(c, "Album", albumName, x, y + 55f, 0)
        settingsToggle(c, "Activer une règle pour cet album", rule.enabled, x, y + 113f, 1)
        settingsSegment(c, "Jours", listOf("Tous", "Semaine", "Week-end"), rule.daysMode, x, y + 171f, 2)
        settingsChoice(c, "À partir de", "%02d:00".format(rule.startHour), x, y + 229f, 3)
        settingsChoice(c, "Jusqu'à", if (rule.endHour == 24) "24:00" else "%02d:00".format(rule.endHour), x, y + 287f, 4)
        settingsChoice(
            c,
            "Durée par photo",
            if (rule.durationSeconds <= 0) "Réglage global" else "${rule.durationSeconds} s",
            x,
            y + 345f,
            5
        )
        settingsChoice(
            c,
            "Transition",
            if (rule.transitionIndex < 0) "Réglage global" else transitions[rule.transitionIndex],
            x,
            y + 403f,
            6
        )
        settingsToggle(c, "Afficher les métadonnées", rule.showMetadata, x, y + 461f, 7)

        text(
            c,
            "Une photo présente dans plusieurs albums reste visible si au moins une règle autorise son affichage.",
            x + 22f,
            y + 528f,
            10.5f,
            Color.rgb(128, 151, 178)
        )
    }

    private fun drawSettingsAdvanced(c: Canvas, x: Float, y: Float) {
        text(c, "Avancés", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        controlBox(c, x + 665f, y + 8f, 190f, 38f, "Règles par album", settingsColumn == 1 && settingsControl == 11 && !navFocus)
        settingsChoice(c, "Affichage de l'image", imageModeLabel(), x, y + 50f, 0)
        settingsToggle(c, "Grille et magnétisme de l'éditeur", gridSnap, x, y + 103f, 1)
        settingsToggle(c, "Protection OLED (micro-déplacement)", oledProtection, x, y + 156f, 2)
        settingsToggle(c, "Masquer les informations après 10 s", overlaysAutoHide, x, y + 209f, 3)
        settingsSlider(
            c,
            "Démarrage automatique après inactivité",
            autoStartMinutes.toFloat(),
            0f,
            60f,
            if (autoStartMinutes == 0) "Désactivé" else "$autoStartMinutes min",
            x,
            y + 262f,
            4
        )
        settingsToggle(c, "Démarrer directement le diaporama", startDirectly, x, y + 315f, 5)
        settingsToggle(c, "Afficher uniquement les favoris", favoritesOnly, x, y + 368f, 6)
        settingsChoice(c, "Disposition des informations", presetName(layoutPreset), x, y + 421f, 7)

        text(c, "Sauvegarde", x + 22f, y + 487f, 12f, Color.WHITE)
        controlBox(c, x + 350f, y + 468f, 180f, 38f, "Exporter", settingsColumn == 1 && settingsControl == 8 && !navFocus)
        controlBox(c, x + 545f, y + 468f, 180f, 38f, "Importer", settingsColumn == 1 && settingsControl == 9 && !navFocus)

        settingsToggle(c, "Son des vidéos", videoSound, x, y + 500f, 10)
        val albumCount = library.flatMap { it.albums }.distinct().size
        val diag = "Diagnostic : ${library.size} médias • $albumCount albums • ${favorites.size} favoris • ${sessionExcludedUris.size} masqués session"
        text(c, diag, x + 22f, y + 545f, 10f, Color.rgb(135, 158, 184))
    }

    private fun imageModeLabel(): String =
        listOf("Remplir", "Adapter", "Original", "Fond flouté")[imageMode.coerceIn(0, 3)]

    private fun presetName(i: Int): String =
        listOf("Standard", "Minimal", "Cinéma", "Horloge")[i.coerceIn(0, 3)]

    private fun applyPreset(index: Int) {
        layoutPreset = index.coerceIn(0, 3)
        when (layoutPreset) {
            1 -> {
                styles[0].apply { size = 27f; x = 6f; y = 89f; visible = true }
                styles[1].visible = false
                showDate = false
                showTime = false
                showTemp = false
            }
            2 -> {
                styles[0].apply { size = 34f; x = 7f; y = 79f; visible = true }
                styles[1].apply { size = 18f; x = 7f; y = 86f; visible = true }
                styles[2].apply { size = 11f; x = 93f; y = 91f; align = 2 }
                showDate = true
                showTime = false
                showTemp = false
            }
            3 -> {
                styles[0].visible = false
                styles[1].visible = false
                styles[2].apply { x = 94f; y = 11f; align = 2; size = 13f }
                styles[3].apply { x = 94f; y = 18f; align = 2; size = 31f }
                styles[4].apply { x = 94f; y = 5f; align = 2; size = 19f }
                showDate = true
                showTime = true
                showTemp = true
            }
            else -> {
                styles[0].apply { size = 31f; x = 7f; y = 76f; visible = true }
                styles[1].apply { size = 19f; x = 7f; y = 84f; visible = true }
                styles[2].apply { size = 11f; x = 73f; y = 10f; align = 2 }
                styles[3].apply { size = 26f; x = 80f; y = 15f; align = 2 }
                styles[4].apply { size = 18f; x = 80f; y = 5f; align = 2 }
                showDate = true
                showTime = true
                showTemp = true
            }
        }
    }

    private fun drawSlideshow(c: Canvas) {
        val items = activePhotos()
        val item = currentItem()
        val current = currentBitmap()
        val previous = if (items.isNotEmpty() && previousPhoto in items.indices) {
            val key = items[previousPhoto].uri.toString()
            synchronized(bitmapCache) { bitmapCache[key] } ?: current
        } else current

        val localTransition = ruleForItem(item)?.transitionIndex?.takeIf { it >= 0 } ?: transitionIndex
        val name = transitions[localTransition.coerceIn(0, transitions.lastIndex)]
        if (item?.mediaType == "video" && supportsVideoPlayback) {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } else if (item?.mediaType == "gif") {
            if (!drawAnimatedGif(c, item, 0f, 0f, 1280f, 720f)) {
                drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, current)
            }
        } else when {
            transitionProgress >= 1f || previous == null || current == null -> {
                if (kenBurns || name == "Ken Burns") drawKenBurns(c, current, item)
                else drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, current)
            }

            name == "Glissement" || name == "Glissement droite" -> {
                val dir = if (name == "Glissement droite") -1f else 1f
                drawBitmapCenterCrop(c, previous, -dir * transitionProgress * 1280f, 0f, 1280f, 720f)
                drawBitmapCenterCrop(c, current, dir * (1f - transitionProgress) * 1280f, 0f, 1280f, 720f)
            }

            name == "Glissement haut" || name == "Glissement bas" -> {
                val dir = if (name == "Glissement haut") 1f else -1f
                drawBitmapCenterCrop(c, previous, 0f, -dir * transitionProgress * 720f, 1280f, 720f)
                drawBitmapCenterCrop(c, current, 0f, dir * (1f - transitionProgress) * 720f, 1280f, 720f)
            }

            name == "Zoom" || name == "Zoom arrière" -> {
                drawBitmapCenterCrop(c, previous, 0f, 0f, 1280f, 720f)
                val t = transitionProgress
                val scale = if (name == "Zoom") .82f + .18f * t else 1.18f - .18f * t
                c.save()
                c.translate(640f, 360f)
                c.scale(scale, scale)
                c.translate(-640f, -360f)
                imagePaint.alpha = (255 * t).toInt().coerceIn(0, 255)
                drawBitmapCenterCrop(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
                c.restore()
            }

            name == "Rotation douce" -> {
                drawBitmapCenterCrop(c, previous, 0f, 0f, 1280f, 720f)
                c.save()
                c.rotate((1f - transitionProgress) * 4f, 640f, 360f)
                imagePaint.alpha = (255 * transitionProgress).toInt().coerceIn(0, 255)
                drawBitmapCenterCrop(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
                c.restore()
            }

            name == "Balayage" -> {
                drawBitmapCenterCrop(c, previous, 0f, 0f, 1280f, 720f)
                c.save()
                c.clipRect(0f, 0f, 1280f * transitionProgress, 720f)
                drawBitmapCenterCrop(c, current, 0f, 0f, 1280f, 720f)
                c.restore()
            }

            name == "Cube 3D" -> {
                drawBitmapCenterCrop(c, previous, 0f, 0f, 1280f, 720f)
                c.save()
                val scaleX = (.12f + .88f * transitionProgress).coerceIn(.12f, 1f)
                c.translate(640f, 0f)
                c.scale(scaleX, 1f)
                c.translate(-640f, 0f)
                imagePaint.alpha = (255 * transitionProgress).toInt().coerceIn(0, 255)
                drawBitmapCenterCrop(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
                c.restore()
            }

            name == "Fondu au noir" || name == "Fondu au blanc" -> {
                val bg = if (name == "Fondu au blanc") Color.WHITE else Color.BLACK
                if (transitionProgress < .5f) {
                    drawBitmapCenterCrop(c, previous, 0f, 0f, 1280f, 720f)
                    fill(c, 0f, 0f, 1280f, 720f, Color.argb((transitionProgress * 2f * 255f).toInt(), Color.red(bg), Color.green(bg), Color.blue(bg)))
                } else {
                    drawBitmapCenterCrop(c, current, 0f, 0f, 1280f, 720f)
                    fill(c, 0f, 0f, 1280f, 720f, Color.argb(((1f - transitionProgress) * 2f * 255f).toInt(), Color.red(bg), Color.green(bg), Color.blue(bg)))
                }
            }

            name == "Aucune" -> drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, current)

            else -> {
                imagePaint.alpha = ((1f - transitionProgress) * 255).toInt().coerceIn(0, 255)
                drawBitmapCenterCrop(c, previous, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = (transitionProgress * 255).toInt().coerceIn(0, 255)
                drawBitmapCenterCrop(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
            }
        }

        drawBottomGradient(c, 0f, 0f, 1280f, 720f)
        val vals = metadataValues(item)
        val ruleAllowsMetadata = ruleForItem(item)?.showMetadata ?: true
        val hideOverlays = !ruleAllowsMetadata || (overlaysAutoHide && System.currentTimeMillis() - slideStartedAt > 10_000L)
        val shift = oledShift()
        if (!hideOverlays) {
            styles.forEachIndexed { i, st ->
                val global = when (i) { 2 -> showDate; 3 -> showTime; 4 -> showTemp; else -> true }
                if (!st.visible || !global || vals.getOrElse(i) { "—" } == "—") return@forEachIndexed
                applyStylePaint(st)
                val x = 1280f * st.x / 100f + shift.first
                val y = 720f * st.y / 100f + shift.second
                p.textAlign = when (st.align) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
                c.drawText(vals[i], x, y, p)
            }
        }

        if (paused) {
            round(c, 535f, 320f, 745f, 398f, 16f, Color.argb(200, 8, 18, 29))
            text(c, "EN PAUSE", 640f, 367f, 21f, Color.WHITE, 1, 1)
        }
        if (infoPanelVisible) drawInfoPanel(c, item)
        if (quickMenuVisible) drawQuickMenu(c, item)
    }

    private fun drawKenBurns(c: Canvas, bmp: Bitmap?, item: PhotoItem?) {
        if (bmp == null || bmp.isRecycled) return
        val localDuration = ruleForItem(item)?.durationSeconds?.takeIf { it > 0 } ?: durationSeconds
        val elapsed = (System.currentTimeMillis() - slideStartedAt).coerceAtLeast(0L)
        val t = (elapsed.toFloat() / (localDuration * 1000f)).coerceIn(0f, 1f)
        val strength = when (zoomLevel) {
            2 -> .12f
            1 -> .08f
            else -> .045f
        }
        val scale = 1f + strength * t
        val panX = (t - .5f) * 18f * (zoomLevel + 1)
        val panY = (.5f - t) * 10f * (zoomLevel + 1)

        c.save()
        c.translate(640f + panX, 360f + panY)
        c.scale(scale, scale)
        c.translate(-640f, -360f)
        drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, bmp)
        c.restore()
        postInvalidateDelayed(40L)
    }

    private fun oledShift(): Pair<Float, Float> {
        if (!oledProtection) return 0f to 0f
        val step = ((System.currentTimeMillis() / 60_000L) % 9L).toInt()
        val offsets = arrayOf(
            -4f to -3f, 0f to -3f, 4f to -3f,
            -4f to 0f, 0f to 0f, 4f to 0f,
            -4f to 3f, 0f to 3f, 4f to 3f
        )
        return offsets[step]
    }

    private fun drawQuickMenu(c: Canvas, item: PhotoItem?) {
        val labels = listOf(
            if (item != null && favorites.contains(item.uri.toString())) "★ Retirer des favoris" else "☆ Ajouter aux favoris",
            "Masquer pour cette session",
            "Toujours masquer cette photo",
            "Informations",
            if (paused) "Reprendre" else "Pause"
        )
        val x = 855f
        val y = 185f
        val w = 370f
        val h = 310f
        round(c, x, y, x + w, y + h, 18f, Color.argb(238, 5, 17, 29))
        strokeRound(c, x, y, x + w, y + h, 18f, Color.rgb(51, 80, 111), 1.5f)
        text(c, "Photo courante", x + 22f, y + 35f, 16f, Color.WHITE, 1)
        labels.forEachIndexed { i, label ->
            val yy = y + 56f + i * 47f
            if (quickMenuIndex == i) gradientRound(c, x + 14f, yy, x + w - 14f, yy + 39f, 10f, Color.rgb(14, 119, 243), Color.rgb(8, 85, 207))
            text(c, label, x + 31f, yy + 26f, 13f, Color.WHITE)
        }
    }

    private fun drawInfoPanel(c: Canvas, item: PhotoItem?) {
        val x = 35f
        val y = 112f
        val w = 470f
        val h = 355f
        round(c, x, y, x + w, y + h, 16f, Color.argb(232, 5, 17, 29))
        strokeRound(c, x, y, x + w, y + h, 16f, Color.rgb(50, 82, 114), 1.3f)
        text(c, "Informations", x + 22f, y + 34f, 18f, Color.WHITE, 1)
        val rows = listOf(
            "Titre" to (item?.title ?: "—"),
            "Album(s)" to (item?.albums?.joinToString(" • ") ?: "—"),
            "Date" to itemDate(item),
            "Lieu" to (item?.location?.ifBlank { "—" } ?: "—"),
            "Appareil" to (item?.camera?.ifBlank { "—" } ?: "—"),
            "Dimensions" to (item?.dimensionsLabel?.ifBlank { "—" } ?: "—"),
            "Orientation" to (item?.orientationLabel?.ifBlank { "—" } ?: "—"),
            "Type" to (item?.mediaType ?: "—")
        )
        rows.forEachIndexed { i, row ->
            val yy = y + 72f + i * 33f
            text(c, row.first, x + 22f, yy, 11f, Color.rgb(148, 167, 190))
            text(c, row.second.take(44), x + 145f, yy, 12f, Color.WHITE)
        }
    }

    private fun drawBottomNav(c: Canvas) {
        val x = 218f
        val y = 644f
        val w = 872f
        val h = 60f
        round(c, x, y, x + w, y + h, 18f, Color.argb(235, 3, 15, 28))
        strokeRound(c, x, y, x + w, y + h, 18f, Color.rgb(22, 49, 77), 1f)

        val labels = listOf("Aperçu", "Photos", "Éditeur", "Réglages")
        for (i in 0..3) {
            val tx = x + i * 218f
            val active = page == i
            if (active) gradientRound(c, tx + 3f, y + 4f, tx + 215f, y + h - 4f, 16f, Color.rgb(12, 128, 255), Color.rgb(7, 91, 237))
            if (navFocus && active) strokeRound(c, tx, y + 1f, tx + 218f, y + h - 1f, 18f, Color.rgb(154, 211, 255), 2f)
            drawBottomIcon(c, tx + 60f, y + 30f, i)
            text(c, labels[i], tx + 86f, y + 37f, 13.5f, Color.WHITE)
        }
    }

    private fun drawBrand(c: Canvas, x: Float, y: Float, subtitle: String) {
        drawCamera(c, x + 15f, y + 18f)
        text(c, "Photo TV", x + 48f, y + 23f, 25f, Color.WHITE, 1)
        text(c, subtitle, x + 48f, y + 49f, 11f, Color.rgb(206, 218, 237))
    }

    private fun drawCamera(c: Canvas, x: Float, y: Float) {
        round(c, x - 14f, y - 10f, x + 18f, y + 13f, 5f, Color.rgb(211, 220, 239))
        round(c, x - 7f, y - 15f, x + 8f, y - 8f, 4f, Color.rgb(174, 194, 229))
        circle(c, x + 2f, y + 1f, 7f, Color.rgb(45, 91, 160))
        circle(c, x + 2f, y + 1f, 4f, Color.rgb(113, 161, 228))
    }

    private fun drawClock(c: Canvas, xRight: Float, y: Float) {
        if (showTemp) text(c, "☀  ${tempText()}", xRight, y + 21f, 23f, Color.WHITE, 0, 2)
        if (showDate) text(c, mockDate(), xRight, y + 47f, 11f, Color.WHITE, 0, 2)
        if (showTime) text(c, currentTime(), xRight, y + 82f, 29f, Color.WHITE, 1, 2)
    }

    private fun datePattern(): String = when (dateFormatIndex) {
        1 -> "dd/MM/yyyy"
        2 -> "dd MMM yyyy"
        else -> "EEEE dd MMMM yyyy"
    }

    private fun dateFormatLabel(): String = when (dateFormatIndex) {
        1 -> "27/09/2025"
        2 -> "27 sept. 2025"
        else -> "Samedi 27 septembre 2025"
    }

    private fun formattedDate(time: Long): String {
        val raw = SimpleDateFormat(datePattern(), Locale.FRANCE).format(Date(time))
        return if (dateFormatIndex == 0) raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.FRANCE) else it.toString() } else raw
    }

    private fun mockDate(): String = formattedDate(System.currentTimeMillis())

    private fun itemDate(item: PhotoItem?): String =
        formattedDate(item?.takenAt?.takeIf { it > 0 } ?: System.currentTimeMillis())

    private fun currentTime(): String {
        val pattern = when {
            time24h && showSeconds -> "HH:mm:ss"
            time24h -> "HH:mm"
            showSeconds -> "h:mm:ss a"
            else -> "h:mm a"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    private fun tempText(): String {
        val c = temperatureC
        return if (tempCelsius) "${c.toInt()} °C" else "${(c * 9f / 5f + 32f).toInt()} °F"
    }

    private fun drawSourceCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, title: String, sub: String, icon: Int, focused: Boolean, primary: Boolean) {
        if (primary) gradientRound(c, x, y, x + w, y + h, 14f, Color.rgb(10, 137, 255), Color.rgb(7, 90, 235))
        else gradientRound(c, x, y, x + w, y + h, 14f, Color.rgb(18, 33, 49), Color.rgb(10, 23, 36))
        strokeRound(c, x, y, x + w, y + h, 14f, if (primary) Color.rgb(89, 188, 255) else Color.rgb(39, 58, 79), if (primary) 2f else 1f)
        if (focused) strokeRound(c, x - 3f, y - 3f, x + w + 3f, y + h + 3f, 16f, Color.rgb(183, 224, 255), 2f)
        drawSourceIcon(c, x + 36f, y + h / 2, icon)
        text(c, title, x + 70f, y + 42f, 16f, Color.WHITE, 1)
        text(c, sub, x + 70f, y + 67f, 12f, Color.rgb(221, 231, 244))
    }

    private fun drawSourceIcon(c: Canvas, x: Float, y: Float, type: Int) {
        when (type) {
            0 -> {
                val colors = listOf(Color.rgb(235, 64, 74), Color.rgb(250, 184, 35), Color.rgb(51, 178, 91), Color.rgb(52, 114, 230))
                for (i in 0..3) {
                    p.color = colors[i]
                    val path = Path()
                    val a = i * 90f
                    val r1 = 23f
                    val r2 = 8f
                    val a1 = Math.toRadians((a - 35).toDouble())
                    val a2 = Math.toRadians((a + 35).toDouble())
                    path.moveTo(x, y)
                    path.lineTo(x + (Math.cos(a1) * r1).toFloat(), y + (Math.sin(a1) * r1).toFloat())
                    path.lineTo(x + (Math.cos(Math.toRadians(a.toDouble())) * r2).toFloat(), y + (Math.sin(Math.toRadians(a.toDouble())) * r2).toFloat())
                    path.lineTo(x + (Math.cos(a2) * r1).toFloat(), y + (Math.sin(a2) * r1).toFloat())
                    path.close()
                    c.drawPath(path, p)
                }
            }
            1 -> {
                round(c, x - 18f, y - 13f, x + 20f, y + 14f, 4f, Color.rgb(246, 186, 64))
                round(c, x - 14f, y - 18f, x + 2f, y - 10f, 3f, Color.rgb(255, 205, 81))
            }
            else -> {
                round(c, x - 19f, y - 17f, x + 19f, y + 17f, 4f, Color.rgb(218, 228, 242))
                p.color = Color.rgb(60, 80, 105)
                val path = Path()
                path.moveTo(x - 13f, y + 10f)
                path.lineTo(x - 4f, y)
                path.lineTo(x + 2f, y + 6f)
                path.lineTo(x + 9f, y - 3f)
                path.lineTo(x + 15f, y + 10f)
                path.close()
                c.drawPath(path, p)
                circle(c, x + 8f, y - 8f, 3f, Color.rgb(60, 80, 105))
            }
        }
    }

    private fun drawAlbumCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, name: String, count: Int, index: Int, focused: Boolean) {
        val selected = if (library.isEmpty()) index == 0 else selectedAlbums.contains(name)
        round(c, x, y, x + w, y + h, 12f, Color.rgb(8, 18, 27))
        drawBitmapCenterCrop(c, albumBitmap(name, index), x + 5f, y + 5f, w - 10f, 128f, 9f)
        if (selected) strokeRound(c, x, y, x + w, y + h, 12f, Color.rgb(49, 177, 255), 2f)
        else strokeRound(c, x, y, x + w, y + h, 12f, Color.rgb(37, 53, 71), 1f)
        if (focused) strokeRound(c, x - 3f, y - 3f, x + w + 3f, y + h + 3f, 14f, Color.WHITE, 2f)
        text(c, name, x + 8f, y + 154f, 13f, Color.WHITE, 1)
        val hidden = hiddenAlbums.contains(name)
        text(
            c,
            if (hidden) "$count photos • MASQUÉ" else "$count photos",
            x + 8f,
            y + 177f,
            11f,
            if (hidden) Color.rgb(255, 176, 104) else Color.rgb(190, 200, 214)
        )
        if (selected && !hidden) {
            circle(c, x + w - 16f, y + 122f, 12f, Color.rgb(15, 124, 255))
            text(c, "✓", x + w - 16f, y + 127f, 13f, Color.WHITE, 1, 1)
        }
    }

    private fun albumBitmap(name: String, index: Int): Bitmap? {
        if (library.isEmpty()) return demoBitmap
        val item = library.firstOrNull { it.albums.contains(name) } ?: return demoBitmap
        return synchronized(bitmapCache) { bitmapCache[item.uri.toString()] } ?: demoBitmap
    }

    private fun drawPhotoThumb(c: Canvas, x: Float, y: Float, w: Float, h: Float, item: PhotoItem?, focused: Boolean) {
        val bmp = if (item == null) demoBitmap else synchronized(bitmapCache) { bitmapCache[item.uri.toString()] } ?: demoBitmap
        round(c, x, y, x + w, y + h, 9f, Color.rgb(11, 22, 32))
        drawBitmapCenterCrop(c, bmp, x, y, w, h, 9f)
        strokeRound(c, x, y, x + w, y + h, 9f, if (focused) Color.WHITE else Color.rgb(39, 58, 77), if (focused) 2f else 1f)
    }

    private fun drawMetaCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, name: String, value: String, selected: Boolean, focused: Boolean) {
        if (selected) gradientRound(c, x, y, x + w, y + h, 12f, Color.rgb(10, 125, 255), Color.rgb(7, 88, 235))
        else gradientRound(c, x, y, x + w, y + h, 12f, Color.rgb(14, 28, 41), Color.rgb(8, 20, 31))
        strokeRound(c, x, y, x + w, y + h, 12f, if (selected) Color.rgb(47, 159, 255) else Color.rgb(33, 51, 69), 1f)
        if (focused) strokeRound(c, x - 3f, y - 3f, x + w + 3f, y + h + 3f, 14f, Color.WHITE, 2f)
        round(c, x + 14f, y + 19f, x + 42f, y + 47f, 7f, Color.rgb(10, 124, 255))
        text(c, "✓", x + 28f, y + 39f, 15f, Color.WHITE, 1, 1)
        text(c, name, x + 58f, y + 29f, 14f, Color.WHITE, 1)
        text(c, value, x + 58f, y + 53f, 11f, Color.rgb(221, 228, 238))
    }

    private fun drawTransitionCards(c: Canvas, x: Float, y: Float, focused: Boolean) {
        val names = listOf("Fondu", "Glissement", "Zoom", "Ken Burns", "Dissolution", "Cube 3D")
        val w = 131f
        val h = 100f
        val gap = 10f
        names.forEachIndexed { i, name ->
            val xx = x + i * (w + gap)
            round(c, xx, y, xx + w, y + h, 8f, Color.rgb(13, 27, 40))
            drawBitmapCenterCrop(c, currentBitmap(), xx + 3f, y + 3f, w - 6f, 66f, 6f)
            val active = transitions[transitionIndex].startsWith(name)
            if (active) strokeRound(c, xx - 2f, y - 2f, xx + w + 2f, y + h + 2f, 10f, Color.rgb(29, 145, 255), 2f)
            if (focused && i == min(transitionIndex, 5)) strokeRound(c, xx - 5f, y - 5f, xx + w + 5f, y + h + 5f, 11f, Color.WHITE, 2f)
            text(c, name, xx + w / 2, y + 91f, 10.5f, Color.WHITE, 0, 1)
        }
    }

    private fun settingsSlider(c: Canvas, label: String, value: Float, minV: Float, maxV: Float, valueText: String, x: Float, y: Float, control: Int) {
        text(c, label, x + 22f, y + 25f, 12f, Color.WHITE)
        val barX = x + 350f
        val barY = y + 18f
        val barW = 375f
        round(c, barX, barY, barX + barW, barY + 7f, 4f, Color.rgb(43, 58, 74))
        val t = ((value - minV) / (maxV - minV)).coerceIn(0f, 1f)
        round(c, barX, barY, barX + barW * t, barY + 7f, 4f, Color.rgb(10, 123, 255))
        circle(c, barX + barW * t, barY + 3.5f, 9f, Color.rgb(14, 135, 255))
        text(c, valueText, x + 862f, y + 25f, 12f, Color.WHITE, 0, 2)
        if (settingsColumn == 1 && settingsControl == control && !navFocus) strokeRound(c, barX - 8f, barY - 11f, barX + barW + 8f, barY + 19f, 10f, Color.rgb(91, 178, 255), 2f)
    }

    private fun settingsToggle(c: Canvas, label: String, on: Boolean, x: Float, y: Float, control: Int) {
        text(c, label, x + 22f, y + 27f, 12f, Color.WHITE)
        drawToggle(c, x + 353f, y + 12f, on, settingsColumn == 1 && settingsControl == control && !navFocus)
    }

    private fun settingsChoice(c: Canvas, label: String, value: String, x: Float, y: Float, control: Int) {
        text(c, label, x + 22f, y + 28f, 12f, Color.WHITE)
        controlBox(c, x + 350f, y + 7f, 380f, 39f, value, settingsColumn == 1 && settingsControl == control && !navFocus)
    }

    private fun settingsSegment(c: Canvas, label: String, values: List<String>, selected: Int, x: Float, y: Float, control: Int) {
        text(c, label, x + 22f, y + 29f, 12f, Color.WHITE)
        val bx = x + 350f
        val bw = 380f
        round(c, bx, y + 6f, bx + bw, y + 43f, 7f, Color.rgb(31, 44, 59))
        val sw = bw / values.size
        values.forEachIndexed { i, v ->
            if (selected == i) round(c, bx + i * sw, y + 6f, bx + (i + 1) * sw, y + 43f, 7f, Color.rgb(13, 112, 226))
            text(c, v, bx + i * sw + sw / 2, y + 30f, 11f, Color.WHITE, 0, 1)
        }
        if (settingsColumn == 1 && settingsControl == control && !navFocus) strokeRound(c, bx - 3f, y + 3f, bx + bw + 3f, y + 46f, 9f, Color.rgb(91, 178, 255), 2f)
    }

    private fun controlBox(c: Canvas, x: Float, y: Float, w: Float, h: Float, value: String, focused: Boolean) {
        round(c, x, y, x + w, y + h, 6f, Color.rgb(8, 20, 31))
        strokeRound(c, x, y, x + w, y + h, 6f, if (focused) Color.rgb(60, 157, 255) else Color.rgb(50, 72, 94), if (focused) 2f else 1f)
        text(c, value, x + 10f, y + h * .66f, 11f, Color.WHITE)
    }

    private fun drawSliderRow(c: Canvas, label: String, value: Float, minV: Float, maxV: Float, x: Float, y: Float, w: Float, focused: Boolean) {
        text(c, label, x, y + 13f, 12f, Color.WHITE)
        text(c, value.toInt().toString(), x + w - 8f, y + 13f, 11f, Color.rgb(191, 202, 216), 0, 2)
        val bx = x + 95f
        val by = y + 7f
        val bw = w - 150f
        round(c, bx, by, bx + bw, by + 6f, 3f, Color.rgb(41, 56, 72))
        val t = ((value - minV) / (maxV - minV)).coerceIn(0f, 1f)
        round(c, bx, by, bx + bw * t, by + 6f, 3f, Color.rgb(13, 117, 240))
        circle(c, bx + bw * t, by + 3f, 8f, Color.rgb(15, 130, 255))
        if (focused) strokeRound(c, bx - 7f, by - 10f, bx + bw + 7f, by + 16f, 8f, Color.rgb(84, 173, 255), 2f)
    }

    private fun drawToggle(c: Canvas, x: Float, y: Float, on: Boolean, focused: Boolean) {
        round(c, x, y, x + 52f, y + 28f, 14f, if (on) Color.rgb(11, 122, 255) else Color.rgb(45, 58, 73))
        circle(c, if (on) x + 38f else x + 14f, y + 14f, 10f, Color.WHITE)
        if (focused) strokeRound(c, x - 3f, y - 3f, x + 55f, y + 31f, 16f, Color.rgb(139, 206, 255), 2f)
    }

    private fun drawSideIcon(c: Canvas, x: Float, y: Float, type: Int) {
        p.color = Color.rgb(224, 231, 241)
        when (type) {
            0 -> {
                val path = Path()
                path.moveTo(x - 7f, y - 9f); path.lineTo(x + 9f, y); path.lineTo(x - 7f, y + 9f); path.close()
                c.drawPath(path, p)
            }
            1 -> text(c, "T", x, y + 7f, 20f, Color.WHITE, 1, 1)
            2 -> { strokeRound(c, x - 10f, y - 8f, x + 10f, y + 8f, 2f, Color.WHITE, 2f); c.drawLine(x - 6f,y-2f,x+6f,y-2f,stroke) }
            3 -> drawBottomIcon(c, x, y, 1)
            4 -> { stroke.style=Paint.Style.STROKE;stroke.color=Color.WHITE;stroke.strokeWidth=2f;c.drawCircle(x,y,10f,stroke);c.drawLine(x,y,x,y-6f,stroke);c.drawLine(x,y,x+5f,y+2f,stroke) }
            5 -> text(c, "♨", x, y + 7f, 18f, Color.WHITE, 0, 1)
            6 -> drawSourceIcon(c, x, y, 2)
            else -> drawGear(c, x, y, 9f)
        }
    }

    private fun drawBottomIcon(c: Canvas, x: Float, y: Float, type: Int) {
        when (type) {
            0, 1 -> {
                stroke.style = Paint.Style.STROKE
                stroke.color = Color.WHITE
                stroke.strokeWidth = 1.6f
                c.drawRoundRect(x - 9f, y - 7f, x + 9f, y + 7f, 2f, 2f, stroke)
                p.color = Color.WHITE
                val path = Path()
                path.moveTo(x - 6f, y + 4f); path.lineTo(x - 1f, y - 1f); path.lineTo(x + 2f, y + 2f); path.lineTo(x + 6f, y - 3f); path.lineTo(x + 8f, y + 4f); path.close()
                c.drawPath(path, p)
            }
            2 -> {
                stroke.style = Paint.Style.STROKE; stroke.color=Color.WHITE; stroke.strokeWidth=1.8f
                c.drawCircle(x,y,9f,stroke)
                c.drawCircle(x-2f,y-3f,2f,stroke);c.drawCircle(x+4f,y,2f,stroke);c.drawCircle(x-1f,y+5f,2f,stroke)
            }
            else -> drawGear(c,x,y,8f)
        }
    }

    private fun drawGear(c: Canvas, x: Float, y: Float, r: Float) {
        p.color = Color.WHITE
        c.drawCircle(x, y, r, p)
        p.color = Color.rgb(9, 24, 38)
        c.drawCircle(x, y, r * .4f, p)
        stroke.style=Paint.Style.STROKE;stroke.color=Color.WHITE;stroke.strokeWidth=3f
        for(i in 0 until 8){
            val a=Math.toRadians((i*45).toDouble())
            c.drawLine(x+(Math.cos(a)*r).toFloat(),y+(Math.sin(a)*r).toFloat(),x+(Math.cos(a)*(r+4)).toFloat(),y+(Math.sin(a)*(r+4)).toFloat(),stroke)
        }
    }

    private fun drawAlignIcon(c: Canvas, x: Float, y: Float, align: Int) {
        stroke.style=Paint.Style.STROKE;stroke.color=Color.WHITE;stroke.strokeWidth=1.5f
        val widths = floatArrayOf(14f, 10f, 16f)
        for(i in 0..2){
            val yy=y-6f+i*6f
            val ww=widths[i]
            val start=when(align){1->x-ww/2;2->x+7f-ww;else->x-7f}
            c.drawLine(start,yy,start+ww,yy,stroke)
        }
    }

    private fun applyStylePaint(s: Style) {
        p.style = Paint.Style.FILL
        p.color = s.color
        p.textSize = s.size
        p.typeface = when (s.font) {
            1 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            2 -> Typeface.create("serif", Typeface.NORMAL)
            3 -> Typeface.MONOSPACE
            else -> Typeface.create("sans-serif", if (s === styles[0]) Typeface.BOLD else Typeface.NORMAL)
        }
        if (s.shadow) p.setShadowLayer(6f, 0f, 2f, Color.BLACK) else p.clearShadowLayer()
    }

    private fun fontName(index: Int) = listOf("Inter", "Inter Light", "Serif", "Monospace")[index.coerceIn(0, 3)]
    private fun format1(v: Float): String = String.format(Locale.FRANCE, "%.1f", v)

    private fun drawAppBackground(c: Canvas) {
        p.shader = LinearGradient(0f, 0f, 1280f, 720f, Color.rgb(4, 13, 21), Color.rgb(1, 7, 12), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1280f, 720f, p)
        p.shader = null
        p.shader = RadialGradient(1020f, 20f, 520f, Color.argb(95, 33, 75, 118), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,1280f,720f,p)
        p.shader=null
    }

    private fun drawBottomGradient(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        p.shader = LinearGradient(0f, y + h * .45f, 0f, y + h, Color.TRANSPARENT, Color.argb(220, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(x, y, x + w, y + h, p)
        p.shader = null
    }

    private fun drawBackgroundPhoto(c: Canvas, x: Float, y: Float, w: Float, h: Float, bmp: Bitmap?) {
        fill(c, x, y, x + w, y + h, Color.rgb(6, 12, 18))
        when (imageMode) {
            1 -> drawBitmapFit(c, bmp, x, y, w, h)
            2 -> drawBitmapOriginal(c, bmp, x, y, w, h)
            3 -> {
                drawSoftBackground(c, bmp, x, y, w, h)
                fill(c, x, y, x + w, y + h, Color.argb(75, 0, 0, 0))
                drawBitmapFit(c, bmp, x, y, w, h)
            }
            else -> drawBitmapCenterCrop(c, bmp, x, y, w, h)
        }
    }

    private fun drawBitmapFit(c: Canvas, bmp: Bitmap?, x: Float, y: Float, w: Float, h: Float) {
        if (bmp == null || bmp.isRecycled) return
        val scale = min(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        val sw = bmp.width * scale
        val sh = bmp.height * scale
        c.drawBitmap(
            bmp,
            null,
            RectF(
                x + (w - sw) / 2f,
                y + (h - sh) / 2f,
                x + (w + sw) / 2f,
                y + (h + sh) / 2f
            ),
            imagePaint
        )
    }

    private fun drawBitmapOriginal(c: Canvas, bmp: Bitmap?, x: Float, y: Float, w: Float, h: Float) {
        if (bmp == null || bmp.isRecycled) return
        val scale = min(1f, min(w / bmp.width.toFloat(), h / bmp.height.toFloat()))
        val sw = bmp.width * scale
        val sh = bmp.height * scale
        c.drawBitmap(
            bmp,
            null,
            RectF(
                x + (w - sw) / 2f,
                y + (h - sh) / 2f,
                x + (w + sw) / 2f,
                y + (h + sh) / 2f
            ),
            imagePaint
        )
    }

    private fun drawSoftBackground(c: Canvas, bmp: Bitmap?, x: Float, y: Float, w: Float, h: Float) {
        if (bmp == null || bmp.isRecycled) return
        if (softSource !== bmp || softBitmap == null || softBitmap?.isRecycled == true) {
            softBitmap?.recycle()
            softSource = bmp
            softBitmap = runCatching {
                val tiny = Bitmap.createScaledBitmap(bmp, 48, 27, true)
                Bitmap.createScaledBitmap(tiny, 480, 270, true).also {
                    if (it !== tiny) tiny.recycle()
                }
            }.getOrNull()
        }
        drawBitmapCenterCrop(c, softBitmap ?: bmp, x, y, w, h)
    }

    private fun drawBitmapCenterCrop(c: Canvas, bmp: Bitmap?, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        if (bmp == null || bmp.isRecycled) return
        val scale = max(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        val sw = bmp.width * scale
        val sh = bmp.height * scale
        val left = x + (w - sw) / 2
        val top = y + (h - sh) / 2
        val dst = RectF(left, top, left + sw, top + sh)
        if (radius > 0f) {
            c.save()
            val path = Path()
            path.addRoundRect(RectF(x,y,x+w,y+h), radius, radius, Path.Direction.CW)
            c.clipPath(path)
            c.drawBitmap(bmp, null, dst, imagePaint)
            c.restore()
        } else c.drawBitmap(bmp, null, dst, imagePaint)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, weight: Int = 0, align: Int = 0) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        p.textSize = size
        p.typeface = Typeface.create("sans-serif", if (weight == 1) Typeface.BOLD else Typeface.NORMAL)
        p.textAlign = when (align) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        p.clearShadowLayer()
        c.drawText(s, x, y, p)
    }

    private fun fill(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color; c.drawRect(l,t,r,b,p)
    }

    private fun round(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(l,t,r,b,radius,radius,p)
    }

    private fun gradientRound(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, c1: Int, c2: Int) {
        p.shader = LinearGradient(l,t,r,b,c1,c2,Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawRoundRect(l,t,r,b,radius,radius,p)
        p.shader = null
    }

    private fun strokeRound(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, width: Float) {
        stroke.style=Paint.Style.STROKE;stroke.color=color;stroke.strokeWidth=width;c.drawRoundRect(l,t,r,b,radius,radius,stroke)
    }

    private fun circle(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        p.shader=null;p.style=Paint.Style.FILL;p.color=color;c.drawCircle(x,y,radius,p)
    }

    private fun markInteraction() {
        scheduleInactivity()
    }

    private fun scheduleInactivity() {
        inactivityHandler.removeCallbacksAndMessages(null)
        inactivityToken = System.currentTimeMillis()
        if (autoStartMinutes <= 0 || slideshow) return
        val token = inactivityToken
        inactivityHandler.postDelayed({
            if (token == inactivityToken && !slideshow && activePhotos().isNotEmpty()) {
                startSlideshow()
            }
        }, autoStartMinutes * 60_000L)
    }

    private fun handleLongAction(): Boolean {
        if (slideshow) {
            if (!quickMenuVisible) {
                quickMenuVisible = true
                quickMenuIndex = 0
                paused = true
                handler.removeCallbacksAndMessages(null)
                invalidate()
            }
            return true
        }
        if (page == 1 && photosRow == 1 && library.isNotEmpty()) {
            val album = currentAlbumName()
            if (hiddenAlbums.contains(album)) hiddenAlbums.remove(album) else hiddenAlbums.add(album)
            savePrefs()
            invalidate()
            return true
        }
        if (page == 2 && editorColumn == 1) {
            editorMoveMode = !editorMoveMode
            invalidate()
            return true
        }
        return false
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longActionLatched = true
            return handleLongAction()
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longActionLatched = false
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        markInteraction()

        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (event?.repeatCount ?: 0) >= 2 && !longActionLatched
        ) {
            longActionLatched = true
            return handleLongAction()
        }

        if (quickMenuVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    quickMenuIndex = (quickMenuIndex - 1 + 5) % 5
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    quickMenuIndex = (quickMenuIndex + 1) % 5
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    activateQuickMenu()
                    true
                }
                KeyEvent.KEYCODE_BACK -> {
                    quickMenuVisible = false
                    invalidate()
                    true
                }
                else -> true
            }
        }

        if (infoPanelVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    infoPanelVisible = false
                    invalidate()
                    true
                }
                else -> true
            }
        }

        if (slideshow) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { slideshowNext(1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { slideshowNext(-1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePause()
                    true
                }
                KeyEvent.KEYCODE_BACK -> { stopSlideshow(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> if (page == 3 && settingsCategory == 7 && advancedRulesOpen) {
                advancedRulesOpen = false
                settingsControl = 0
                invalidate()
                return true
            } else if (page != 0) {
                page = 0
                navFocus = true
                editorMoveMode = false
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveVertical(1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveVertical(-1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveHorizontal(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveHorizontal(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!longActionLatched) activate()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun activateQuickMenu() {
        val item = currentItem()
        when (quickMenuIndex) {
            0 -> item?.let {
                val key = it.uri.toString()
                if (favorites.contains(key)) favorites.remove(key) else favorites.add(key)
            }
            1 -> item?.let {
                sessionExcludedUris.add(it.uri.toString())
                quickMenuVisible = false
                paused = false
                if (activePhotos().isEmpty()) stopSlideshow() else {
                    currentPhoto = currentPhoto.coerceIn(0, activePhotos().lastIndex)
                    slideStartedAt = System.currentTimeMillis()
                    preloadAroundCurrent()
                    scheduleSlideshow()
                }
            }
            2 -> item?.let {
                excludedUris.add(it.uri.toString())
                quickMenuVisible = false
                paused = false
                if (activePhotos().isEmpty()) stopSlideshow() else {
                    currentPhoto = currentPhoto.coerceIn(0, activePhotos().lastIndex)
                    slideStartedAt = System.currentTimeMillis()
                    preloadAroundCurrent()
                    scheduleSlideshow()
                }
            }
            3 -> {
                quickMenuVisible = false
                infoPanelVisible = true
            }
            4 -> {
                quickMenuVisible = false
                togglePause()
            }
        }
        savePrefs()
        invalidate()
    }

    private fun moveVertical(dir: Int) {
        if (navFocus) {
            if (dir < 0) {
                navFocus = false
                when (page) {
                    1 -> photosRow = 2
                    2 -> editorColumn = 1
                    3 -> settingsColumn = 1
                }
            }
            invalidate()
            return
        }

        when (page) {
            0 -> if (dir > 0) navFocus = true
            1 -> {
                photosRow = (photosRow + dir).coerceIn(0, 3)
                if (photosRow == 3) navFocus = true
            }
            2 -> {
                if (editorColumn == 1 && editorMoveMode) {
                    moveEditorByPixels(0, dir)
                } else if (editorColumn == 0) {
                    editorElement = (editorElement + dir).coerceIn(0, elementNames.lastIndex)
                } else if (editorColumn == 2) {
                    editorControl = (editorControl + dir).coerceIn(0, 7)
                } else if (dir > 0) {
                    navFocus = true
                }
            }
            3 -> {
                if (settingsColumn == 0) {
                    settingsCategory = (settingsCategory + dir).coerceIn(0, 7)
                    settingsControl = settingsControl.coerceIn(0, settingsControlMax())
                } else {
                    settingsControl = (settingsControl + dir).coerceIn(0, settingsControlMax())
                }
            }
        }
        savePrefs()
        invalidate()
    }

    private fun moveHorizontal(dir: Int) {
        if (navFocus) {
            page = (page + dir + 4) % 4
            invalidate()
            return
        }

        when (page) {
            0 -> if (activePhotos().isNotEmpty()) previewNext(dir)
            1 -> when (photosRow) {
                0 -> sourceFocus = (sourceFocus + dir).coerceIn(0, 2)
                1 -> albumFocus = (albumFocus + dir).coerceIn(0, max(0, albumPairs().size.coerceAtMost(6) - 1))
                2 -> photoFocus = (photoFocus + dir).coerceIn(0, max(0, currentAlbumPhotos().size.coerceAtMost(6) - 1))
                else -> navFocus = true
            }
            2 -> {
                when {
                    editorColumn == 1 && editorMoveMode -> moveEditorByPixels(dir, 0)
                    editorColumn == 1 -> editorColumn = if (dir < 0) 0 else 2
                    editorColumn == 0 && dir > 0 -> editorColumn = 1
                    editorColumn == 2 && dir < 0 -> editorColumn = 1
                    editorColumn == 2 -> adjustEditor(dir)
                }
            }
            3 -> {
                if (settingsColumn == 0 && dir > 0) settingsColumn = 1
                else if (settingsColumn == 1 && dir < 0) settingsColumn = 0
                else if (settingsColumn == 1) adjustSettings(dir)
            }
        }
        savePrefs()
        invalidate()
    }

    private fun moveEditorByPixels(dx: Int, dy: Int) {
        val st = styles[editorElement]
        if (dx != 0) {
            st.x = (st.x + dx * (100f / 1280f)).coerceIn(0f, 100f)
            if (gridSnap) st.x = snapPercent(st.x)
        }
        if (dy != 0) {
            st.y = (st.y + dy * (100f / 720f)).coerceIn(0f, 100f)
            if (gridSnap) st.y = snapPercent(st.y)
        }
    }

    private fun snapPercent(v: Float): Float {
        val nearest = (v / 5f).toInt() * 5f
        val upper = nearest + 5f
        val candidate = if (kotlin.math.abs(v - nearest) < kotlin.math.abs(v - upper)) nearest else upper
        return if (kotlin.math.abs(v - candidate) <= 0.45f) candidate.coerceIn(0f, 100f) else v
    }

    private fun settingsControlMax(): Int = when(settingsCategory) {
        0 -> 7
        1 -> elementNames.lastIndex
        2 -> elementNames.lastIndex
        3 -> transitions.lastIndex
        4 -> 4
        5 -> 2
        6 -> 5
        else -> if (advancedRulesOpen) 8 else 11
    }

    private fun adjustEditor(dir: Int) {
        val s = styles[editorElement]
        when (editorControl) {
            1 -> s.font = (s.font + dir + 4) % 4
            2 -> s.size = (s.size + dir).coerceIn(12f, 80f)
            3 -> {
                val colors = listOf(Color.WHITE, Color.rgb(173,196,255), Color.rgb(255,224,112), Color.rgb(255,126,126), Color.rgb(137,230,173))
                var idx = colors.indexOf(s.color).coerceAtLeast(0)
                idx = (idx + dir + colors.size) % colors.size
                s.color = colors[idx]
            }
            4 -> s.x = (s.x + dir).coerceIn(0f, 100f)
            5 -> s.y = (s.y + dir).coerceIn(0f, 100f)
            6 -> s.align = (s.align + dir + 3) % 3
        }
    }

    private fun adjustAlbumRule(dir: Int) {
        val albums = albumPairs()
        if (albums.isEmpty()) return

        if (settingsControl == 8) {
            advancedRulesOpen = false
            settingsControl = 0
            return
        }
        if (settingsControl == 0) {
            ruleAlbumIndex = (ruleAlbumIndex + dir + albums.size) % albums.size
            return
        }

        val albumName = albums[ruleAlbumIndex.coerceIn(0, albums.lastIndex)].first
        val rule = albumRules.getOrPut(albumName) { AlbumRule() }
        when (settingsControl) {
            1 -> rule.enabled = !rule.enabled
            2 -> rule.daysMode = (rule.daysMode + dir + 3) % 3
            3 -> rule.startHour = (rule.startHour + dir + 24) % 24
            4 -> {
                var next = rule.endHour + dir
                if (next < 1) next = 24
                if (next > 24) next = 1
                rule.endHour = next
            }
            5 -> {
                if (dir > 0) {
                    rule.durationSeconds = if (rule.durationSeconds == 0) 5 else (rule.durationSeconds + 5).coerceAtMost(120)
                } else {
                    rule.durationSeconds = if (rule.durationSeconds <= 5) 0 else rule.durationSeconds - 5
                }
            }
            6 -> {
                val size = transitions.size + 1
                var encoded = rule.transitionIndex + 1
                encoded = (encoded + dir + size) % size
                rule.transitionIndex = encoded - 1
            }
            7 -> rule.showMetadata = !rule.showMetadata
        }
    }

    private fun adjustSettings(dir: Int) {
        when (settingsCategory) {
            0 -> when (settingsControl) {
                0 -> durationSeconds = (durationSeconds + dir).coerceIn(2, 120)
                1 -> fixedImage = dir > 0
                2 -> loop = !loop
                3 -> randomOrder = !randomOrder
                4 -> transitionIndex = (transitionIndex + dir + transitions.size) % transitions.size
                5 -> transitionSeconds = (transitionSeconds + dir * .1f).coerceIn(.2f, 4f)
                6 -> kenBurns = !kenBurns
                7 -> zoomLevel = (zoomLevel + dir + 3) % 3
            }
            1 -> toggleElement(settingsControl)
            2 -> editorElement = (editorElement + dir + elementNames.size) % elementNames.size
            3 -> transitionIndex = (transitionIndex + dir + transitions.size) % transitions.size
            4 -> when(settingsControl) {
                0 -> showDate = !showDate
                1 -> dateFormatIndex = (dateFormatIndex + dir + 3) % 3
                2 -> showTime = !showTime
                3 -> time24h = !time24h
                4 -> {
                    showSeconds = !showSeconds
                    scheduleClock()
                }
            }
            5 -> when(settingsControl) { 0 -> showTemp=!showTemp; 1 -> tempCelsius=!tempCelsius }
            6 -> when (settingsControl) {
                2 -> albumSort = (albumSort + dir + 2) % 2
                4 -> toggleAllAlbums()
                5 -> clearAllMasks()
            }
            7 -> if (advancedRulesOpen) {
                adjustAlbumRule(dir)
            } else when (settingsControl) {
                0 -> imageMode = (imageMode + dir + 4) % 4
                1 -> gridSnap = !gridSnap
                2 -> oledProtection = !oledProtection
                3 -> overlaysAutoHide = !overlaysAutoHide
                4 -> autoStartMinutes = (autoStartMinutes + dir).coerceIn(0, 60)
                5 -> startDirectly = !startDirectly
                6 -> favoritesOnly = !favoritesOnly
                7 -> applyPreset((layoutPreset + dir + 4) % 4)
                10 -> {
                    videoSound = !videoSound
                    syncVideoPlayback()
                }
                11 -> {
                    advancedRulesOpen = true
                    settingsControl = 0
                }
            }
        }
        scheduleSlideshow()
        scheduleInactivity()
    }

    private fun activate() {
        if (navFocus) {
            navFocus = false
            when (page) { 1 -> photosRow = 0; 2 -> editorColumn = 0; 3 -> settingsColumn = 0 }
            invalidate(); return
        }
        when (page) {
            0 -> {
                if (activePhotos().isNotEmpty()) startSlideshow() else { page = 1; photosRow = 0; sourceFocus = 0 }
            }
            1 -> when (photosRow) {
                0 -> when (sourceFocus) {
                    0 -> onExactSource()
                    1 -> onFolderSource()
                    else -> onPickPhotos()
                }
                1 -> {
                    val name = currentAlbumName()
                    if (library.isNotEmpty()) {
                        if (selectedAlbums.contains(name)) selectedAlbums.remove(name) else selectedAlbums.add(name)
                    }
                }
                2 -> if (library.isNotEmpty()) {
                    val item = currentAlbumPhotos().getOrNull(photoFocus)
                    if (item != null) {
                        val active = activePhotos()
                        val idx = active.indexOfFirst { it.uri == item.uri }
                        if (idx >= 0) currentPhoto = idx
                        page = 0
                    }
                }
            }
            2 -> {
                when (editorColumn) {
                    0 -> editorColumn = 1
                    1 -> editorMoveMode = !editorMoveMode
                    2 -> {
                        val st = styles[editorElement]
                        when (editorControl) {
                            7 -> st.shadow = !st.shadow
                        }
                    }
                }
            }
            3 -> {
                if (settingsColumn == 0) settingsColumn = 1
                else when(settingsCategory) {
                    0 -> adjustSettings(1)
                    1 -> toggleElement(settingsControl)
                    2 -> { page = 2; editorColumn = 0 }
                    3 -> transitionIndex = settingsControl.coerceIn(0, transitions.lastIndex)
                    4 -> adjustSettings(1)
                    5 -> if (settingsControl == 2) onWeatherLocation() else adjustSettings(1)
                    6 -> when (settingsControl) {
                        0 -> onExactSource()
                        1 -> onPickPhotos()
                        2 -> adjustSettings(1)
                        3 -> onAlbumSearch()
                        4 -> toggleAllAlbums()
                        5 -> clearAllMasks()
                    }
                    7 -> if (advancedRulesOpen) {
                        if (settingsControl == 8) {
                            advancedRulesOpen = false
                            settingsControl = 0
                        } else adjustAlbumRule(1)
                    } else when (settingsControl) {
                        8 -> onExportSettings()
                        9 -> onImportSettings()
                        11 -> {
                            advancedRulesOpen = true
                            settingsControl = 0
                        }
                        else -> adjustSettings(1)
                    }
                }
            }
        }
        savePrefs()
        invalidate()
    }

    private fun clearAllMasks() {
        excludedUris.clear()
        sessionExcludedUris.clear()
        hiddenAlbums.clear()
        savePrefs()
    }

    private fun toggleAllAlbums() {
        val all = library.flatMap { it.albums }.distinct()
        if (all.isEmpty()) return
        if (selectedAlbums.containsAll(all)) selectedAlbums.clear()
        else {
            selectedAlbums.clear()
            selectedAlbums.addAll(all)
        }
        currentPhoto = 0
        savePrefs()
    }

    private fun toggleElement(i: Int) {
        when (i) {
            2 -> showDate = !showDate
            3 -> showTime = !showTime
            4 -> showTemp = !showTemp
            else -> styles.getOrNull(i)?.let { it.visible = !it.visible }
        }
    }

    private fun previewNext(dir: Int) {
        val items = activePhotos()
        if (items.isEmpty()) return
        currentPhoto = (currentPhoto + dir + items.size) % items.size
        slideStartedAt = System.currentTimeMillis()
        preloadAroundCurrent()
        savePrefs()
        invalidate()
    }

    private fun syncVideoPlayback() {
        val item = if (slideshow) currentItem() else null
        if (item?.mediaType == "video" && supportsVideoPlayback) onVideoPlayback(item.uri, videoSound)
        else onVideoPlayback(null, videoSound)
    }

    private fun startSlideshow() {
        val items = activePhotos()
        if (items.isEmpty()) return
        currentPhoto = currentPhoto.coerceIn(0, items.lastIndex)
        slideshow = true
        paused = false
        quickMenuVisible = false
        infoPanelVisible = false
        history.clear()
        rebuildShuffleBag()
        transitionProgress = 1f
        slideStartedAt = System.currentTimeMillis()
        preloadAroundCurrent()
        syncVideoPlayback()
        inactivityHandler.removeCallbacksAndMessages(null)
        invalidate()
        scheduleSlideshow()
    }

    private fun stopSlideshow() {
        slideshow = false
        paused = false
        quickMenuVisible = false
        infoPanelVisible = false
        handler.removeCallbacksAndMessages(null)
        transitionAnimator?.cancel()
        onVideoPlayback(null, videoSound)
        savePrefs()
        scheduleInactivity()
        invalidate()
    }

    private fun togglePause() {
        paused = !paused
        scheduleSlideshow()
        invalidate()
    }

    private fun scheduleSlideshow() {
        handler.removeCallbacksAndMessages(null)
        if (!slideshow || paused || fixedImage || quickMenuVisible || infoPanelVisible) return
        val seconds = ruleForItem(currentItem())?.durationSeconds?.takeIf { it > 0 } ?: durationSeconds
        handler.postDelayed({ slideshowNext(1) }, seconds * 1000L)
    }

    private fun rebuildShuffleBag() {
        shuffleBag.clear()
        val items = activePhotos()
        if (items.size <= 1) return
        shuffleBag.addAll(items.indices.filter { it != currentPhoto })
        shuffleBag.shuffle()
    }

    private fun preloadAroundCurrent() {
        val items = activePhotos()
        if (items.isEmpty()) return
        val uris = mutableListOf<Uri>()
        for (offset in 0..5) {
            val idx = if (items.isEmpty()) 0 else (currentPhoto + offset) % items.size
            items.getOrNull(idx)?.let { uris += it.uri }
        }
        preload(uris)
    }

    private fun slideshowNext(dir: Int) {
        val items = activePhotos()
        if (items.isEmpty()) {
            stopSlideshow()
            return
        }

        currentPhoto = currentPhoto.coerceIn(0, items.lastIndex)
        previousPhoto = currentPhoto

        if (randomOrder && items.size > 1) {
            if (dir < 0) {
                if (history.isNotEmpty()) {
                    currentPhoto = history.removeAt(history.lastIndex).coerceIn(0, items.lastIndex)
                } else {
                    currentPhoto = (currentPhoto - 1 + items.size) % items.size
                }
            } else {
                history += currentPhoto
                if (history.size > 100) history.removeAt(0)
                if (shuffleBag.isEmpty()) rebuildShuffleBag()
                currentPhoto = if (shuffleBag.isNotEmpty()) shuffleBag.removeAt(0) else currentPhoto
            }
        } else {
            if (dir > 0) {
                history += currentPhoto
                var next = currentPhoto + 1
                if (next >= items.size) {
                    if (loop) next = 0 else {
                        stopSlideshow()
                        return
                    }
                }
                currentPhoto = next
            } else {
                currentPhoto = if (currentPhoto <= 0) {
                    if (loop) items.lastIndex else 0
                } else currentPhoto - 1
            }
        }

        slideStartedAt = System.currentTimeMillis()
        savePrefs()
        preloadAroundCurrent()
        syncVideoPlayback()
        startTransition()
        scheduleSlideshow()
    }

    private fun startTransition() {
        transitionAnimator?.cancel()
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (transitionSeconds * 1000L).toLong().coerceAtLeast(80L)
            addUpdateListener {
                transitionProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun handlePhotosTap(x: Float, y: Float) {
        when {
            y in 108f..210f -> {
                when {
                    x in 47f..435f -> onExactSource()
                    x in 450f..812f -> onFolderSource()
                    x in 828f..1238f -> onPickPhotos()
                }
            }
            y in 270f..468f -> {
                val i = ((x - 47f) / 199f).toInt()
                val albums = albumPairs()
                if (i in 0 until min(6, albums.size) && x >= 47f + i * 199f && x <= 232f + i * 199f) {
                    albumFocus = i
                    val name = albums[i].first
                    if (library.isNotEmpty()) {
                        if (selectedAlbums.contains(name)) selectedAlbums.remove(name) else selectedAlbums.add(name)
                    }
                }
            }
            y in 522f..626f -> {
                val i = ((x - 47f) / 199f).toInt()
                val photos = currentAlbumPhotos()
                if (i in 0 until min(6, photos.size) && x >= 47f + i * 199f && x <= 232f + i * 199f) {
                    photoFocus = i
                    photos.getOrNull(i)?.let { item ->
                        val active = activePhotos()
                        val idx = active.indexOfFirst { it.uri == item.uri }
                        if (idx >= 0) {
                            currentPhoto = idx
                            page = 0
                            preloadAroundCurrent()
                        }
                    }
                }
            }
        }
        savePrefs()
        invalidate()
    }

    private fun handleEditorTap(x: Float, y: Float) {
        val visibleCount = 6
        val first = (editorElement - 2).coerceIn(0, max(0, elementNames.size - visibleCount))

        if (x in 32f..352f && y in 120f..642f) {
            val slot = ((y - 120f) / 87f).toInt()
            val index = first + slot
            if (slot in 0 until visibleCount && index in elementNames.indices) {
                editorElement = index
                editorColumn = 0
                editorMoveMode = false
            }
            invalidate()
            return
        }

        if (x in 370f..918f && y in 64f..638f) {
            editorColumn = 1
            editorMoveMode = !editorMoveMode
            invalidate()
            return
        }

        if (x !in 940f..1248f) return
        val st = styles[editorElement]
        editorColumn = 2

        when {
            y in 126f..171f -> editorControl = 0
            y in 170f..218f -> {
                editorControl = 1
                st.font = (st.font + 1) % 4
            }
            y in 220f..268f -> {
                editorControl = 2
                val t = ((x - 1051f) / 126f).coerceIn(0f, 1f)
                st.size = 12f + t * 68f
            }
            y in 280f..330f -> {
                editorControl = 3
                val colors = listOf(Color.WHITE, Color.rgb(173,196,255), Color.rgb(255,224,112), Color.rgb(255,126,126), Color.rgb(137,230,173))
                val idx = colors.indexOf(st.color).coerceAtLeast(0)
                st.color = colors[(idx + 1) % colors.size]
            }
            y in 326f..378f -> {
                editorControl = 4
                st.x = (((x - 1051f) / 126f) * 100f).coerceIn(0f, 100f)
            }
            y in 387f..439f -> {
                editorControl = 5
                st.y = (((x - 1051f) / 126f) * 100f).coerceIn(0f, 100f)
            }
            y in 454f..506f -> {
                editorControl = 6
                st.align = (((x - 1048f) / 42f).toInt()).coerceIn(0, 2)
            }
            y in 516f..568f -> {
                editorControl = 7
                st.shadow = !st.shadow
            }
        }
        savePrefs()
        invalidate()
    }

    private fun setSliderFromTap(x: Float, minValue: Float, maxValue: Float): Float {
        val t = ((x - 730f) / 375f).coerceIn(0f, 1f)
        return minValue + (maxValue - minValue) * t
    }

    private fun handleSettingsTap(x: Float, y: Float) {
        if (x in 32f..352f && y in 116f..566f) {
            val i = ((y - 116f) / 55f).toInt().coerceIn(0, 7)
            settingsCategory = i
            settingsColumn = 0
            settingsControl = settingsControl.coerceIn(0, settingsControlMax())
            invalidate()
            return
        }

        if (x !in 380f..1248f || y !in 77f..613f) return
        settingsColumn = 1

        when (settingsCategory) {
            0 -> when {
                y in 112f..164f -> {
                    settingsControl = 0
                    durationSeconds = setSliderFromTap(x, 2f, 120f).toInt().coerceIn(2, 120)
                }
                y in 164f..214f -> {
                    settingsControl = 1
                    fixedImage = x >= 920f
                }
                y in 210f..262f -> { settingsControl = 2; loop = !loop }
                y in 255f..310f -> { settingsControl = 3; randomOrder = !randomOrder }
                y in 340f..452f -> {
                    val i = ((x - 402f) / 141f).toInt()
                    if (i in 0..5) {
                        settingsControl = 4
                        transitionIndex = i
                    }
                }
                y in 455f..515f -> {
                    settingsControl = 5
                    transitionSeconds = setSliderFromTap(x, .2f, 4f)
                }
                y in 505f..555f -> { settingsControl = 6; kenBurns = !kenBurns }
                y in 548f..600f -> {
                    settingsControl = 7
                    zoomLevel = (((x - 730f) / (380f / 3f)).toInt()).coerceIn(0, 2)
                }
            }

            1 -> {
                val visibleCount = 6
                val first = (settingsControl - 2).coerceIn(0, max(0, elementNames.size - visibleCount))
                val slot = ((y - 136f) / 70f).toInt()
                val idx = first + slot
                if (slot in 0 until visibleCount && idx in elementNames.indices) {
                    settingsControl = idx
                    toggleElement(idx)
                }
            }

            2 -> {
                val visibleCount = 6
                val first = (editorElement - 2).coerceIn(0, max(0, elementNames.size - visibleCount))
                val slot = ((y - 164f) / 64f).toInt()
                val idx = first + slot
                if (slot in 0 until visibleCount && idx in elementNames.indices) {
                    editorElement = idx
                    settingsControl = idx
                    page = 2
                    editorColumn = 0
                }
            }

            3 -> {
                val col = ((x - 402f) / 211f).toInt()
                val row = ((y - 142f) / 104f).toInt()
                val idx = row * 4 + col
                if (col in 0..3 && row in 0..3 && idx in transitions.indices) {
                    settingsControl = idx
                    transitionIndex = idx
                }
            }

            4 -> when {
                y in 130f..182f -> { settingsControl = 0; showDate = !showDate }
                y in 184f..238f -> { settingsControl = 1; dateFormatIndex = (dateFormatIndex + 1) % 3 }
                y in 240f..294f -> { settingsControl = 2; showTime = !showTime }
                y in 296f..350f -> { settingsControl = 3; time24h = !time24h }
                y in 352f..408f -> {
                    settingsControl = 4
                    showSeconds = !showSeconds
                    scheduleClock()
                }
            }

            5 -> when {
                y in 136f..190f -> { settingsControl = 0; showTemp = !showTemp }
                y in 192f..250f -> {
                    settingsControl = 1
                    tempCelsius = x < 920f
                    loadWeather()
                }
                y in 252f..316f -> { settingsControl = 2; onWeatherLocation() }
            }

            6 -> when {
                y in 138f..258f && x < 800f -> { settingsControl = 0; onExactSource() }
                y in 138f..258f && x >= 800f -> { settingsControl = 1; onPickPhotos() }
                y in 300f..350f -> { settingsControl = 2; albumSort = (albumSort + 1) % 2 }
                y in 356f..414f -> { settingsControl = 3; onAlbumSearch() }
                y in 416f..474f -> { settingsControl = 4; toggleAllAlbums() }
                y in 545f..610f -> { settingsControl = 5; clearAllMasks() }
            }

            7 -> if (advancedRulesOpen) {
                if (x >= 1030f && y in 75f..125f) {
                    advancedRulesOpen = false
                    settingsControl = 0
                } else {
                    val control = (((y - 120f) / 58f).toInt()).coerceIn(0, 7)
                    settingsControl = control
                    adjustAlbumRule(1)
                }
            } else when {
                x >= 1020f && y in 72f..122f -> {
                    advancedRulesOpen = true
                    settingsControl = 0
                }
                y in 112f..166f -> { settingsControl = 0; imageMode = (imageMode + 1) % 4 }
                y in 168f..220f -> { settingsControl = 1; gridSnap = !gridSnap }
                y in 221f..274f -> { settingsControl = 2; oledProtection = !oledProtection }
                y in 275f..327f -> { settingsControl = 3; overlaysAutoHide = !overlaysAutoHide }
                y in 328f..380f -> {
                    settingsControl = 4
                    autoStartMinutes = setSliderFromTap(x, 0f, 60f).toInt().coerceIn(0, 60)
                }
                y in 381f..433f -> { settingsControl = 5; startDirectly = !startDirectly }
                y in 434f..486f -> { settingsControl = 6; favoritesOnly = !favoritesOnly }
                y in 487f..535f -> { settingsControl = 7; applyPreset((layoutPreset + 1) % 4) }
                y in 536f..578f && x < 1000f -> { settingsControl = 8; onExportSettings() }
                y in 536f..578f && x >= 1000f -> { settingsControl = 9; onImportSettings() }
                y in 579f..626f -> { settingsControl = 10; videoSound = !videoSound; syncVideoPlayback() }
            }
        }

        savePrefs()
        scheduleInactivity()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        markInteraction()

        val x = event.x / (width / 1280f)
        val y = event.y / (height / 720f)

        if (quickMenuVisible) {
            if (x in 855f..1225f && y in 241f..476f) {
                quickMenuIndex = (((y - 241f) / 47f).toInt()).coerceIn(0, 4)
                activateQuickMenu()
            } else {
                quickMenuVisible = false
                invalidate()
            }
            return true
        }

        if (infoPanelVisible) {
            infoPanelVisible = false
            invalidate()
            return true
        }

        if (slideshow) {
            if (x < 426f) slideshowNext(-1)
            else if (x > 854f) slideshowNext(1)
            else togglePause()
            return true
        }

        if (y >= 638f && x >= 218f && x <= 1090f) {
            page = ((x - 218f) / 218f).toInt().coerceIn(0, 3)
            navFocus = false
            editorMoveMode = false
            invalidate()
            return true
        }

        when (page) {
            0 -> {
                if (x < 180f) previewNext(-1)
                else if (x > 1100f) previewNext(1)
                else if (activePhotos().isNotEmpty()) startSlideshow()
                else page = 1
                invalidate()
            }
            1 -> handlePhotosTap(x, y)
            2 -> handleEditorTap(x, y)
            3 -> handleSettingsTap(x, y)
        }

        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        inactivityHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        transitionAnimator?.cancel()
        onVideoPlayback(null, videoSound)
        executor.shutdownNow()
    }
}
