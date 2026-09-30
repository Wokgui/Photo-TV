package fr.wokgui.phototv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.util.Log
import android.media.MediaMetadataRetriever
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class PhotoTvView(
    context: Context,
    private val onExactSource: () -> Unit,
    private val onFolderSource: () -> Unit = {},
    private val onPickPhotos: () -> Unit,
    private val onNetworkSource: () -> Unit = {},
    private val onWeatherLocation: () -> Unit = {},
    private val onExportSettings: () -> Unit = {},
    private val onImportSettings: () -> Unit = {},
    private val onAlbumSearch: () -> Unit = {},
    private val onVideoPlayback: (Uri?, Boolean) -> Unit = { _, _ -> },
    private val onVideoPause: (Boolean) -> Unit = {},
    private val supportsVideoPlayback: Boolean = false,
    private val automationMode: Boolean = false
) : View(context) {

    companion object {
        private const val SETTINGS_SCHEMA_VERSION = 3
    }

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

    private data class EditorSnapshot(
        val styles: List<Style>,
        val showDate: Boolean,
        val showTime: Boolean,
        val showTemp: Boolean,
        val layoutPreset: Int
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
    private val highResExecutor = Executors.newSingleThreadExecutor()
    private val bitmapCache = LinkedHashMap<String, Bitmap>(32, .75f, true)
    private var bitmapCacheBytes = 0L
    private val bitmapCacheLimitBytes: Long by lazy {
        val adaptive = Runtime.getRuntime().maxMemory() / 8L
        adaptive.coerceIn(24L * 1024L * 1024L, 96L * 1024L * 1024L)
    }
    private val highResCache = LinkedHashMap<String, Bitmap>(8, .75f, true)
    private val highResLoading = linkedSetOf<String>()
    private var highResCacheBytes = 0L
    private val highResCacheLimitBytes: Long by lazy {
        val adaptive = Runtime.getRuntime().maxMemory() / 5L
        adaptive.coerceIn(48L * 1024L * 1024L, 192L * 1024L * 1024L)
    }
    private val gifCache = LinkedHashMap<String, Movie>()
    private val gifLoading = linkedSetOf<String>()
    private var softSource: Bitmap? = null
    private var softBitmap: Bitmap? = null

    private val demoUrl =
        "https://commons.wikimedia.org/wiki/Special:Redirect/file/Oslo%20-%20Op%C3%A9ra%20-%20Ext%C3%A9rieur%2001.JPG?width=1800"
    private val mockAlbumUrls = listOf(
        demoUrl,
        "https://images.unsplash.com/photo-1513622470522-26c3c8a854bc?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1533154683836-84ea7a0bc310?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1501785888041-af3ef285b470?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1552053831-71594a27632d?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=800&q=80"
    )
    private val mockAlbumBitmaps = mutableMapOf<Int, Bitmap>()
    private var demoBitmap: Bitmap? = null

    private var library: List<PhotoItem> = emptyList()
    private var exactAlbums = false
    private var sourceName = "Démo"
    private var page = 0
    private var navFocus = false
    private var currentPhoto = 0
    private var resumeUri = ""
    private var resumeWasSlideshow = false
    private var resumeWasPaused = false
    private val resumeShuffleUris = mutableListOf<String>()
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
    private var nightModeEnabled = false
    private var nightStartHour = 22
    private var nightEndHour = 7
    private var nightDimPercent = 45
    private var nightHideOverlays = true
    private var temperatureC = Float.NaN
    private var feelsLikeC = Float.NaN
    private var forecastMinC = Float.NaN
    private var forecastMaxC = Float.NaN
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
    private val failedMediaUris = linkedSetOf<String>()
    private var decodeFailureCount = 0
    private var lastDecodeFailure = ""
    private val recentUris = java.util.ArrayDeque<String>()
    private var cacheHits = 0L
    private var cacheMisses = 0L
    private var decodeCount = 0L
    private var decodeTotalMs = 0L
    private var frameCount = 0L
    private var fpsWindowStarted = android.os.SystemClock.uptimeMillis()
    private var measuredFps = 0f
    private var lastAnimatedFrameNs = 0L
    private var animatedFrameCount = 0L
    private var slowFrameCount = 0L
    private val history = mutableListOf<Int>()
    private val shuffleBag = mutableListOf<Int>()
    private var quickMenuVisible = false
    private var quickMenuIndex = 0
    private var infoPanelVisible = false
    private var slideStartedAt = System.currentTimeMillis()
    private var inactivityToken = 0L
    private var editorMoveMode = false
    private var longActionLatched = false
    private var touchDraggingEditor = false
    private var editorGestureRecorded = false
    private val editorUndo = java.util.ArrayDeque<EditorSnapshot>()
    private val editorRedo = java.util.ArrayDeque<EditorSnapshot>()
    private var interactionDiagnostics = false
    private var diagnosticTouchX = -1f
    private var diagnosticTouchY = -1f
    private var layoutPreset = 0
    private var albumSearch = ""
    private var albumSort = 0
    private var videoSound = false
    private var remoteEnabled = false
    private var remoteToken = ""
    private var remoteServer: RemoteControlServer? = null
    private var remoteQrVisible = false
    private var remoteQrBitmap: Bitmap? = null
    private var remoteQrUrl = ""
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
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        loadDeterministicDemo()
        if (!automationMode) {
            loadDemo()
            loadWeather()
        } else {
            temperatureC = 17f
            feelsLikeC = 17f
            forecastMinC = 12f
            forecastMaxC = 19f
            weatherSummary = "Ciel dégagé"
            forecastLines.clear()
            forecastLines += listOf("Lun  12° / 19°", "Mar  11° / 18°", "Mer  13° / 20°")
        }
        updateRemoteServer()
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
        nightModeEnabled = prefs.getBoolean("night_mode", false)
        nightStartHour = prefs.getInt("night_start", 22).coerceIn(0, 23)
        nightEndHour = prefs.getInt("night_end", 7).coerceIn(0, 23)
        nightDimPercent = prefs.getInt("night_dim", 45).coerceIn(0, 85)
        nightHideOverlays = prefs.getBoolean("night_hide_overlays", true)
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
        remoteEnabled = prefs.getBoolean("remote_enabled", false)
        remoteToken = prefs.getString("remote_token", "").orEmpty().ifBlank {
            java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        }
        interactionDiagnostics = prefs.getBoolean("interaction_diagnostics", false)
        restoreAlbumRules(prefs.getString("album_rules", null))
        favoritesOnly = prefs.getBoolean("favorites_only", false)
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        hiddenAlbums.addAll(prefs.getStringSet("hidden_albums", emptySet()) ?: emptySet())
        excludedUris.addAll(prefs.getStringSet("excluded_uris", emptySet()) ?: emptySet())
        savedSelectedAlbums.addAll(prefs.getStringSet("selected_albums", emptySet()) ?: emptySet())
        currentPhoto = prefs.getInt("resume_index", 0)
        resumeUri = prefs.getString("resume_uri", "").orEmpty()
        resumeWasSlideshow = prefs.getBoolean("resume_slideshow", false)
        resumeWasPaused = prefs.getBoolean("resume_paused", false)
        runCatching {
            val arr = JSONArray(prefs.getString("resume_shuffle", "[]") ?: "[]")
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { resumeShuffleUris += it }
        }
        runCatching {
            val arr = JSONArray(prefs.getString("recent_uris", "[]") ?: "[]")
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { recentUris.addLast(it) }
        }
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
        savedSelectedAlbums.clear()
        savedSelectedAlbums.addAll(selectedAlbums)
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
            putBoolean("night_mode", nightModeEnabled)
            putInt("night_start", nightStartHour)
            putInt("night_end", nightEndHour)
            putInt("night_dim", nightDimPercent)
            putBoolean("night_hide_overlays", nightHideOverlays)
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
            putBoolean("remote_enabled", remoteEnabled)
            putString("remote_token", remoteToken)
            putBoolean("interaction_diagnostics", interactionDiagnostics)
            putString("album_rules", albumRulesJson().toString())
            putBoolean("favorites_only", favoritesOnly)
            putStringSet("favorites", HashSet(favorites))
            putStringSet("hidden_albums", HashSet(hiddenAlbums))
            putStringSet("excluded_uris", HashSet(excludedUris))
            putStringSet("selected_albums", HashSet(selectedAlbums))
            putInt("resume_index", currentPhoto)
            putString("resume_uri", currentItem()?.uri?.toString().orEmpty())
            putBoolean("resume_slideshow", slideshow)
            putBoolean("resume_paused", paused)
            putString("resume_shuffle", JSONArray().apply {
                val items = activePhotos()
                shuffleBag.forEach { index -> items.getOrNull(index)?.uri?.toString()?.let { put(it) } }
            }.toString())
            putString("recent_uris", JSONArray().apply { recentUris.forEach { put(it) } }.toString())
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
            val result = runCatching {
                val encoded = if (weatherLocation.isBlank()) "" else java.net.URLEncoder.encode(weatherLocation, "UTF-8")
                val url = if (encoded.isBlank()) {
                    "https://wttr.in/?format=j1&lang=fr"
                } else {
                    "https://wttr.in/$encoded?format=j1&lang=fr"
                }
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "PhotoTV/" + appVersionName())
                }
                val json = connection.getInputStream().bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val current = root.optJSONArray("current_condition")?.optJSONObject(0)
                current?.optString("temp_C")?.toFloatOrNull()?.let { temperatureC = it }
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
                forecastMinC = today?.optString("mintempC")?.toFloatOrNull() ?: Float.NaN
                forecastMaxC = today?.optString("maxtempC")?.toFloatOrNull() ?: Float.NaN

                forecastLines.clear()
                if (weather != null) {
                    for (i in 0 until min(3, weather.length())) {
                        val day = weather.optJSONObject(i) ?: continue
                        val rawDate = day.optString("date")
                        val label = runCatching {
                            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(rawDate)
                            SimpleDateFormat("EEE dd/MM", Locale.FRANCE).format(parsed ?: Date())
                        }.getOrDefault(rawDate)
                        val minC = day.optString("mintempC").toFloatOrNull() ?: Float.NaN
                        val maxC = day.optString("maxtempC").toFloatOrNull() ?: Float.NaN
                        forecastLines += "$label  ${temperatureLabel(minC, false)} / ${temperatureLabel(maxC, false)}"
                    }
                }
            }
            if (result.isFailure && !temperatureC.isFinite()) {
                weatherSummary = "Météo indisponible"
                forecastLines.clear()
            }
            postInvalidate()
        }
    }

    private fun loadDeterministicDemo() {
        mockAlbumBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        mockAlbumBitmaps.clear()

        val palettes = listOf(
            intArrayOf(Color.rgb(26, 55, 77), Color.rgb(89, 127, 145), Color.rgb(12, 27, 41)),
            intArrayOf(Color.rgb(26, 45, 70), Color.rgb(113, 91, 77), Color.rgb(12, 24, 39)),
            intArrayOf(Color.rgb(45, 60, 45), Color.rgb(111, 130, 102), Color.rgb(23, 34, 29)),
            intArrayOf(Color.rgb(40, 56, 74), Color.rgb(80, 118, 144), Color.rgb(18, 30, 44)),
            intArrayOf(Color.rgb(62, 49, 43), Color.rgb(137, 101, 71), Color.rgb(28, 25, 27)),
            intArrayOf(Color.rgb(28, 64, 79), Color.rgb(88, 143, 160), Color.rgb(12, 34, 43))
        )

        palettes.forEachIndexed { index, colors ->
            val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val gradient = LinearGradient(
                0f, 0f, 1280f, 720f,
                colors[0], colors[1],
                Shader.TileMode.CLAMP
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
            canvas.drawRect(0f, 0f, 1280f, 720f, paint)

            paint.shader = null
            paint.color = colors[2]
            val path = Path().apply {
                moveTo(0f, 590f)
                lineTo(300f, 310f + index * 18f)
                lineTo(520f, 520f)
                lineTo(760f, 270f + index * 12f)
                lineTo(1010f, 500f)
                lineTo(1280f, 330f + index * 14f)
                lineTo(1280f, 720f)
                lineTo(0f, 720f)
                close()
            }
            canvas.drawPath(path, paint)

            paint.color = Color.argb(90, 255, 255, 255)
            canvas.drawCircle(1040f - index * 38f, 135f + index * 14f, 45f, paint)

            mockAlbumBitmaps[index] = bmp
        }
        demoBitmap = mockAlbumBitmaps[0]
    }

    private fun loadDemo() {
        mockAlbumUrls.forEachIndexed { index, url ->
            val req = ImageRequest.Builder(context)
                .data(url)
                .size(900, 650)
                .target(
                    onSuccess = { d: Drawable ->
                        val bmp = d.toBitmap()
                        mockAlbumBitmaps[index] = bmp
                        if (index == 0) demoBitmap = bmp
                        invalidate()
                    }
                )
                .build()
            context.imageLoader.enqueue(req)
        }
    }

    fun exportSettingsJson(): String {
        val root = JSONObject()
        root.put("version", SETTINGS_SCHEMA_VERSION)
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
        root.put("nightModeEnabled", nightModeEnabled)
        root.put("nightStartHour", nightStartHour)
        root.put("nightEndHour", nightEndHour)
        root.put("nightDimPercent", nightDimPercent)
        root.put("nightHideOverlays", nightHideOverlays)
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
        root.put("networkSources", NetworkSourceStore.exportJson(context))
        val networkPrefs = context.getSharedPreferences("photo_tv_network_settings", Context.MODE_PRIVATE)
        root.put("networkCacheMb", networkPrefs.getInt("cache_mb", 512))
        root.put("networkCacheTtlHours", networkPrefs.getInt("ttl_hours", 24))
        root.put("networkRefreshMinutes", networkPrefs.getInt("refresh_minutes", 15))

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
            val schemaVersion = root.optInt("version", 1)
            require(schemaVersion in 1..SETTINGS_SCHEMA_VERSION) {
                "Version de réglages non prise en charge: $schemaVersion"
            }
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
            nightModeEnabled = root.optBoolean("nightModeEnabled", nightModeEnabled)
            nightStartHour = root.optInt("nightStartHour", nightStartHour).coerceIn(0, 23)
            nightEndHour = root.optInt("nightEndHour", nightEndHour).coerceIn(0, 23)
            nightDimPercent = root.optInt("nightDimPercent", nightDimPercent).coerceIn(0, 85)
            nightHideOverlays = root.optBoolean("nightHideOverlays", nightHideOverlays)
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
            root.optJSONArray("networkSources")?.let { NetworkSourceStore.importJson(context, it) }
            val networkPrefs = context.getSharedPreferences("photo_tv_network_settings", Context.MODE_PRIVATE)
            val importedCacheMb = root.optInt("networkCacheMb", networkPrefs.getInt("cache_mb", 512)).coerceIn(64, 2048)
            val importedTtl = root.optInt("networkCacheTtlHours", networkPrefs.getInt("ttl_hours", 24)).coerceIn(1, 168)
            val importedRefresh = root.optInt("networkRefreshMinutes", networkPrefs.getInt("refresh_minutes", 15)).coerceIn(5, 60)
            networkPrefs.edit()
                .putInt("cache_mb", importedCacheMb)
                .putInt("ttl_hours", importedTtl)
                .putInt("refresh_minutes", importedRefresh)
                .apply()
            NetworkLibrary.configureCache(importedCacheMb, importedTtl)

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
            val availableAlbums = library.flatMap { it.albums }.distinct()
            if (availableAlbums.isNotEmpty()) {
                selectedAlbums.retainAll(availableAlbums.toSet())
                if (selectedAlbums.isEmpty()) selectedAlbums.addAll(availableAlbums)
                hiddenAlbums.retainAll(availableAlbums.toSet())
            }

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
        val now = java.util.Calendar.getInstance()
        return item.albums
            .asSequence()
            .filter {
                selectedAlbums.contains(it) &&
                    !hiddenAlbums.contains(it) &&
                    albumAllowed(it, now)
            }
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
        loadingText = message.takeIf { it.isNotBlank() }
        invalidate()
    }

    fun setAutomationPage(index: Int) {
        slideshow = false
        paused = false
        quickMenuVisible = false
        infoPanelVisible = false
        page = index.coerceIn(0, 3)
        navFocus = false
        when (page) {
            1 -> {
                photosRow = 0
                sourceFocus = 0
            }
            2 -> {
                editorElement = 0
                editorControl = 0
                editorColumn = 0
            }
            3 -> {
                settingsCategory = 0
                settingsControl = 0
                settingsColumn = 0
            }
        }
        if (automationMode) Log.i("PhotoTVState", automationStateDescription())
        invalidate()
    }

    fun automationStateDescription(): String =
        "PhotoTV page=$page navFocus=$navFocus photosRow=$photosRow sourceFocus=$sourceFocus " +
            "albumFocus=$albumFocus photoFocus=$photoFocus editorColumn=$editorColumn " +
            "editorElement=$editorElement editorControl=$editorControl editorMoveMode=$editorMoveMode " +
            "settingsCategory=$settingsCategory settingsColumn=$settingsColumn settingsControl=$settingsControl " +
            "rulesOpen=$advancedRulesOpen diagnostics=$interactionDiagnostics " +
            "slideshow=$slideshow paused=$paused quickMenu=$quickMenuVisible " +
            "decodeFailures=$decodeFailureCount failedMedia=${failedMediaUris.size} " +
            "night=${isNightModeActive()} memory=${memoryDiagnostics()}"

    fun setLibrary(items: List<PhotoItem>, exactAlbums: Boolean, sourceName: String = if (exactAlbums) "Google Photos / Takeout" else "Sélection") {
        synchronized(bitmapCache) {
            bitmapCache.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
            bitmapCache.clear()
            bitmapCacheBytes = 0L
        }
        synchronized(highResCache) {
            highResCache.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
            highResCache.clear()
            highResLoading.clear()
            highResCacheBytes = 0L
        }
        synchronized(gifCache) {
            gifCache.clear()
            gifLoading.clear()
        }
        softSource = null
        softBitmap?.let { if (!it.isRecycled) it.recycle() }
        softBitmap = null

        failedMediaUris.clear()
        decodeFailureCount = 0
        lastDecodeFailure = ""
        library = items
        this.exactAlbums = exactAlbums
        this.sourceName = sourceName
        selectedAlbums.clear()
        val allAlbums = items.flatMap { it.albums }.distinct()
        if (savedSelectedAlbums.isNotEmpty()) {
            selectedAlbums.addAll(allAlbums.filter { savedSelectedAlbums.contains(it) })
        }
        if (selectedAlbums.isEmpty()) selectedAlbums.addAll(allAlbums)
        currentPhoto = prefs.getInt("resume_index", 0).coerceAtLeast(0)
        val activeNow = activePhotos()
        if (resumeUri.isNotBlank()) {
            val restored = activeNow.indexOfFirst { it.uri.toString() == resumeUri }
            if (restored >= 0) currentPhoto = restored
        }
        albumFocus = 0
        photoFocus = 0
        loadingText = null
        preload(items.take(48).map { it.uri })
        val count = activePhotos().size
        if (count > 0) currentPhoto = currentPhoto.coerceIn(0, count - 1)
        invalidate()
        scheduleInactivity()
        if (resumeWasSlideshow && count > 0) {
            postDelayed({
                if (!slideshow) {
                    startSlideshow()
                    if (resumeShuffleUris.isNotEmpty()) {
                        val active = activePhotos()
                        val restored = resumeShuffleUris.mapNotNull { uri ->
                            active.indexOfFirst { it.uri.toString() == uri }.takeIf { it >= 0 }
                        }.distinct().filter { it != currentPhoto }
                        if (restored.isNotEmpty()) {
                            shuffleBag.clear()
                            shuffleBag.addAll(restored)
                        }
                    }
                    if (resumeWasPaused) {
                        paused = true
                        scheduleSlideshow()
                        syncVideoPlayback()
                        invalidate()
                    }
                }
                resumeWasSlideshow = false
                resumeShuffleUris.clear()
            }, 450)
        } else if (startDirectly && count > 0) {
            postDelayed({ if (!slideshow) startSlideshow() }, 450)
        }
    }

    fun startAsDream(items: List<PhotoItem>, exactAlbums: Boolean, sourceName: String) {
        setLibrary(items, exactAlbums = exactAlbums, sourceName = sourceName)
        if (activePhotos().isNotEmpty()) {
            postDelayed({ startSlideshow() }, 150)
        }
    }

    private fun preload(uris: List<Uri>) {
        uris.distinct().forEach { uri ->
            val key = uri.toString()
            if (failedMediaUris.contains(key) || bitmapCache.containsKey(key)) return@forEach
            executor.execute {
                val bmp = decodeThumb(uri)
                if (bmp != null) {
                    synchronized(bitmapCache) {
                        bitmapCache.remove(key)?.let { old ->
                            bitmapCacheBytes -= old.allocationByteCount.toLong()
                            if (!old.isRecycled) old.recycle()
                        }
                        bitmapCache[key] = bmp
                        bitmapCacheBytes += bmp.allocationByteCount.toLong()
                        while (bitmapCacheBytes > bitmapCacheLimitBytes && bitmapCache.size > 1) {
                            val first = bitmapCache.entries.firstOrNull() ?: break
                            bitmapCacheBytes -= first.value.allocationByteCount.toLong()
                            if (!first.value.isRecycled) first.value.recycle()
                            bitmapCache.remove(first.key)
                        }
                    }
                    postInvalidate()
                } else {
                    registerDecodeFailure(uri, "Image illisible ou format non pris en charge")
                }
            }
        }
    }

    private fun registerDecodeFailure(uri: Uri, reason: String) {
        val key = uri.toString()
        val added = synchronized(failedMediaUris) { failedMediaUris.add(key) }
        if (!added) return
        decodeFailureCount++
        lastDecodeFailure = reason
        Log.w("PhotoTVDecode", "$reason: $key")
        post {
            val items = activePhotos()
            if (items.isEmpty()) {
                if (slideshow) stopSlideshow()
            } else {
                currentPhoto = currentPhoto.coerceIn(0, items.lastIndex)
                if (slideshow) {
                    slideStartedAt = System.currentTimeMillis()
                    preloadAroundCurrent()
                    syncVideoPlayback()
                    scheduleSlideshow()
                }
            }
            invalidate()
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
                openMediaStream(uri)?.use { Movie.decodeStream(it) }
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

    private fun openMediaStream(uri: Uri): java.io.InputStream? =
        if (NetworkLibrary.isNetworkUri(uri)) NetworkLibrary.open(context, uri)
        else context.contentResolver.openInputStream(uri)

    private fun decodeThumb(uri: Uri): Bitmap? {
        return runCatching {
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("video/")) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: demoBitmap
                } finally {
                    runCatching { r.release() }
                }
            } else {
                decodeBitmap(uri, 1200, 900)
            }
        }.getOrNull()
    }

    private fun decodeBitmap(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        val decodeStarted = android.os.SystemClock.elapsedRealtime()
        try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openMediaStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth &&
                bounds.outHeight / (sample * 2) >= targetHeight
            ) {
                sample *= 2
            }
            val opt = BitmapFactory.Options().apply {
                inSampleSize = max(1, sample)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = openMediaStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opt)
            }
            if (decoded != null) return applyExifOrientation(uri, decoded)
        }

        if (!NetworkLibrary.isNetworkUri(uri) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = runCatching {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val srcW = info.size.width.coerceAtLeast(1)
                    val srcH = info.size.height.coerceAtLeast(1)
                    val scale = min(
                        1f,
                        min(
                            targetWidth.toFloat() / srcW.toFloat(),
                            targetHeight.toFloat() / srcH.toFloat()
                        )
                    )
                    val outW = max(1, (srcW * scale).toInt())
                    val outH = max(1, (srcH * scale).toInt())
                    decoder.setTargetSize(outW, outH)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }

        return null
        } finally {
            decodeCount++
            decodeTotalMs += (android.os.SystemClock.elapsedRealtime() - decodeStarted).coerceAtLeast(0L)
        }
    }

    private fun highResTarget(): Pair<Int, Int> {
        val displayW = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val displayH = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val memoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        val maxW = when {
            memoryMb >= 768L -> 3840
            memoryMb >= 384L -> 3200
            else -> 2560
        }
        val maxH = when {
            maxW >= 3840 -> 2160
            maxW >= 3200 -> 1800
            else -> 1440
        }
        return min(displayW.coerceAtLeast(1280), maxW) to
            min(displayH.coerceAtLeast(720), maxH)
    }

    private fun requestHighRes(uri: Uri) {
        val key = uri.toString()
        if (failedMediaUris.contains(key)) return
        synchronized(highResCache) {
            if (highResCache.containsKey(key) || highResLoading.contains(key)) return
            highResLoading += key
        }
        val (targetW, targetH) = highResTarget()
        highResExecutor.execute {
            val bmp = runCatching { decodeBitmap(uri, targetW, targetH) }.getOrNull()
            synchronized(highResCache) {
                highResLoading.remove(key)
                if (bmp != null) {
                    highResCache.remove(key)?.let { old ->
                        highResCacheBytes -= old.allocationByteCount.toLong()
                        if (!old.isRecycled) old.recycle()
                    }
                    highResCache[key] = bmp
                    highResCacheBytes += bmp.allocationByteCount.toLong()
                    while (highResCacheBytes > highResCacheLimitBytes && highResCache.size > 1) {
                        val first = highResCache.entries.firstOrNull() ?: break
                        highResCacheBytes -= first.value.allocationByteCount.toLong()
                        if (!first.value.isRecycled) first.value.recycle()
                        highResCache.remove(first.key)
                    }
                }
            }
            if (bmp != null) postInvalidate()
        }
    }

    private fun applyExifOrientation(uri: Uri, source: Bitmap): Bitmap {
        val orientation = runCatching {
            openMediaStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) return source

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source && !source.isRecycled) source.recycle()
        return rotated
    }

    private fun activePhotos(): List<PhotoItem> {
        if (library.isEmpty()) return emptyList()
        val now = java.util.Calendar.getInstance()
        return library.filter { item ->
            item.albums.any {
                selectedAlbums.contains(it) &&
                    !hiddenAlbums.contains(it) &&
                    albumAllowed(it, now)
            } &&
                !excludedUris.contains(item.uri.toString()) &&
                !sessionExcludedUris.contains(item.uri.toString()) &&
                !failedMediaUris.contains(item.uri.toString()) &&
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
            val high = synchronized(highResCache) { highResCache[key] }
            if (high != null && !high.isRecycled) {
                cacheHits++
                return high
            }
            val thumb = synchronized(bitmapCache) { bitmapCache[key] }
            if (thumb != null && !thumb.isRecycled) {
                cacheHits++
                requestHighRes(item.uri)
                thumb
            } else {
                cacheMisses++
                requestHighRes(item.uri)
                preload(listOf(item.uri))
                demoBitmap
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        synchronized(highResCache) {
            highResCache.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
            highResCache.clear()
            highResLoading.clear()
            highResCacheBytes = 0L
        }
        currentItem()?.let { requestHighRes(it.uri) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frameCount++
        val animated = slideshow && (transitionProgress < 1f || kenBurns)
        if (animated) {
            val nowNs = System.nanoTime()
            if (lastAnimatedFrameNs > 0L) {
                val frameMs = (nowNs - lastAnimatedFrameNs) / 1_000_000.0
                if (frameMs > 34.0) slowFrameCount++
            }
            lastAnimatedFrameNs = nowNs
            animatedFrameCount++
        } else {
            lastAnimatedFrameNs = 0L
        }
        val nowFps = android.os.SystemClock.uptimeMillis()
        val elapsedFps = nowFps - fpsWindowStarted
        if (elapsedFps >= 1000L) {
            measuredFps = frameCount * 1000f / elapsedFps.toFloat()
            frameCount = 0
            fpsWindowStarted = nowFps
        }
        contentDescription = automationStateDescription()
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

        if (interactionDiagnostics) drawInteractionDiagnostics(canvas)
        loadingText?.let { drawLoading(canvas, it) }
        if (remoteQrVisible) drawRemoteQrOverlay(canvas)
        canvas.restore()
    }

    private fun ensureRemoteQr(): Bitmap? {
        val url = remoteServer?.url().orEmpty()
        if (url.isBlank()) return null
        if (remoteQrBitmap != null && remoteQrUrl == url && remoteQrBitmap?.isRecycled == false) return remoteQrBitmap

        remoteQrBitmap?.let { if (!it.isRecycled) it.recycle() }
        remoteQrUrl = url
        val matrix = runCatching {
            QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 240, 240)
        }.getOrNull() ?: return null

        remoteQrBitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bmp ->
            for (yy in 0 until matrix.height) {
                for (xx in 0 until matrix.width) {
                    bmp.setPixel(xx, yy, if (matrix[xx, yy]) Color.BLACK else Color.WHITE)
                }
            }
        }
        return remoteQrBitmap
    }

    private fun showRemoteQrIfAvailable() {
        remoteQrVisible = remoteEnabled
        if (remoteQrVisible) ensureRemoteQr()
        invalidate()
    }

    private fun drawRemoteQrOverlay(c: Canvas) {
        fill(c, 0f, 0f, 1280f, 720f, Color.argb(190, 0, 0, 0))
        round(c, 360f, 120f, 920f, 610f, 20f, Color.rgb(12, 25, 39))
        strokeRound(c, 360f, 120f, 920f, 610f, 20f, Color.rgb(57, 105, 151), 1.5f)
        text(c, "Télécommande téléphone", 640f, 165f, 24f, Color.WHITE, 1, 1)
        text(c, "Scannez ce QR code avec le téléphone connecté au même réseau.", 640f, 202f, 13f, Color.rgb(190, 204, 222), 0, 1)

        val qr = ensureRemoteQr()
        if (qr != null) {
            c.drawBitmap(qr, null, RectF(500f, 230f, 780f, 510f), imagePaint)
            ellipsizedText(c, remoteQrUrl, 640f, 548f, 470f, 11f, Color.rgb(150, 195, 235), 0, 1)
        } else {
            text(c, "Connexion réseau locale indisponible", 640f, 365f, 16f, Color.rgb(255, 183, 120), 1, 1)
        }
        text(c, "OK, Retour ou appui sur l’écran pour fermer", 640f, 580f, 12f, Color.rgb(163, 179, 198), 0, 1)
    }

    private fun drawInteractionDiagnostics(c: Canvas) {
        val cyan = Color.rgb(60, 205, 255)
        val green = Color.rgb(89, 230, 145)
        val amber = Color.rgb(255, 193, 84)
        val magenta = Color.rgb(232, 103, 255)

        fun box(l: Float, t: Float, r: Float, b: Float, label: String, color: Int = cyan) {
            round(c, l, t, r, b, 4f, Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)))
            strokeRound(c, l, t, r, b, 4f, color, 1.5f)
            text(c, label, l + 4f, t + 13f, 9f, color, 1)
        }

        fill(c, 8f, 8f, 360f, 34f, Color.argb(215, 2, 13, 25))
        val pointer = if (diagnosticTouchX >= 0f) {
            " • x=${diagnosticTouchX.toInt()} y=${diagnosticTouchY.toInt()}"
        } else ""
        text(c, "DIAGNOSTIC • zones interactives$pointer", 16f, 26f, 11f, Color.WHITE, 1)

        if (slideshow) {
            box(0f, 0f, 426f, 720f, "Précédent", cyan)
            box(426f, 0f, 854f, 720f, "Pause / reprise", green)
            box(854f, 0f, 1280f, 720f, "Suivant", cyan)
            return
        }

        when (page) {
            0 -> {
                box(45f, 305f, 150f, 405f, "Flèche précédente", cyan)
                box(180f, 90f, 1100f, 625f, "Ouvrir diaporama", green)
                box(1130f, 305f, 1235f, 405f, "Flèche suivante", cyan)
            }
            1 -> {
                box(47f, 108f, 435f, 210f, "Google / Takeout", green)
                box(450f, 108f, 812f, 210f, "Dossier local", green)
                box(828f, 108f, 1238f, 210f, "Sélection fichiers", green)
                repeat(6) { i ->
                    val x = 47f + i * 199f
                    box(x, 270f, x + 185f, 468f, "Album ${i + 1}", amber)
                    box(x, 522f, x + 185f, 626f, "Photo ${i + 1}", magenta)
                }
            }
            2 -> {
                repeat(5) { i ->
                    val y = 120f + i * 87f
                    box(32f, y, 352f, y + 78f, "Métadonnée ${i + 1}", amber)
                }
                box(370f, 64f, 918f, 638f, "Déplacer l'élément", green)
                box(956f, 131f, 1232f, 166f, "Texte", cyan)
                box(1048f, 175f, 1232f, 210f, "Police", cyan)
                box(956f, 220f, 1232f, 268f, "Taille", cyan)
                box(956f, 280f, 1232f, 330f, "Couleur", cyan)
                box(956f, 326f, 1232f, 378f, "Position X", cyan)
                box(956f, 387f, 1232f, 439f, "Position Y", cyan)
                box(1048f, 454f, 1174f, 506f, "Alignement", cyan)
                box(956f, 516f, 1232f, 568f, "Ombre", cyan)
                repeat(4) { i ->
                    val x = 956f + i * 67f
                    box(x, 584f, x + 62f, 618f, listOf("Annuler", "Rétablir", "Réinit.", "Tout")[i], magenta)
                }
            }
            else -> {
                repeat(8) { i ->
                    val y = 116f + i * 55f
                    box(32f, y, 352f, y + 50f, "Catégorie ${i + 1}", amber)
                }
                if (settingsCategory == 7 && !advancedRulesOpen) {
                    box(825f, 85f, 1030f, 123f, "Zones diagnostic", magenta)
                    box(1045f, 85f, 1235f, 123f, "Règles album", magenta)
                } else {
                    box(380f, 77f, 1248f, 613f, "Contrôles du panneau", cyan)
                }
            }
        }

        repeat(4) { i ->
            val x = 218f + i * 218f
            box(x, 644f, x + 218f, 704f, listOf("Aperçu", "Photos", "Éditeur", "Réglages")[i], green)
        }
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
        ellipsizedText(c, title, 78f, 568f, 930f, 31f, Color.WHITE, 1)
        ellipsizedText(c, album, 78f, 607f, 930f, 19f, Color.WHITE, 0)

        val count = activePhotos().size
        val countText = if (count > 0) "${currentPhoto + 1} / $count" else "3 / 142"
        text(c, countText, 1215f, 607f, 15f, Color.WHITE, 0, 2)
    }

    private fun windowStart(index: Int, total: Int, pageSize: Int = 6): Int {
        if (total <= pageSize) return 0
        val safe = index.coerceIn(0, total - 1)
        return (safe / pageSize) * pageSize
    }

    private fun drawPhotos(c: Canvas) {
        drawAppBackground(c)
        drawBrand(c, 58f, 20f, "PHOTOS ET ALBUMS")

        val sourceY = 108f
        drawSourceCard(c, 47f, sourceY, 388f, 102f, "Google Photos", "Se connecter", 0, sourceFocus == 0 && !navFocus, true)
        drawSourceCard(c, 450f, sourceY, 362f, 102f, "Choisir un dossier", "Stockage local", 1, sourceFocus == 1 && !navFocus, false)
        drawSourceCard(c, 828f, sourceY, 410f, 102f, "Sélectionner des photos", "Choisir plusieurs fichiers", 2, sourceFocus == 2 && !navFocus, false)

        val albums = albumPairs()
        val albumsTitle = if (library.isEmpty() || exactAlbums) "Mes albums Google Photos" else "Albums et dossiers"
        text(c, albumsTitle, 45f, 250f, 19f, Color.WHITE, 1)
        text(c, "${albums.size} albums", 1235f, 250f, 13f, Color.rgb(186, 196, 210), 0, 2)

        val albumY = 270f
        val cardW = 185f
        val cardH = 198f
        val gap = 14f
        if (albums.isNotEmpty()) albumFocus = albumFocus.coerceIn(0, albums.lastIndex)
        val albumStart = windowStart(albumFocus, albums.size)
        albums.drop(albumStart).take(6).forEachIndexed { slot, pair ->
            val absoluteIndex = albumStart + slot
            val x = 47f + slot * (cardW + gap)
            val focused = photosRow == 1 && albumFocus == absoluteIndex && !navFocus
            drawAlbumCard(c, x, albumY, cardW, cardH, pair.first, pair.second, absoluteIndex, focused)
        }

        text(c, "Photos de l'album sélectionné", 45f, 500f, 19f, Color.WHITE, 1)
        val thumbs = currentAlbumPhotos()
        if (thumbs.isNotEmpty()) photoFocus = photoFocus.coerceIn(0, thumbs.lastIndex)
        val photoStart = windowStart(photoFocus, thumbs.size)
        val py = 522f
        val tw = 185f
        thumbs.drop(photoStart).take(6).forEachIndexed { slot, item ->
            val absoluteIndex = photoStart + slot
            val x = 47f + slot * (tw + gap)
            val focused = photosRow == 2 && photoFocus == absoluteIndex && !navFocus
            drawPhotoThumb(c, x, py, tw, 104f, item, focused, slot)
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

        return when {
            library.isEmpty() && albumSort == 0 -> filtered
            albumSort == 1 -> filtered.sortedByDescending { it.second }
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
        val panelName = elementNames.getOrElse(editorElement) { "Élément" }
            .replace(" de la photo", "")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.FRANCE) else it.toString() }
        text(c, "Paramètres : $panelName", x + 16f, y + 28f, 15f, Color.WHITE, 1)
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

        val actionY = y + 520f
        val actionW = 62f
        val actionGap = 5f
        val actions = listOf("↶", "↷", "Réinit.", "Tout")
        actions.forEachIndexed { i, label ->
            val ax = x + 16f + i * (actionW + actionGap)
            controlBox(
                c,
                ax,
                actionY,
                actionW,
                34f,
                label,
                editorControl == 8 + i && editorColumn == 2 && !navFocus
            )
        }
    }

    private fun currentTextForElement(): String =
        metadataValues().getOrElse(editorElement) { "—" }

    private fun captureEditorSnapshot(): EditorSnapshot =
        EditorSnapshot(
            styles = styles.map { it.copy() },
            showDate = showDate,
            showTime = showTime,
            showTemp = showTemp,
            layoutPreset = layoutPreset
        )

    private fun restoreEditorSnapshot(snapshot: EditorSnapshot) {
        snapshot.styles.forEachIndexed { i, st ->
            if (i in styles.indices) {
                styles[i].size = st.size
                styles[i].x = st.x
                styles[i].y = st.y
                styles[i].font = st.font
                styles[i].color = st.color
                styles[i].align = st.align
                styles[i].shadow = st.shadow
                styles[i].visible = st.visible
            }
        }
        showDate = snapshot.showDate
        showTime = snapshot.showTime
        showTemp = snapshot.showTemp
        layoutPreset = snapshot.layoutPreset
    }

    private fun recordEditorState() {
        val snapshot = captureEditorSnapshot()
        val last = editorUndo.peekLast()
        if (last == snapshot) return
        editorUndo.addLast(snapshot)
        while (editorUndo.size > 60) editorUndo.removeFirst()
        editorRedo.clear()
    }

    private fun undoEditor() {
        if (editorUndo.isEmpty()) return
        editorRedo.addLast(captureEditorSnapshot())
        restoreEditorSnapshot(editorUndo.removeLast())
        savePrefs()
        invalidate()
    }

    private fun redoEditor() {
        if (editorRedo.isEmpty()) return
        editorUndo.addLast(captureEditorSnapshot())
        restoreEditorSnapshot(editorRedo.removeLast())
        savePrefs()
        invalidate()
    }

    private fun defaultEditorStyle(index: Int): Style = when (index) {
        0 -> Style(31f, 7f, 76f, font = 0, color = Color.WHITE)
        1 -> Style(19f, 7f, 84f, font = 0, color = Color.WHITE)
        2 -> Style(11f, 73f, 10f, font = 0, color = Color.WHITE, align = 2)
        3 -> Style(26f, 80f, 15f, font = 0, color = Color.WHITE, align = 2)
        4 -> Style(18f, 80f, 5f, font = 0, color = Color.WHITE, align = 2)
        5 -> Style(14f, 7f, 90f, font = 0, color = Color.WHITE, visible = false)
        6 -> Style(13f, 7f, 94f, font = 0, color = Color.WHITE, visible = false)
        7 -> Style(12f, 72f, 90f, font = 0, color = Color.WHITE, align = 2, visible = false)
        8 -> Style(12f, 72f, 94f, font = 0, color = Color.WHITE, align = 2, visible = false)
        else -> Style(12f, 72f, 98f, font = 0, color = Color.WHITE, align = 2, visible = false)
    }

    private fun resetEditorElement() {
        recordEditorState()
        val d = defaultEditorStyle(editorElement)
        val st = styles[editorElement]
        st.size = d.size
        st.x = d.x
        st.y = d.y
        st.font = d.font
        st.color = d.color
        st.align = d.align
        st.shadow = d.shadow
        st.visible = d.visible
        when (editorElement) {
            2 -> showDate = true
            3 -> showTime = true
            4 -> showTemp = true
        }
        layoutPreset = 0
        savePrefs()
        invalidate()
    }

    private fun resetEditorAll() {
        recordEditorState()
        styles.indices.forEach { i ->
            val d = defaultEditorStyle(i)
            val st = styles[i]
            st.size = d.size
            st.x = d.x
            st.y = d.y
            st.font = d.font
            st.color = d.color
            st.align = d.align
            st.shadow = d.shadow
            st.visible = d.visible
        }
        showDate = true
        showTime = true
        showTemp = true
        layoutPreset = 0
        savePrefs()
        invalidate()
    }

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
        settingsToggle(c, "Afficher la date", showDate, x, y + 55f, 0)
        settingsChoice(c, "Format de date", dateFormatLabel(), x, y + 103f, 1)
        settingsToggle(c, "Afficher l'heure", showTime, x, y + 151f, 2)
        settingsChoice(c, "Format de l'heure", if (time24h) "24 h" else "12 h", x, y + 199f, 3)
        settingsToggle(c, "Afficher les secondes", showSeconds, x, y + 247f, 4)

        text(c, "Mode nuit", x + 22f, y + 316f, 13f, Color.rgb(177, 191, 209))
        settingsToggle(c, "Activer automatiquement", nightModeEnabled, x, y + 326f, 5)
        settingsChoice(c, "Début", "%02d:00".format(nightStartHour), x, y + 374f, 6)
        settingsChoice(c, "Fin", "%02d:00".format(nightEndHour), x, y + 422f, 7)
        settingsSlider(c, "Assombrissement", nightDimPercent.toFloat(), 0f, 85f, "$nightDimPercent %", x, y + 462f, 8)
        settingsToggle(c, "Masquer les informations la nuit", nightHideOverlays, x, y + 500f, 9)
    }

    private fun isNightModeActive(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        if (!nightModeEnabled) return false
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        return if (nightStartHour == nightEndHour) {
            true
        } else if (nightStartHour < nightEndHour) {
            hour in nightStartHour until nightEndHour
        } else {
            hour >= nightStartHour || hour < nightEndHour
        }
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
        val feels = temperatureLabel(feelsLikeC, includeUnit = true)
        val minT = temperatureLabel(forecastMinC, includeUnit = false)
        val maxT = temperatureLabel(forecastMaxC, includeUnit = false)
        text(c, "Ressenti $feels • Aujourd'hui $minT / $maxT", x + 22f, y + 401f, 12f, Color.rgb(169, 184, 203))
        forecastLines.take(3).forEachIndexed { i, line ->
            text(c, line, x + 22f + i * 210f, y + 438f, 11f, Color.rgb(196, 210, 228))
        }
        text(c, "OK sur « Ville météo » pour choisir une ville.", x + 22f, y + 485f, 11f, Color.rgb(129, 153, 181))
    }

    private fun drawSettingsSource(c: Canvas, x: Float, y: Float, w: Float) {
        text(c, "Source des photos", x + 22f, y + 34f, 18f, Color.WHITE, 1)

        val cardW = 264f
        val gap = 12f
        drawSourceCard(
            c, x + 22f, y + 72f, cardW, 105f,
            "Google Photos", "Takeout • albums exacts", 0,
            settingsControl == 0 && settingsColumn == 1 && !navFocus, true
        )
        drawSourceCard(
            c, x + 22f + cardW + gap, y + 72f, cardW, 105f,
            "Dossier local", "Parcourir le stockage", 1,
            settingsControl == 1 && settingsColumn == 1 && !navFocus, false
        )
        drawSourceCard(
            c, x + 22f + (cardW + gap) * 2f, y + 72f, cardW, 105f,
            "Choisir des photos", "Album Google non garanti", 2,
            settingsControl == 2 && settingsColumn == 1 && !navFocus, false
        )

        val status = when {
            library.isEmpty() -> "Aucune photothèque connectée."
            exactAlbums -> "$sourceName • ${library.size} médias • ${library.flatMap { it.albums }.distinct().size} albums exacts"
            sourceName.contains("Takeout", ignoreCase = true) ->
                "$sourceName • ${library.size} médias • certains noms exacts sont absents"
            sourceName.contains("Dossier", ignoreCase = true) ->
                "$sourceName • ${library.size} médias • noms de dossiers utilisés"
            else -> "$sourceName • ${library.size} médias • album Google Photos non garanti"
        }
        text(
            c, status, x + 22f, y + 215f, 14f,
            if (exactAlbums) Color.rgb(91, 213, 145) else Color.rgb(187, 198, 212)
        )

        settingsChoice(
            c,
            "Tri des albums",
            if (albumSort == 0) "Alphabétique" else "Nombre de photos",
            x,
            y + 235f,
            3
        )
        settingsChoice(
            c,
            "Recherche d'album",
            if (albumSearch.isBlank()) "Aucune" else albumSearch,
            x,
            y + 295f,
            4
        )

        val allAlbums = library.flatMap { it.albums }.distinct()
        val allSelected = allAlbums.isNotEmpty() && selectedAlbums.containsAll(allAlbums)
        controlBox(
            c,
            x + 350f,
            y + 355f,
            380f,
            39f,
            if (allSelected) "Désélectionner tous les albums" else "Sélectionner tous les albums",
            settingsColumn == 1 && settingsControl == 5 && !navFocus
        )

        text(
            c,
            "Appui long OK sur un album : masquer / réafficher.",
            x + 22f, y + 430f, 11f, Color.rgb(130, 154, 181)
        )
        text(
            c,
            "Masquées : ${excludedUris.size} permanentes • ${sessionExcludedUris.size} session • ${hiddenAlbums.size} albums",
            x + 22f, y + 458f, 11f, Color.rgb(130, 154, 181)
        )
        controlBox(
            c,
            x + 22f,
            y + 475f,
            300f,
            39f,
            "Réseau • WebDAV / SMB",
            settingsColumn == 1 && settingsControl == 7 && !navFocus
        )
        controlBox(
            c,
            x + 350f,
            y + 475f,
            380f,
            39f,
            "Tout réafficher",
            settingsColumn == 1 && settingsControl == 6 && !navFocus
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
        controlBox(c, x + 225f, y + 8f, 205f, 38f, if (remoteEnabled) "Télécommande : ON" else "Télécommande : OFF", settingsColumn == 1 && settingsControl == 13 && !navFocus)
        controlBox(c, x + 445f, y + 8f, 205f, 38f, if (interactionDiagnostics) "Zones : ON" else "Zones : OFF", settingsColumn == 1 && settingsControl == 12 && !navFocus)
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
        val diag = "Photo TV ${appVersionName()} • ${library.size} médias • $albumCount albums • ${favorites.size} favoris • ${sessionExcludedUris.size} masqués • $decodeFailureCount illisibles"
        ellipsizedText(c, diag, x + 22f, y + 545f, 820f, 10f, Color.rgb(135, 158, 184))
        ellipsizedText(c, memoryDiagnostics(), x + 22f, y + 559f, 820f, 9f, Color.rgb(145, 180, 211))
        if (lastDecodeFailure.isNotBlank()) {
            ellipsizedText(c, "Erreur : $lastDecodeFailure", x + 22f, y + 575f, 820f, 9f, Color.rgb(196, 150, 120))
        } else if (remoteEnabled) {
            ellipsizedText(c, remoteServer?.url() ?: "Télécommande : connexion réseau en attente", x + 22f, y + 575f, 820f, 9f, Color.rgb(137, 200, 173))
        }
    }

    fun diagnosticReport(): String {
        val cache = NetworkLibrary.cacheStats(context)
        val albums = library.flatMap { it.albums }.distinct().size
        return buildString {
            appendLine("Photo TV diagnostic")
            appendLine("Version: " + appVersionName())
            appendLine("Source: " + sourceName)
            appendLine("Médias: " + library.size)
            appendLine("Albums: " + albums)
            appendLine("Diaporama: " + slideshow)
            appendLine("Pause: " + paused)
            appendLine("Photo courante: " + currentPhoto)
            appendLine("Mode aléatoire: " + randomOrder)
            appendLine("Transition: " + transitions[transitionIndex.coerceIn(0, transitions.lastIndex)])
            appendLine("Mode image: " + imageModeLabel())
            appendLine("Fichiers illisibles: " + decodeFailureCount)
            appendLine("Dernière erreur: " + lastDecodeFailure)
            appendLine("Historique: " + recentUris.size)
            appendLine("Mémoire: " + memoryDiagnostics())
            appendLine("Cache réseau: " + cache.first + " fichiers • " + (cache.second / (1024L * 1024L)) + " Mo")
            appendLine("Télécommande: " + remoteEnabled)
            appendLine("Mode nuit actif: " + isNightModeActive())
            appendLine("État UI: " + automationStateDescription())
        }
    }
    private fun memoryDiagnostics(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val maxMb = rt.maxMemory() / (1024L * 1024L)
        val thumbMb = synchronized(bitmapCache) { bitmapCacheBytes / (1024L * 1024L) }
        val hdMb = synchronized(highResCache) { highResCacheBytes / (1024L * 1024L) }
        val thumbCount = synchronized(bitmapCache) { bitmapCache.size }
        val hdCount = synchronized(highResCache) { highResCache.size }
        val total = cacheHits + cacheMisses
        val hitRate = if (total == 0L) 0 else (cacheHits * 100L / total)
        val avgDecode = if (decodeCount == 0L) 0L else decodeTotalMs / decodeCount
        val jankRate = if (animatedFrameCount == 0L) 0L else slowFrameCount * 100L / animatedFrameCount
        return "RAM $usedMb/$maxMb Mo • mini $thumbMb Mo ($thumbCount) • HD $hdMb Mo ($hdCount) • cache $hitRate% • décod. ${avgDecode} ms • ${format1(measuredFps)} fps • jank $jankRate% ($slowFrameCount)"
    }

    private fun appVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun imageModeLabel(): String =
        listOf("Remplir", "Adapter", "Original", "Fond flouté")[imageMode.coerceIn(0, 3)]

    private fun presetName(i: Int): String =
        listOf("Standard", "Minimal", "Cinéma", "Horloge")[i.coerceIn(0, 3)]

    private fun applyPreset(index: Int) {
        recordEditorState()
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

            name == "Dissolution" -> {
                drawBitmapCenterCrop(c, previous, 0f, 0f, 1280f, 720f)
                val cols = 12
                val rows = 7
                val total = cols * rows
                val reveal = (transitionProgress * total).toInt().coerceIn(0, total)
                val tilePath = Path()
                for (step in 0 until reveal) {
                    val index = (step * 37) % total
                    val col = index % cols
                    val row = index / cols
                    val l = 1280f * col / cols
                    val t = 720f * row / rows
                    val r = 1280f * (col + 1) / cols
                    val b = 720f * (row + 1) / rows
                    tilePath.addRect(l, t, r, b, Path.Direction.CW)
                }
                c.save()
                c.clipPath(tilePath)
                drawBitmapCenterCrop(c, current, 0f, 0f, 1280f, 720f)
                c.restore()
            }

            name == "Flou progressif" -> {
                drawBitmapCenterCrop(c, previous, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = (220f * transitionProgress).toInt().coerceIn(0, 220)
                drawSoftBackground(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = (255f * transitionProgress * transitionProgress).toInt().coerceIn(0, 255)
                drawBitmapCenterCrop(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
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
        val nightActive = isNightModeActive()
        val hideOverlays = !ruleAllowsMetadata || (nightActive && nightHideOverlays) || (overlaysAutoHide && System.currentTimeMillis() - slideStartedAt > 10_000L)
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

        if (nightActive && nightDimPercent > 0) {
            val alpha = (255f * nightDimPercent / 100f).toInt().coerceIn(0, 230)
            fill(c, 0f, 0f, 1280f, 720f, Color.argb(alpha, 0, 0, 0))
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

    private fun tempText(): String = temperatureLabel(temperatureC, includeUnit = true)

    private fun temperatureLabel(valueC: Float, includeUnit: Boolean): String {
        if (!valueC.isFinite()) {
            return if (includeUnit) {
                if (tempCelsius) "— °C" else "— °F"
            } else "—°"
        }
        val shown = if (tempCelsius) valueC else valueC * 9f / 5f + 32f
        return if (includeUnit) {
            "${shown.toInt()} °${if (tempCelsius) "C" else "F"}"
        } else {
            "${shown.toInt()}°"
        }
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
        ellipsizedText(c, name, x + 8f, y + 154f, w - 16f, 13f, Color.WHITE, 1)
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
        if (library.isEmpty()) return mockAlbumBitmaps[index] ?: demoBitmap
        val item = library.firstOrNull { it.albums.contains(name) } ?: return demoBitmap
        val cached = synchronized(bitmapCache) { bitmapCache[item.uri.toString()] }
        if (cached == null) preload(listOf(item.uri))
        return cached ?: demoBitmap
    }

    private fun drawPhotoThumb(c: Canvas, x: Float, y: Float, w: Float, h: Float, item: PhotoItem?, focused: Boolean, demoIndex: Int = 0) {
        val bmp = if (item == null) {
            mockAlbumBitmaps[demoIndex] ?: demoBitmap
        } else {
            val cached = synchronized(bitmapCache) { bitmapCache[item.uri.toString()] }
            if (cached == null) preload(listOf(item.uri))
            cached ?: demoBitmap
        }
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
        ellipsizedText(c, name, x + 58f, y + 29f, w - 70f, 14f, Color.WHITE, 1)
        ellipsizedText(c, value, x + 58f, y + 53f, w - 70f, 11f, Color.rgb(221, 228, 238))
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
        ellipsizedText(c, value, x + 10f, y + h * .66f, w - 20f, 11f, Color.WHITE)
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

    private fun ellipsizedText(
        c: Canvas,
        s: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        size: Float,
        color: Int,
        weight: Int = 0,
        align: Int = 0
    ) {
        p.textSize = size
        p.typeface = Typeface.create("sans-serif", if (weight == 1) Typeface.BOLD else Typeface.NORMAL)
        val limit = maxWidth.coerceAtLeast(1f)
        var safe = s
        if (p.measureText(safe) > limit) {
            val ellipsis = "…"
            var low = 0
            var high = safe.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (p.measureText(safe.substring(0, mid) + ellipsis) <= limit) low = mid else high = mid - 1
            }
            safe = safe.substring(0, low) + ellipsis
        }
        text(c, safe, x, y, size, color, weight, align)
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
                if (currentItem()?.mediaType == "video") onVideoPause(true)
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
        if (automationMode) Log.i("PhotoTVState", automationStateDescription())
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        markInteraction()

        if (remoteQrVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    remoteQrVisible = false
                    invalidate()
                    true
                }
                else -> true
            }
        }

        if (page == 2 && event?.isCtrlPressed == true) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> {
                    undoEditor()
                    return true
                }
                KeyEvent.KEYCODE_Y -> {
                    redoEditor()
                    return true
                }
            }
        }

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
                    syncVideoPlayback()
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
                    editorControl = (editorControl + dir).coerceIn(0, 11)
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
                1 -> {
                    val size = albumPairs().size
                    val next = (albumFocus + dir).coerceIn(0, max(0, size - 1))
                    if (next != albumFocus) {
                        albumFocus = next
                        photoFocus = 0
                    }
                }
                2 -> {
                    val size = currentAlbumPhotos().size
                    photoFocus = (photoFocus + dir).coerceIn(0, max(0, size - 1))
                }
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
        if (dx == 0 && dy == 0) return
        recordEditorState()
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
        4 -> 9
        5 -> 2
        6 -> 7
        else -> if (advancedRulesOpen) 8 else 13
    }

    private fun adjustEditor(dir: Int) {
        if (editorControl !in 1..6) return
        recordEditorState()
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
                5 -> nightModeEnabled = !nightModeEnabled
                6 -> nightStartHour = (nightStartHour + dir + 24) % 24
                7 -> nightEndHour = (nightEndHour + dir + 24) % 24
                8 -> nightDimPercent = (nightDimPercent + dir * 5).coerceIn(0, 85)
                9 -> nightHideOverlays = !nightHideOverlays
            }
            5 -> when(settingsControl) { 0 -> showTemp=!showTemp; 1 -> tempCelsius=!tempCelsius }
            6 -> when (settingsControl) {
                3 -> albumSort = (albumSort + dir + 2) % 2
                5 -> toggleAllAlbums()
                6 -> clearAllMasks()
                7 -> onNetworkSource()
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
                12 -> interactionDiagnostics = !interactionDiagnostics
                13 -> {
                    remoteEnabled = !remoteEnabled
                    updateRemoteServer()
                    if (remoteEnabled) showRemoteQrIfAvailable() else remoteQrVisible = false
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
                            7 -> {
                                recordEditorState()
                                st.shadow = !st.shadow
                            }
                            8 -> undoEditor()
                            9 -> redoEditor()
                            10 -> resetEditorElement()
                            11 -> resetEditorAll()
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
                        1 -> onFolderSource()
                        2 -> onPickPhotos()
                        3 -> adjustSettings(1)
                        4 -> onAlbumSearch()
                        5 -> toggleAllAlbums()
                        6 -> clearAllMasks()
                        7 -> onNetworkSource()
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
                        12 -> interactionDiagnostics = !interactionDiagnostics
                        13 -> {
                            remoteEnabled = !remoteEnabled
                            updateRemoteServer()
                            if (remoteEnabled) showRemoteQrIfAvailable() else remoteQrVisible = false
                        }
                        else -> adjustSettings(1)
                    }
                }
            }
        }
        savePrefs()
        invalidate()
    }

    private fun updateRemoteServer() {
        remoteServer?.stop()
        remoteServer = null
        if (!remoteEnabled || !supportsVideoPlayback) return
        remoteServer = RemoteControlServer(
            token = remoteToken,
            stateProvider = {
                val item = currentItem()
                RemoteControlState(
                    title = item?.title ?: "Photo TV",
                    album = albumDisplay(item),
                    slideshow = slideshow,
                    paused = paused,
                    durationSeconds = durationSeconds,
                    transition = transitions[transitionIndex.coerceIn(0, transitions.lastIndex)],
                    imageMode = imageModeLabel()
                )
            }
        ) { command ->
            post { handleRemoteCommand(command) }
        }.also { it.start() }
    }

    private fun handleRemoteCommand(command: String) {
        when (command) {
            "prev" -> if (slideshow) slideshowNext(-1) else previewNext(-1)
            "next" -> if (slideshow) slideshowNext(1) else previewNext(1)
            "pause" -> if (slideshow) togglePause() else startSlideshow()
            "stop" -> if (slideshow) stopSlideshow()
            "sources" -> {
                if (slideshow) stopSlideshow()
                page = 1
                photosRow = 0
                sourceFocus = 0
                navFocus = false
                invalidate()
            }
            "favorite" -> currentItem()?.let { item ->
                val key = item.uri.toString()
                if (favorites.contains(key)) favorites.remove(key) else favorites.add(key)
                savePrefs()
                invalidate()
            }
            "hide" -> currentItem()?.let { item ->
                sessionExcludedUris.add(item.uri.toString())
                val items = activePhotos()
                if (items.isEmpty()) {
                    if (slideshow) stopSlideshow()
                } else {
                    currentPhoto = currentPhoto.coerceIn(0, items.lastIndex)
                    preloadAroundCurrent()
                    if (slideshow) {
                        syncVideoPlayback()
                        scheduleSlideshow()
                    }
                    invalidate()
                }
            }
            "duration_down" -> {
                durationSeconds = (durationSeconds - 1).coerceIn(2, 120)
                savePrefs()
                scheduleSlideshow()
                invalidate()
            }
            "duration_up" -> {
                durationSeconds = (durationSeconds + 1).coerceIn(2, 120)
                savePrefs()
                scheduleSlideshow()
                invalidate()
            }
            "transition_next" -> {
                transitionIndex = (transitionIndex + 1) % transitions.size
                savePrefs()
                invalidate()
            }
            "mode_next" -> {
                imageMode = (imageMode + 1) % 4
                savePrefs()
                invalidate()
            }
            "history_prev" -> goToRecentPrevious()
            "album_next" -> {
                val albums = albumPairs().map { it.first }
                if (albums.isNotEmpty()) {
                    val currentAlbum = currentItem()?.albums?.firstOrNull()
                    val currentIndex = albums.indexOf(currentAlbum).coerceAtLeast(-1)
                    val nextAlbum = albums[(currentIndex + 1 + albums.size) % albums.size]
                    val items = activePhotos()
                    val idx = items.indexOfFirst { it.albums.contains(nextAlbum) }
                    if (idx >= 0) {
                        previousPhoto = currentPhoto
                        currentPhoto = idx
                        rememberCurrentUri()
                        preloadAroundCurrent()
                        syncVideoPlayback()
                        savePrefs()
                        invalidate()
                    }
                }
            }
        }
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
        recordEditorState()
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
        rememberCurrentUri()
        rebuildShuffleBag()
        transitionProgress = 1f
        slideStartedAt = System.currentTimeMillis()
        preloadAroundCurrent()
        syncVideoPlayback()
        inactivityHandler.removeCallbacksAndMessages(null)
        savePrefs()
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
        if (currentItem()?.mediaType == "video") onVideoPause(paused)
        savePrefs()
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
        val items = activePhotos()
        shuffleBag.clear()
        shuffleBag.addAll(SlideshowOrder.newBag(items.size, currentPhoto))
    }

    private fun preloadAroundCurrent() {
        val items = activePhotos()
        if (items.isEmpty()) return

        val rt = Runtime.getRuntime()
        val freeRatio = ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())).toDouble() /
            rt.maxMemory().coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
        val seconds = ruleForItem(currentItem())?.durationSeconds?.takeIf { it > 0 } ?: durationSeconds
        val ahead = when {
            freeRatio < .18 -> 1
            seconds <= 4 -> 2
            seconds >= 20 && freeRatio > .40 -> 8
            freeRatio > .35 -> 5
            else -> 3
        }
        val hdAhead = when {
            freeRatio < .22 -> 1
            freeRatio > .45 -> min(4, ahead + 1)
            else -> min(2, ahead)
        }

        val indices = if (randomOrder && shuffleBag.isNotEmpty()) {
            buildList {
                add(currentPhoto)
                shuffleBag.take(ahead).forEach { if (it in items.indices) add(it) }
            }
        } else {
            (0..ahead).map { (currentPhoto + it) % items.size }
        }
        val orderedUris = indices.distinct().mapNotNull { items.getOrNull(it)?.uri }
        preload(orderedUris)
        orderedUris.take(hdAhead).forEach { requestHighRes(it) }
        if (orderedUris.any { NetworkLibrary.isNetworkUri(it) }) {
            executor.execute { NetworkLibrary.prefetch(context, orderedUris.take(ahead + 1)) }
        }
    }

    fun onMemoryPressure(level: Int) {
        val aggressive = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        synchronized(highResCache) {
            val keep = if (aggressive) 1 else 2
            while (highResCache.size > keep) {
                val first = highResCache.entries.firstOrNull() ?: break
                highResCacheBytes -= first.value.allocationByteCount.toLong()
                if (!first.value.isRecycled) first.value.recycle()
                highResCache.remove(first.key)
            }
        }
        synchronized(bitmapCache) {
            val keep = if (aggressive) 2 else 6
            while (bitmapCache.size > keep) {
                val first = bitmapCache.entries.firstOrNull() ?: break
                bitmapCacheBytes -= first.value.allocationByteCount.toLong()
                if (!first.value.isRecycled) first.value.recycle()
                bitmapCache.remove(first.key)
            }
        }
        softBitmap?.let { if (!it.isRecycled) it.recycle() }
        softBitmap = null
        softSource = null
        postInvalidate()
    }

    private fun rememberCurrentUri() {
        val key = currentItem()?.uri?.toString().orEmpty()
        if (key.isBlank()) return
        if (recentUris.peekLast() == key) return
        recentUris.addLast(key)
        while (recentUris.size > 100) recentUris.removeFirst()
    }

    private fun goToRecentPrevious() {
        val current = currentItem()?.uri?.toString()
        while (recentUris.isNotEmpty()) {
            val key = recentUris.removeLast()
            if (key == current) continue
            val items = activePhotos()
            val idx = items.indexOfFirst { it.uri.toString() == key }
            if (idx >= 0) {
                previousPhoto = currentPhoto
                currentPhoto = idx
                slideStartedAt = System.currentTimeMillis()
                preloadAroundCurrent()
                syncVideoPlayback()
                startTransition()
                scheduleSlideshow()
                savePrefs()
                invalidate()
                return
            }
        }
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
        rememberCurrentUri()
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
                val slot = ((x - 47f) / 199f).toInt()
                val albums = albumPairs()
                val start = windowStart(albumFocus, albums.size)
                val index = start + slot
                if (slot in 0 until min(6, albums.size - start) && index in albums.indices &&
                    x >= 47f + slot * 199f && x <= 232f + slot * 199f
                ) {
                    albumFocus = index
                    photoFocus = 0
                    val name = albums[index].first
                    if (library.isNotEmpty()) {
                        if (selectedAlbums.contains(name)) selectedAlbums.remove(name) else selectedAlbums.add(name)
                    }
                }
            }
            y in 522f..626f -> {
                val slot = ((x - 47f) / 199f).toInt()
                val photos = currentAlbumPhotos()
                val start = windowStart(photoFocus, photos.size)
                val index = start + slot
                if (slot in 0 until min(6, photos.size - start) && index in photos.indices &&
                    x >= 47f + slot * 199f && x <= 232f + slot * 199f
                ) {
                    photoFocus = index
                    photos.getOrNull(index)?.let { item ->
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
        val visibleCount = 5
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
                recordEditorState()
                st.font = (st.font + 1) % 4
            }
            y in 220f..268f -> {
                editorControl = 2
                recordEditorState()
                val t = ((x - 1051f) / 126f).coerceIn(0f, 1f)
                st.size = 12f + t * 68f
            }
            y in 280f..330f -> {
                editorControl = 3
                recordEditorState()
                val colors = listOf(Color.WHITE, Color.rgb(173,196,255), Color.rgb(255,224,112), Color.rgb(255,126,126), Color.rgb(137,230,173))
                val idx = colors.indexOf(st.color).coerceAtLeast(0)
                st.color = colors[(idx + 1) % colors.size]
            }
            y in 326f..378f -> {
                editorControl = 4
                recordEditorState()
                st.x = (((x - 1051f) / 126f) * 100f).coerceIn(0f, 100f)
            }
            y in 387f..439f -> {
                editorControl = 5
                recordEditorState()
                st.y = (((x - 1051f) / 126f) * 100f).coerceIn(0f, 100f)
            }
            y in 454f..506f -> {
                editorControl = 6
                recordEditorState()
                st.align = (((x - 1048f) / 42f).toInt()).coerceIn(0, 2)
            }
            y in 516f..568f -> {
                editorControl = 7
                recordEditorState()
                st.shadow = !st.shadow
            }
            y in 580f..625f -> {
                val i = ((x - 956f) / 67f).toInt()
                if (i in 0..3) {
                    editorControl = 8 + i
                    when (i) {
                        0 -> undoEditor()
                        1 -> redoEditor()
                        2 -> resetEditorElement()
                        3 -> resetEditorAll()
                    }
                }
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
                y in 145f..260f && x in 402f..666f -> {
                    settingsControl = 0
                    onExactSource()
                }
                y in 145f..260f && x in 678f..942f -> {
                    settingsControl = 1
                    onFolderSource()
                }
                y in 145f..260f && x in 954f..1218f -> {
                    settingsControl = 2
                    onPickPhotos()
                }
                y in 305f..365f -> {
                    settingsControl = 3
                    albumSort = (albumSort + 1) % 2
                }
                y in 365f..425f -> {
                    settingsControl = 4
                    onAlbumSearch()
                }
                y in 425f..490f -> {
                    settingsControl = 5
                    toggleAllAlbums()
                }
                y in 545f..610f && x < 730f -> {
                    settingsControl = 7
                    onNetworkSource()
                }
                y in 545f..610f -> {
                    settingsControl = 6
                    clearAllMasks()
                }
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
                x in 605f..810f && y in 77f..125f -> {
                    settingsControl = 13
                    remoteEnabled = !remoteEnabled
                    updateRemoteServer()
                    if (remoteEnabled) showRemoteQrIfAvailable() else remoteQrVisible = false
                }
                x in 825f..1030f && y in 77f..125f -> {
                    settingsControl = 12
                    interactionDiagnostics = !interactionDiagnostics
                }
                x >= 1040f && y in 77f..125f -> {
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
        markInteraction()

        val sx = if (width > 0) width / 1280f else 1f
        val sy = if (height > 0) height / 720f else 1f
        val x = event.x / sx
        val y = event.y / sy
        diagnosticTouchX = x
        diagnosticTouchY = y

        if (remoteQrVisible) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                remoteQrVisible = false
                invalidate()
            }
            return true
        }

        if (page == 2 && !slideshow && !quickMenuVisible && !infoPanelVisible) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (x in 370f..918f && y in 64f..638f) {
                        touchDraggingEditor = true
                        editorGestureRecorded = true
                        recordEditorState()
                        editorColumn = 1
                        moveSelectedEditorElementTo(x, y)
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (touchDraggingEditor) {
                    moveSelectedEditorElementTo(x, y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (touchDraggingEditor) {
                    moveSelectedEditorElementTo(x, y)
                    touchDraggingEditor = false
                    editorGestureRecorded = false
                    savePrefs()
                    invalidate()
                    return true
                }
            }
        }

        if (event.actionMasked != MotionEvent.ACTION_UP) return true

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

    private fun moveSelectedEditorElementTo(x: Float, y: Float) {
        val st = styles[editorElement]
        st.x = (((x - 370f) / 548f) * 100f).coerceIn(0f, 100f)
        st.y = (((y - 64f) / 574f) * 100f).coerceIn(0f, 100f)
        if (gridSnap) {
            st.x = snapPercent(st.x)
            st.y = snapPercent(st.y)
        }
    }

    override fun onDetachedFromWindow() {
        remoteServer?.stop()
        remoteServer = null
        remoteQrBitmap?.let { if (!it.isRecycled) it.recycle() }
        remoteQrBitmap = null
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        inactivityHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        transitionAnimator?.cancel()
        onVideoPause(true)
        onVideoPlayback(null, videoSound)
        executor.shutdownNow()
        highResExecutor.shutdownNow()
    }
}
