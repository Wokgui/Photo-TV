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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
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
    private val onPrepareNetworkSource: (NetworkLibrary.Kind, String, String) -> Unit = { _, _, _ -> },
    private val onSettingsPin: () -> Unit = {},
    private val canOpenSettings: () -> Boolean = { true },
    private val onUnlockSettings: () -> Unit = {},
    private val onWeatherLocation: () -> Unit = {},
    private val onExportSettings: () -> Unit = {},
    private val onImportSettings: () -> Unit = {},
    private val onAlbumSearch: () -> Unit = {},
    private val onScreensaver: () -> Unit = {},
    private val onVideoPlayback: (Uri?, Boolean) -> Unit = { _, _ -> },
    private val onVideoPause: (Boolean) -> Unit = {},
    private val supportsVideoPlayback: Boolean = false,
    private val automationMode: Boolean = false
) : View(context) {

    companion object {
        private const val SETTINGS_SCHEMA_VERSION = 4
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

    private data class SourceRule(
        var enabled: Boolean = false,
        var seasonMode: Int = 0,
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
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val bitmapCache = LinkedHashMap<String, Bitmap>(32, .75f, true)
    private val bitmapLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
    private val emptySlideshowBackground: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.slideshow_empty_background)
    }

    private var library: List<PhotoItem> = emptyList()
    private var uriIndex: Map<String, PhotoItem> = emptyMap()
    private var albumIndex: Map<String, List<PhotoItem>> = emptyMap()
    private var sourceIndex: Map<String, List<PhotoItem>> = emptyMap()
    private var libraryRevision = 0L
    private var libraryHasNetworkItems = false
    private var activePhotosCacheKey = Long.MIN_VALUE
    private var activePhotosCache: List<PhotoItem> = emptyList()
    private var exactAlbums = false
    private var sourceName = "Démo"
    private var page = 0
    private var previewControlsVisible = false
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
    private var smartSelectionMode = SmartSelectionPolicy.OFF
    private var autonomousSlideshow = false
    private var backgroundAnalysis = true
    private var autonomousSlideNumber = 0L
    private var autonomousImageModeOverride: Int? = null
    private var transitionIndex = 0
    private var transitionSeconds = 2f
    private var fadeIntensityPercent = 100
    private var hideTitleExtensions = false
    private var kenBurns = true
    private var zoomLevel = 0
    private var lightInterface = false
    private var darkTheme = false
    private var interfaceFont = 0
    private var interfaceFontScale = 1f
    private var settingsEditing = false
    private var editorScroll = 0f
    private val editorHitBoxes = mutableListOf<Pair<Int, android.graphics.RectF>>()
    private var settingsScroll = 0f
    private val settingHitBoxes = mutableListOf<Pair<Int, android.graphics.RectF>>()
    private var listTouchY = 0f
    private var listTouchActive = false
    private var listTouchMoved = false
    private val driveRetryAfter = java.util.concurrent.ConcurrentHashMap<String,Long>()
    private var slideshowFont = 0
    private var renderingSlideshowText = false
    private var lifecycleClosed = false
    private val brandIcon: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.photo_tv_icon_art)
    }
    private fun viewportScale() = min(width / 1280f, height / 720f).coerceAtLeast(.001f)
    private fun viewportLeft() = (width - 1280f * viewportScale()) / 2f
    private fun viewportTop() = (height - 720f * viewportScale()) / 2f
    private fun interfaceColor(color: Int): Int {
        if (!lightInterface || Color.alpha(color) < 240) return color
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        if (r > 235 && g > 235 && b > 235) return Color.rgb(30, 44, 64)
        if (r < 65 && g < 85 && b < 110) return if (r > 25) Color.rgb(205, 217, 232) else Color.rgb(248, 250, 253)
        if (b > 130 && r < 70 && g < 160) return Color.rgb(210, 230, 255)
        if (r in 90..235 && g in 120..235 && b >= g) return Color.rgb(71, 91, 117)
        return color
    }
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
    private var imageMode = 1
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
    private val failedMediaUris = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var decodeFailureCount = 0
    @Volatile private var lastDecodeFailure = ""
    private val recentUris = java.util.ArrayDeque<String>()
    private val smartCropAnchorCache = java.util.concurrent.ConcurrentHashMap<String, List<SmartCropPolicy.Anchor>>()
    private val sceneCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val qualityCache = java.util.concurrent.ConcurrentHashMap<String, MediaMetadataStore.CachedQuality>()
    @Volatile private var backgroundAnalysisGeneration = 0L
    @Volatile private var backgroundAnalysisDone = 0
    @Volatile private var backgroundAnalysisTotal = 0
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
    private var historyOverlayVisible = false
    private var historyOverlayIndex = 0
    private var memoriesOverlayVisible = false
    private var memoriesMode = 0
    private var memoriesIndex = 0
    private var memoriesTabFocus = true
    private var technicalDiagnosticsVisible = false
    private var infoPanelVisible = false
    private var infoPanelScroll = 0f
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
    private var albumDensity = 1
    private val albumHitBoxes = mutableListOf<Pair<Int, RectF>>()
    private val albumDensityLabels = listOf("Grands", "Standard", "Compact")
    private var videoSound = false
    private var remoteEnabled = false
    private var remoteToken = ""
    private var remoteServer: RemoteControlServer? = null
    private var remoteQrVisible = false
    private var remoteQrBitmap: Bitmap? = null
    private var remoteQrUrl = ""
    private var remoteSourceFilter: String? = null
    private var ruleAlbumIndex = 0
    private var ruleSourceIndex = 0
    private var rulesBySource = false
    private var advancedRulesOpen = false
    private val albumRules = linkedMapOf<String, AlbumRule>()
    private val sourceRules = linkedMapOf<String, SourceRule>()

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
        if (automationMode) loadDeterministicDemo()
        if (!automationMode) {
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
        SettingsMigration.migrate(prefs)
        durationSeconds = prefs.getInt("duration", 10)
        fixedImage = prefs.getBoolean("fixed", false)
        loop = prefs.getBoolean("loop", true)
        randomOrder = prefs.getBoolean("random", false)
        smartSelectionMode = prefs.getInt("smart_selection_mode", SmartSelectionPolicy.OFF).coerceIn(0, 4)
        autonomousSlideshow = prefs.getBoolean("autonomous_slideshow", false)
        backgroundAnalysis = prefs.getBoolean("background_analysis", true)
        transitionIndex = prefs.getInt("transition", 0).coerceIn(0, transitions.lastIndex)
        transitionSeconds = prefs.getFloat("transition_seconds", 2f)
        fadeIntensityPercent = prefs.getInt("fade_intensity_percent", 100).coerceIn(0, 100)
        hideTitleExtensions = prefs.getBoolean("hide_title_extensions", false)
        kenBurns = prefs.getBoolean("ken", true)
        zoomLevel = prefs.getInt("zoom", 0).coerceIn(-1, 2)
        darkTheme = prefs.getBoolean("dark_theme", false)
        interfaceFontScale = prefs.getFloat("interface_font_scale", 1f).coerceIn(.85f, 1.6f)
        interfaceFont = prefs.getInt("interface_font", 0).coerceIn(0, 3)
        slideshowFont = prefs.getInt("slideshow_font", 0).coerceIn(0, 3)
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
        imageMode = prefs.getInt("image_mode", 1).coerceIn(0, 6)
        gridSnap = prefs.getBoolean("grid_snap", true)
        oledProtection = prefs.getBoolean("oled", true)
        overlaysAutoHide = prefs.getBoolean("overlay_hide", false)
        autoStartMinutes = prefs.getInt("auto_start", 5).coerceIn(0, 60)
        startDirectly = prefs.getBoolean("start_direct", false)
        layoutPreset = prefs.getInt("layout_preset", 0).coerceIn(0, 3)
        albumSearch = prefs.getString("album_search", "") ?: ""
        albumSort = prefs.getInt("album_sort", 0).coerceIn(0, 1)
        albumDensity = prefs.getInt("album_density", 1).coerceIn(0, 2)
        videoSound = prefs.getBoolean("video_sound", false)
        remoteEnabled = prefs.getBoolean("remote_enabled", false)
        remoteToken = prefs.getString("remote_token", "").orEmpty().ifBlank {
            java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        }
        interactionDiagnostics = prefs.getBoolean("interaction_diagnostics", false)
        restoreAlbumRules(prefs.getString("album_rules", null))
        restoreSourceRules(prefs.getString("source_rules", null))
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
            putInt("smart_selection_mode", smartSelectionMode)
            putBoolean("autonomous_slideshow", autonomousSlideshow)
            putBoolean("background_analysis", backgroundAnalysis)
            putInt("transition", transitionIndex)
            putFloat("transition_seconds", transitionSeconds)
            putInt("fade_intensity_percent", fadeIntensityPercent)
            putBoolean("hide_title_extensions", hideTitleExtensions)
            putBoolean("ken", kenBurns)
            putInt("zoom", zoomLevel)
            putBoolean("dark_theme", darkTheme)
            putFloat("interface_font_scale", interfaceFontScale)
            putInt("interface_font", interfaceFont)
            putInt("slideshow_font", slideshowFont)
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
            putInt("album_density", albumDensity)
            putBoolean("video_sound", videoSound)
            putBoolean("remote_enabled", remoteEnabled)
            putString("remote_token", remoteToken)
            putBoolean("interaction_diagnostics", interactionDiagnostics)
            putString("album_rules", albumRulesJson().toString())
            putString("source_rules", sourceRulesJson().toString())
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
            post {
                rebuildLibraryIndexes()
                postInvalidate()
            }
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
        root.put("smartSelectionMode", smartSelectionMode)
        root.put("autonomousSlideshow", autonomousSlideshow)
        root.put("backgroundAnalysis", backgroundAnalysis)
        root.put("transitionIndex", transitionIndex)
        root.put("transitionSeconds", transitionSeconds.toDouble())
        root.put("fadeIntensityPercent", fadeIntensityPercent)
        root.put("hideTitleExtensions", hideTitleExtensions)
        root.put("kenBurns", kenBurns)
        root.put("zoomLevel", zoomLevel)
        root.put("darkTheme", darkTheme)
        root.put("interfaceFontScale", interfaceFontScale.toDouble())
        root.put("interfaceFont", interfaceFont)
        root.put("slideshowFont", slideshowFont)
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
        root.put("albumDensity", albumDensity)
        root.put("videoSound", videoSound)
        root.put("albumRules", albumRulesJson())
        root.put("sourceRules", sourceRulesJson())
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
            smartSelectionMode = root.optInt("smartSelectionMode", smartSelectionMode).coerceIn(0, 4)
            autonomousSlideshow = root.optBoolean("autonomousSlideshow", autonomousSlideshow)
            backgroundAnalysis = root.optBoolean("backgroundAnalysis", backgroundAnalysis)
            transitionIndex = root.optInt("transitionIndex", transitionIndex).coerceIn(0, transitions.lastIndex)
            transitionSeconds = root.optDouble("transitionSeconds", transitionSeconds.toDouble()).toFloat().coerceIn(.2f, 4f)
            fadeIntensityPercent = root.optInt("fadeIntensityPercent", fadeIntensityPercent).coerceIn(0, 100)
            hideTitleExtensions = root.optBoolean("hideTitleExtensions", hideTitleExtensions)
            kenBurns = root.optBoolean("kenBurns", kenBurns)
            zoomLevel = root.optInt("zoomLevel", zoomLevel).coerceIn(-1, 2)
            darkTheme = root.optBoolean("darkTheme", darkTheme)
            interfaceFontScale = root.optDouble("interfaceFontScale", interfaceFontScale.toDouble()).toFloat().coerceIn(.85f, 1.6f)
            interfaceFont = root.optInt("interfaceFont", interfaceFont).coerceIn(0, 3)
            slideshowFont = root.optInt("slideshowFont", slideshowFont).coerceIn(0, 3)
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
            imageMode = root.optInt("imageMode", imageMode).coerceIn(0, 6)
            gridSnap = root.optBoolean("gridSnap", gridSnap)
            oledProtection = root.optBoolean("oledProtection", oledProtection)
            overlaysAutoHide = root.optBoolean("overlaysAutoHide", overlaysAutoHide)
            autoStartMinutes = root.optInt("autoStartMinutes", autoStartMinutes).coerceIn(0, 60)
            startDirectly = root.optBoolean("startDirectly", startDirectly)
            favoritesOnly = root.optBoolean("favoritesOnly", favoritesOnly)
            layoutPreset = root.optInt("layoutPreset", layoutPreset).coerceIn(0, 3)
            albumSearch = root.optString("albumSearch", albumSearch)
            albumSort = root.optInt("albumSort", albumSort).coerceIn(0, 1)
            albumDensity = root.optInt("albumDensity", albumDensity).coerceIn(0, 2)
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

            root.optJSONObject("sourceRules")?.let {
                sourceRules.clear()
                restoreSourceRules(it.toString())
            }

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
            val availableAlbums = albumIndex.keys.toList()
            if (availableAlbums.isNotEmpty()) {
                selectedAlbums.retainAll(availableAlbums.toSet())
                selectedAlbums.clear()
                selectedAlbums.addAll(availableAlbums.filterNot { it in hiddenAlbums })
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

    private fun sourceRulesJson(): JSONObject {
        val root = JSONObject()
        sourceRules.forEach { (name, rule) ->
            root.put(
                name,
                JSONObject()
                    .put("enabled", rule.enabled)
                    .put("seasonMode", rule.seasonMode)
                    .put("startHour", rule.startHour)
                    .put("endHour", rule.endHour)
                    .put("durationSeconds", rule.durationSeconds)
                    .put("transitionIndex", rule.transitionIndex)
                    .put("showMetadata", rule.showMetadata)
            )
        }
        return root
    }

    private fun restoreSourceRules(raw: String?) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val root = JSONObject(raw)
            val names = root.keys()
            while (names.hasNext()) {
                val name = names.next()
                val o = root.optJSONObject(name) ?: continue
                sourceRules[name] = SourceRule(
                    enabled = o.optBoolean("enabled", false),
                    seasonMode = o.optInt("seasonMode", 0).coerceIn(0, 4),
                    startHour = o.optInt("startHour", 0).coerceIn(0, 23),
                    endHour = o.optInt("endHour", 24).coerceIn(1, 24),
                    durationSeconds = o.optInt("durationSeconds", 0).coerceIn(0, 120),
                    transitionIndex = o.optInt("transitionIndex", -1).coerceIn(-1, transitions.lastIndex),
                    showMetadata = o.optBoolean("showMetadata", true),
                )
            }
        }
    }

    private fun sourceLabels(): List<String> = sourceIndex.keys.sorted()

    private fun sourceAllowed(label: String, now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val rule = sourceRules[label] ?: return true
        if (!rule.enabled) return true

        val month = now.get(java.util.Calendar.MONTH) + 1
        val seasonAllowed = when (rule.seasonMode) {
            1 -> month == 12 || month == 1 || month == 2
            2 -> month in 3..5
            3 -> month in 6..8
            4 -> month in 9..11
            else -> true
        }
        if (!seasonAllowed) return false

        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            rule.startHour == 0 && rule.endHour == 24 -> true
            rule.startHour < rule.endHour -> hour in rule.startHour until rule.endHour
            else -> hour >= rule.startHour || hour < rule.endHour
        }
    }

    private fun sourceRuleForItem(item: PhotoItem?): SourceRule? {
        if (item == null) return null
        val label = item.sourceLabel.ifBlank { "Source" }
        return sourceRules[label]?.takeIf { it.enabled }
    }

    private fun effectiveDuration(item: PhotoItem?): Int {
        val source = sourceRuleForItem(item)?.durationSeconds?.takeIf { it > 0 }
        val album = ruleForItem(item)?.durationSeconds?.takeIf { it > 0 }
        val autonomous = if (autonomousSlideshow && item != null) {
            AutonomousSlideshowPolicy.current(
                slideNumber = autonomousSlideNumber,
                scene = currentScene(item),
                qualityScore = qualityCache[item.uri.toString()]?.score ?: -1
            ).durationSecondsOverride
        } else null
        return source ?: album ?: autonomous ?: durationSeconds
    }

    private fun effectiveTransition(item: PhotoItem?): Int {
        val source = sourceRuleForItem(item)?.transitionIndex?.takeIf { it >= 0 }
        val album = ruleForItem(item)?.transitionIndex?.takeIf { it >= 0 }
        val requested = source ?: album ?: transitionIndex

        val rt = Runtime.getRuntime()
        val freeRatio = ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())).toDouble() /
            rt.maxMemory().coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
        val jankRate = if (animatedFrameCount == 0L) 0L
        else slowFrameCount * 100L / animatedFrameCount

        return TransitionPolicy.choose(
            requestedIndex = requested,
            fadeIndex = 0,
            noneIndex = transitions.lastIndex,
            mediaType = item?.mediaType.orEmpty(),
            imageMode = imageMode,
            width = item?.width ?: 0,
            height = item?.height ?: 0,
            measuredFps = measuredFps,
            jankPercent = jankRate,
            freeRatio = freeRatio
        )
    }

    private fun effectiveShowMetadata(item: PhotoItem?): Boolean =
        (sourceRuleForItem(item)?.showMetadata ?: true) &&
            (ruleForItem(item)?.showMetadata ?: true)
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

    fun installAutomationLibraryForTest(count: Int) {
        if (!automationMode) return
        val safeCount = count.coerceIn(1, 50_000)
        val items = (0 until safeCount).map { index ->
            PhotoItem(
                uri = Uri.parse("content://phototv-test/photo-" + index),
                title = "Test " + index,
                albums = linkedSetOf("Test " + (index % 24)),
                sourceLabel = "Test " + (index % 4)
            )
        }
        setLibrary(items, exactAlbums = false, sourceName = "Test")
    }

    fun automationLibrarySizeForTest(): Int =
        if (automationMode) library.size else 0

    fun automationActiveCountForTest(): Int =
        if (automationMode) activePhotos().size else 0

    fun setAutomationImageModeForTest(index: Int) {
        if (!automationMode) return
        imageMode = index.coerceIn(0, imageModes.lastIndex)
        savePrefs()
        syncVideoPlayback()
        preloadAroundCurrent()
        invalidate()
    }

    fun startAutomationSlideshowForTest() {
        if (automationMode) startSlideshow()
    }

    fun advanceAutomationForTest() {
        if (automationMode && slideshow) slideshowNext(1)
    }

    fun importSettingsForTest(raw: String): Boolean =
        if (automationMode) importSettingsJson(raw) else false

    fun accessibilityDescriptionForTest(): String =
        if (automationMode) accessibilityDescription() else ""

    fun openSettingsAfterUnlock() {
        page = 3
        navFocus = false
        settingsCategory = 0
        settingsControl = 0
        settingsColumn = 0
        invalidate()
    }

    fun setAutomationPage(index: Int) {
        slideshow = false
        paused = false
        quickMenuVisible = false
        infoPanelVisible = false
        page = index.coerceIn(0, 3)
        previewControlsVisible = false
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

    private fun accessibilityDescription(): String {
        loadingText?.let { return "Photo TV. $it" }
        if (remoteQrVisible) return "Télécommande téléphone. Scannez le QR code avec un téléphone connecté au même réseau."
        if (technicalDiagnosticsVisible) return "Diagnostics techniques. Appuyez sur OK ou Retour pour fermer."
        if (historyOverlayVisible) return "Historique récent. Utilisez les flèches pour choisir une photo puis OK pour l'ouvrir."
        if (memoriesOverlayVisible) return "Souvenirs. Utilisez les flèches pour choisir une catégorie et une photo."
        if (slideshow) {
            val item = currentItem()
            val state = if (paused) "En pause" else "Lecture"
            return "Diaporama. $state. ${item?.title.orEmpty()}. ${albumDisplay(item)}. Flèche gauche : précédente. Flèche droite : suivante. OK : pause ou reprise. Retour : quitter."
        }
        if (navFocus) {
            val labels = navLabels
            return "Navigation principale. ${labels[page.coerceIn(0, labels.lastIndex)]}. Flèches gauche et droite pour changer d'écran. OK pour ouvrir."
        }
        return when (page) {
            0 -> {
                val item = currentItem()
                "Aperçu. ${item?.title.orEmpty()}. ${albumDisplay(item)}. Flèches gauche et droite pour changer de photo. ${if(previewControlsVisible) "OK pour lancer le diaporama. Bas pour les onglets." else "OK pour afficher le menu."}"
            }
            1 -> when (photosRow) {
                3 -> "Densité des albums : ${albumDensityLabels[albumDensity]}. Gauche et droite ou OK pour modifier. Bas pour parcourir les albums."
                0 -> "Sources. " + listOf("Google Photos ou Takeout", "Dossier local", "Sélection de photos")[sourceFocus.coerceIn(0, 2)] + ". OK pour ouvrir."
                1 -> {
                    val name = currentAlbumName()
                    val selected = if (selectedAlbums.contains(name)) "sélectionné" else "non sélectionné"
                    "Albums. $name, $selected. OK pour modifier la sélection."
                }
                2 -> {
                    val item = currentAlbumPhotos().getOrNull(photoFocus)
                    "Photos. ${item?.title ?: "Aucune photo"}. OK pour ouvrir."
                }
                else -> "Navigation principale."
            }
            2 -> {
                val element = elementNames[editorElement.coerceIn(0, elementNames.lastIndex)]
                when (editorColumn) {
                    0 -> "Éditeur. Élément $element. Flèches haut et bas pour choisir un élément. Droite pour modifier."
                    1 -> "Éditeur. Aperçu de $element. OK pour activer ou quitter le déplacement."
                    else -> "Éditeur. Réglage ${editorControl + 1} pour $element. Flèches pour modifier."
                }
            }
            else -> {
                val categories = listOf(
                    "Diaporama", "Éléments affichés", "Style et position", "Transitions",
                    "Heure et date", "Température", "Source des photos", "Intelligence", "Avancés"
                )
                val category = categories[settingsCategory.coerceIn(0, categories.lastIndex)]
                if (settingsColumn == 0) {
                    "Réglages. Catégorie $category. Haut et bas pour changer de catégorie. Droite ou OK pour ouvrir."
                } else {
                    val control = when {
                        settingsCategory == 2 && settingsControl == 10 -> "Police de l’interface : ${fontName(interfaceFont)}"
                        settingsCategory == 2 && settingsControl == 11 -> "Police du diaporama : ${fontName(slideshowFont)}"
                        else -> "Contrôle ${settingsControl + 1}"
                    }
                    "Réglages. $category. $control. Utilisez les flèches et OK pour modifier."
                }
            }
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.view.View"
        info.contentDescription = accessibilityDescription()
        info.isClickable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (slideshow) togglePause() else activate()
        contentDescription = accessibilityDescription()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }

    private fun announceAccessibilityState() {
        contentDescription = accessibilityDescription()
        announceForAccessibility(contentDescription)
    }

    fun automationStateDescription(): String =
        "PhotoTV page=$page navFocus=$navFocus photosRow=$photosRow sourceFocus=$sourceFocus " +
            "albumFocus=$albumFocus photoFocus=$photoFocus editorColumn=$editorColumn " +
            "editorElement=$editorElement editorControl=$editorControl editorMoveMode=$editorMoveMode " +
            "settingsCategory=$settingsCategory settingsColumn=$settingsColumn settingsControl=$settingsControl " +
            "interfaceFont=$interfaceFont slideshowFont=$slideshowFont interfaceScale=$interfaceFontScale settingsScroll=$settingsScroll editing=$settingsEditing fixedImage=$fixedImage " +
            "rulesOpen=$advancedRulesOpen diagnostics=$interactionDiagnostics " +
            "slideshow=$slideshow paused=$paused currentPhoto=$currentPhoto quickMenu=$quickMenuVisible " +
            "autonomous=$autonomousSlideshow analysis=$backgroundAnalysisDone/$backgroundAnalysisTotal " +
            "decodeFailures=$decodeFailureCount failedMedia=${failedMediaUris.size} " +
            "night=${isNightModeActive()} memory=${memoryDiagnostics()}"

    private fun virtualAlbums(item: PhotoItem): Set<String> =
        VirtualAlbumPolicy.labels(
            favorite = favorites.contains(item.uri.toString()),
            takenAt = item.takenAt,
            location = item.location,
            scene = sceneCache[item.uri.toString()].orEmpty(),
            qualityScore = qualityCache[item.uri.toString()]?.score
        )

    private fun allAlbumKeys(item: PhotoItem): Set<String> =
        LinkedHashSet<String>().apply {
            addAll(item.albums)
            addAll(virtualAlbums(item))
        }

    private fun rebuildLibraryIndexes() {
        val albums = linkedMapOf<String, MutableList<PhotoItem>>()
        val sources = linkedMapOf<String, MutableList<PhotoItem>>()
        libraryRevision++
        activePhotosCacheKey = Long.MIN_VALUE
        libraryHasNetworkItems = false
        library.forEach { item ->
            if (NetworkLibrary.isNetworkUri(item.uri)) libraryHasNetworkItems = true
            allAlbumKeys(item).forEach { album ->
                albums.getOrPut(album) { mutableListOf() }.add(item)
            }
            val source = item.sourceLabel.ifBlank { "Source" }
            sources.getOrPut(source) { mutableListOf() }.add(item)
        }
        uriIndex = library.associateBy { it.uri.toString() }
        albumIndex = albums.mapValues { it.value.toList() }
        sourceIndex = sources.mapValues { it.value.toList() }
        if (remoteSourceFilter != null && !sourceIndex.containsKey(remoteSourceFilter)) {
            remoteSourceFilter = null
        }
    }

    private fun tagSource(items: List<PhotoItem>, label: String): List<PhotoItem> =
        items.map { item ->
            if (item.sourceLabel.isBlank()) item.copy(sourceLabel = label) else item
        }

    private fun refreshLibraryState(resetCurrent: Boolean) {
        library = library.filter { MediaTypeDetector.isPhotoType(it.mediaType) }
        rebuildLibraryIndexes()
        selectedAlbums.clear()
        val allAlbums = albumIndex.keys.toList()
        selectedAlbums.addAll(allAlbums.filterNot { it in hiddenAlbums })

        if (resetCurrent) {
            currentPhoto = prefs.getInt("resume_index", 0).coerceAtLeast(0)
            val activeNow = activePhotos()
            if (resumeUri.isNotBlank()) {
                val restored = activeNow.indexOfFirst { it.uri.toString() == resumeUri }
                if (restored >= 0) currentPhoto = restored
            }
        }

        albumFocus = 0
        photoFocus = 0
        loadingText = null
        if (!automationMode) preload(library.filterNot { DriveRemoteLibrary.isRemote(it.uri) }.take(48).map { it.uri })
        val count = activePhotos().size
        if (count > 0) currentPhoto = currentPhoto.coerceIn(0, count - 1)
        else currentPhoto = 0

        val labels = library.map { it.sourceLabel.ifBlank { "Source" } }.distinct()
        sourceName = when (labels.size) {
            0 -> "Aucune source"
            1 -> labels.first()
            else -> "Sources multiples (" + labels.size + ")"
        }

        invalidate()
        scheduleInactivity()
    }

    fun setLibrary(
        items: List<PhotoItem>,
        exactAlbums: Boolean,
        sourceName: String = if (exactAlbums) "Google Photos / Takeout" else "Sélection"
    ) {
        synchronized(bitmapCache) {
            bitmapCache.clear()
            bitmapLoading.clear()
            bitmapCacheBytes = 0L
        }
        synchronized(highResCache) {
            highResCache.clear()
            highResLoading.clear()
            highResCacheBytes = 0L
        }
        synchronized(gifCache) {
            gifCache.clear()
            gifLoading.clear()
        }
        softSource = null
        softBitmap = null

        failedMediaUris.clear()
        decodeFailureCount = 0
        lastDecodeFailure = ""
        library = tagSource(items, sourceName)
        this.exactAlbums = exactAlbums
        this.sourceName = sourceName
        refreshLibraryState(resetCurrent = true)
        scheduleBackgroundAnalysis()

        val count = activePhotos().size
        if (!automationMode && resumeWasSlideshow && count > 0) {
            handler.postDelayed({
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
        } else if (!automationMode && startDirectly && count > 0) {
            handler.postDelayed({ if (!slideshow) startSlideshow() }, 450)
        }
    }

    fun upsertSourceLibrary(
        items: List<PhotoItem>,
        exactAlbums: Boolean = false,
        fallbackSourceName: String = "Source"
    ) {
        val label = items.firstOrNull()?.sourceLabel?.takeIf { it.isNotBlank() } ?: fallbackSourceName
        val tagged = tagSource(items, label)
        val wasEmpty = library.isEmpty()
        val retained = library.filterNot { it.sourceLabel == label }
        library = retained + tagged
        this.exactAlbums = if (wasEmpty) exactAlbums else this.exactAlbums && exactAlbums
        refreshLibraryState(resetCurrent = false)
    }

    fun removeSourceByLabel(label: String) {
        library = library.filterNot { it.sourceLabel == label }
        refreshLibraryState(resetCurrent = false)
    }

    fun replaceNetworkLibraries(items: List<PhotoItem>) {
        val retained = library.filterNot { NetworkLibrary.isNetworkUri(it.uri) }
        library = retained + items
        exactAlbums = false
        refreshLibraryState(resetCurrent = false)
    }
    fun startAsDream(items: List<PhotoItem>, exactAlbums: Boolean, sourceName: String) {
        setLibrary(items, exactAlbums = exactAlbums, sourceName = sourceName)
        if (activePhotos().isNotEmpty()) {
            handler.postDelayed({ startSlideshow() }, 150)
        }
    }

    private fun preload(uris: List<Uri>, targetWidth: Int = 1200, targetHeight: Int = 900) {
        if (executor.isShutdown || automationMode) return
        uris.distinct().forEach { uri ->
            val key = uri.toString()
            if ((driveRetryAfter[key] ?: 0L) > android.os.SystemClock.elapsedRealtime()) return@forEach
            if (failedMediaUris.contains(key)) return@forEach
            val alreadyCached = synchronized(bitmapCache) { bitmapCache.containsKey(key) }
            if (alreadyCached || !bitmapLoading.add(key)) return@forEach

            executor.execute {
                try {
                    val bmp = decodeThumb(uri, targetWidth, targetHeight)
                    if (bmp != null) {
                        driveRetryAfter.remove(key)
                        synchronized(bitmapCache) {
                            bitmapCache.remove(key)?.let { old ->
                                bitmapCacheBytes -= old.allocationByteCount.toLong()
                            }
                            bitmapCache[key] = bmp
                            bitmapCacheBytes += bmp.allocationByteCount.toLong()
                            while (bitmapCacheBytes > bitmapCacheLimitBytes && bitmapCache.size > 1) {
                                val first = bitmapCache.entries.firstOrNull() ?: break
                                bitmapCacheBytes -= first.value.allocationByteCount.toLong()
                                bitmapCache.remove(first.key)
                            }
                        }
                        postInvalidate()
                    } else {
                        registerDecodeFailure(uri, "Image illisible ou format non pris en charge")
                    }
                } finally {
                    bitmapLoading.remove(key)
                }
            }
        }
    }

    private fun scheduleBackgroundAnalysis() {
        val generation = ++backgroundAnalysisGeneration
        if (!backgroundAnalysis || automationMode || library.isEmpty()) {
            backgroundAnalysisDone = 0
            backgroundAnalysisTotal = 0
            return
        }
        val snapshot = library.filter { item ->
            item.mediaType == "image" && !NetworkLibrary.isNetworkUri(item.uri) && !DriveRemoteLibrary.isRemote(item.uri)
        }
        backgroundAnalysisDone = 0
        backgroundAnalysisTotal = snapshot.size
        analysisExecutor.execute {
            for ((index, item) in snapshot.withIndex()) {
                if (generation != backgroundAnalysisGeneration || Thread.currentThread().isInterrupted) break
                val key = item.uri.toString()
                val mime = runCatching { context.contentResolver.getType(item.uri) }.getOrNull().orEmpty()
                val signature = MediaMetadataStore.signature(context, item.uri, mime)
                val cachedScene = MediaMetadataStore.readScene(context, item.uri, signature)
                val cachedQuality = MediaMetadataStore.readQuality(context, item.uri, signature)
                if (cachedScene != null) sceneCache[key] = cachedScene.label
                if (cachedQuality != null) qualityCache[key] = cachedQuality

                if (cachedScene == null || cachedQuality == null) {
                    val bmp = runCatching { decodeThumb(item.uri) }.getOrNull()
                    if (bmp != null && !bmp.isRecycled) {
                        val scene = cachedScene?.label ?: OnDeviceSceneLabeler.classify(bmp).also { scene ->
                            sceneCache[key] = scene
                            MediaMetadataStore.writeScene(
                                context,
                                item.uri,
                                signature,
                                MediaMetadataStore.CachedScene(scene, .65f)
                            )
                        }
                        if (cachedScene != null) sceneCache[key] = scene

                        if (cachedQuality == null) {
                            val analyzed = ImageQualityAnalyzer.analyze(
                                bitmap = bmp,
                                portrait = scene == "Portrait",
                                favorite = false
                            )
                            val quality = MediaMetadataStore.CachedQuality(
                                score = analyzed.score,
                                sharpness = analyzed.sharpness,
                                exposure = analyzed.exposure,
                                contrast = analyzed.contrast,
                                noiseQuality = analyzed.noiseQuality
                            )
                            qualityCache[key] = quality
                            MediaMetadataStore.writeQuality(context, item.uri, signature, quality)
                        }
                        smartCropAnchors(item, bmp)
                        if (bmp !== demoBitmap && !bmp.isRecycled) bmp.recycle()
                    }
                }
                backgroundAnalysisDone = index + 1
                if ((index + 1) % 8 == 0) postInvalidate()
            }
            if (generation == backgroundAnalysisGeneration) {
                post {
                    rebuildLibraryIndexes()
                    if (slideshow && (randomOrder || autonomousSlideshow || effectiveSmartSelectionMode() != SmartSelectionPolicy.OFF)) {
                        rebuildShuffleBag()
                    }
                    postInvalidate()
                }
            } else {
                postInvalidate()
            }
        }
    }

    private fun registerDecodeFailure(uri: Uri, reason: String) {
        val key = uri.toString()
        if (DriveRemoteLibrary.isRemote(uri)) {
            // A failed network/cache read must not permanently remove a Drive
            // photo or stop a slideshow whose photos have not downloaded yet.
            driveRetryAfter[key] = android.os.SystemClock.elapsedRealtime() + 15_000L
            lastDecodeFailure = "Lecture Drive interrompue ; nouvel essai automatique"
            postDelayed({ if (!lifecycleClosed) invalidate() }, 15_050L)
            return
        }
        val transientNetworkFailure =
            NetworkLibrary.isNetworkUri(uri) && NetworkLibrary.isTemporarilyOffline()

        if (transientNetworkFailure) {
            lastDecodeFailure = "Source réseau temporairement indisponible"
            Log.w("PhotoTVDecode", "$lastDecodeFailure: $key")
        } else {
            val added = failedMediaUris.add(key)
            if (!added) return
            decodeFailureCount++
            lastDecodeFailure = reason
            Log.w("PhotoTVDecode", "$reason: $key")
        }

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
                if (movie != null) {
                    gifCache[key] = movie
                    while (gifCache.size > 12) {
                        val first = gifCache.entries.firstOrNull() ?: break
                        gifCache.remove(first.key)
                    }
                }
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

        val scale = when (displayImageMode()) {
            1, 3 -> min(w / mw, h / mh)
            2 -> min(1f, min(w / mw, h / mh))
            else -> max(w / mw, h / mh)
        }

        if (displayImageMode() == 3) {
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
        if (DriveRemoteLibrary.isRemote(uri)) DriveRemoteLibrary.open(context, uri)
        else if (NetworkLibrary.isNetworkUri(uri)) NetworkLibrary.open(context, uri)
        else context.contentResolver.openInputStream(uri)

    private fun decodeThumb(uri: Uri, targetWidth: Int = 1200, targetHeight: Int = 900): Bitmap? {
        return runCatching {
            val key = uri.toString()
            val mediaType = uriIndex[key]?.mediaType
                ?: context.contentResolver.getType(uri).orEmpty().let {
                    if (it.startsWith("video/")) "video" else "image"
                }
            if (mediaType == "video") {
                if (NetworkLibrary.isNetworkUri(uri)) return@runCatching demoBitmap
                val r = MediaMetadataRetriever()
                try {
                    if (DriveRemoteLibrary.isRemote(uri)) r.setDataSource(DriveRemoteLibrary.materialize(context,uri).absolutePath)
                    else r.setDataSource(context, uri)
                    r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: demoBitmap
                } finally {
                    runCatching { r.release() }
                }
            } else {
                val item = uriIndex[key]
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
                val signature = MediaMetadataStore.signature(context, uri, mime)
                val persistentKey = buildString {
                    append(key)
                    append('|').append(signature.modified)
                    append('|').append(signature.size)
                    append('|').append(item?.width ?: 0).append('x').append(item?.height ?: 0)
                    append('|').append(item?.takenAt ?: 0L)
                    append("|tv${targetWidth}x${targetHeight}")
                }
                if (signature.cacheable) {
                    ThumbnailDiskCache.read(context, persistentKey)?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return@runCatching it }
                    }
                }
                val decoded = decodeBitmap(uri, targetWidth, targetHeight)
                if (decoded != null && !decoded.isRecycled) {
                    runCatching {
                        java.io.ByteArrayOutputStream().use { stream ->
                            if (signature.cacheable &&
                                decoded.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                            ) {
                                ThumbnailDiskCache.write(context, persistentKey, stream.toByteArray())
                            }
                        }
                    }
                }
                decoded
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
            if (decoded != null) {
                val oriented = applyExifOrientation(uri, decoded)
                val maxPixels = (targetWidth.toLong() * targetHeight.toLong() * 5L / 4L)
                    .coerceAtLeast(if (targetWidth <= 600 && targetHeight <= 450) 150_000L else 1_500_000L)
                val pixels = oriented.width.toLong() * oriented.height.toLong()
                if (pixels > maxPixels) {
                    val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
                    val outW = max(1, (oriented.width * scale).toInt())
                    val outH = max(1, (oriented.height * scale).toInt())
                    return Bitmap.createScaledBitmap(oriented, outW, outH, true).also { scaled ->
                        if (scaled !== oriented) oriented.recycle()
                    }
                }
                return oriented
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = runCatching {
                if (DriveRemoteLibrary.isRemote(uri)) {
                    ImageDecoder.createSource(DriveRemoteLibrary.materialize(context,uri))
                } else if (NetworkLibrary.isNetworkUri(uri)) {
                    NetworkLibrary.materialize(context, uri)?.let(ImageDecoder::createSource)
                } else {
                    ImageDecoder.createSource(context.contentResolver, uri)
                }
            }.getOrNull()

            if (source != null) {
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
        if (lifecycleClosed || highResExecutor.isShutdown) return
        val key = uri.toString()
        if ((driveRetryAfter[key] ?: 0L) > android.os.SystemClock.elapsedRealtime()) return
        if (failedMediaUris.contains(key)) return
        val mediaType = uriIndex[key]?.mediaType
        if (mediaType != null && mediaType != "image") return
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
                    }
                    highResCache[key] = bmp
                    highResCacheBytes += bmp.allocationByteCount.toLong()
                    while (highResCacheBytes > highResCacheLimitBytes && highResCache.size > 1) {
                        val first = highResCache.entries.firstOrNull() ?: break
                        highResCacheBytes -= first.value.allocationByteCount.toLong()
                        highResCache.remove(first.key)
                    }
                }
            }
            if (bmp != null) { driveRetryAfter.remove(key); postInvalidate() }
            else if (DriveRemoteLibrary.isRemote(uri)) registerDecodeFailure(uri, "Lecture Drive interrompue")
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
        val nowMs = System.currentTimeMillis()
        val timeBucket = if (libraryHasNetworkItems) nowMs / 2_000L else nowMs / 60_000L
        var key = 17L
        fun mix(value: Int) { key = key * 31L + value.toLong() }
        key = key * 31L + libraryRevision
        key = key * 31L + timeBucket
        mix(remoteSourceFilter?.hashCode() ?: 0)
        mix(selectedAlbums.hashCode())
        mix(hiddenAlbums.hashCode())
        mix(excludedUris.hashCode())
        mix(sessionExcludedUris.hashCode())
        mix(failedMediaUris.hashCode())
        mix(if (favoritesOnly) favorites.hashCode() else 0)
        mix(if (favoritesOnly) 1 else 0)
        mix(albumRules.hashCode())
        mix(sourceRules.hashCode())
        if (key == activePhotosCacheKey) return activePhotosCache

        val now = java.util.Calendar.getInstance()
        val filtered = library.filter { item ->
            (remoteSourceFilter == null || item.sourceLabel.ifBlank { "Source" } == remoteSourceFilter) &&
                sourceAllowed(item.sourceLabel.ifBlank { "Source" }, now) &&
                allAlbumKeys(item).any {
                    selectedAlbums.contains(it) &&
                        !hiddenAlbums.contains(it) &&
                        albumAllowed(it, now)
                } &&
                NetworkLibrary.canUseOffline(context, item.uri) &&
                !excludedUris.contains(item.uri.toString()) &&
                !sessionExcludedUris.contains(item.uri.toString()) &&
                !failedMediaUris.contains(item.uri.toString()) &&
                (!favoritesOnly || favorites.contains(item.uri.toString()))
        }
        activePhotosCache = filtered
        activePhotosCacheKey = key
        return filtered
    }

    private fun currentItem(): PhotoItem? {
        val items = activePhotos()
        if (items.isEmpty()) return null
        currentPhoto = currentPhoto.coerceIn(0, items.lastIndex)
        return items[currentPhoto]
    }

    private fun currentPhotoLoaded(): Boolean {
        if (automationMode) return true
        val key=currentItem()?.uri?.toString() ?: return false
        return synchronized(highResCache) { highResCache[key]?.isRecycled == false } ||
            synchronized(bitmapCache) { bitmapCache[key]?.isRecycled == false }
    }

    private fun currentBitmap(): Bitmap? {
        if (automationMode) return demoBitmap
        val item = currentItem()
        return if (item == null) emptySlideshowBackground else {
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
                emptySlideshowBackground
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        synchronized(highResCache) {
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
        contentDescription = accessibilityDescription()
        if (width <= 0 || height <= 0) return
        lightInterface = !slideshow && page != 0 && !darkTheme
        canvas.drawColor(if (lightInterface) Color.rgb(238, 243, 249) else Color.BLACK)
        val scale = viewportScale()
        canvas.save()
        canvas.translate(viewportLeft(), viewportTop())
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, 1280f, 720f)

        if (slideshow) drawSlideshow(canvas)
        else {
            when (page) {
                0 -> drawPreview(canvas)
                1 -> drawPhotos(canvas)
                2 -> drawEditor(canvas)
                3 -> drawSettings(canvas)
                else -> drawPreview(canvas)
            }
            if (page != 0 || previewControlsVisible || navFocus) drawBottomNav(canvas)
        }

        if ((slideshow || page == 0) && currentItem()?.uri?.let { DriveRemoteLibrary.isRemote(it) } == true && !currentPhotoLoaded()) {
            val key=currentItem()?.uri.toString()
            round(canvas, 300f, 310f, 980f, 410f, 18f, Color.argb(205, 8, 22, 38))
            text(canvas, if(driveRetryAfter.containsKey(key)) "Lecture Drive interrompue" else "Chargement de la photo depuis Drive…", 640f, 350f, 22f, Color.WHITE, 1, 1)
            text(canvas, if(driveRetryAfter.containsKey(key)) "Nouvel essai automatique · ← → pour changer de photo" else "La photo apparaîtra entière dès qu’elle sera prête", 640f, 385f, 14f, Color.WHITE, 0, 1)
        }
        if (interactionDiagnostics) drawInteractionDiagnostics(canvas)
        loadingText?.let { drawLoading(canvas, it) }
        if (historyOverlayVisible) drawHistoryOverlay(canvas)
        if (memoriesOverlayVisible) drawMemoriesOverlay(canvas)
        if (technicalDiagnosticsVisible) drawTechnicalDiagnosticsOverlay(canvas)
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

    private fun memoriesItems(): List<PhotoItem> {
        val items = activePhotos()
        if (items.isEmpty()) return emptyList()
        val now = java.util.Calendar.getInstance()

        return when (memoriesMode) {
            0 -> items
                .filter { MemoriesPolicy.isSameDayPreviousYear(it.takenAt, now) }
                .sortedByDescending { it.takenAt }
                .take(12)
            1 -> items
                .filter { MemoriesPolicy.isSameMonth(it.takenAt, now) }
                .sortedByDescending { it.takenAt }
                .take(12)
            else -> items.sortedByDescending { item ->
                MemoriesPolicy.bestScore(
                    favorite = favorites.contains(item.uri.toString()),
                    width = item.width,
                    height = item.height
                )
            }.take(12)
        }
    }

    private fun drawMemoriesOverlay(c: Canvas) {
        fill(c, 0f, 0f, 1280f, 720f, Color.argb(220, 0, 0, 0))
        round(c, 85f, 50f, 1195f, 665f, 22f, Color.rgb(9, 22, 35))
        strokeRound(c, 85f, 50f, 1195f, 665f, 22f, Color.rgb(58, 91, 124), 1.5f)
        text(c, "Souvenirs", 120f, 92f, 24f, Color.WHITE, 1)

        val tabs = listOf("Ce jour-là", "Ce mois-ci", "Meilleures")
        tabs.forEachIndexed { index, label ->
            val x = 120f + index * 250f
            val selected = memoriesMode == index
            if (selected) {
                gradientRound(c, x, 110f, x + 220f, 153f, 12f, Color.rgb(12, 128, 255), Color.rgb(7, 91, 237))
            } else {
                round(c, x, 110f, x + 220f, 153f, 12f, Color.rgb(18, 37, 57))
            }
            if (memoriesTabFocus && selected) {
                strokeRound(c, x - 2f, 108f, x + 222f, 155f, 13f, Color.rgb(178, 220, 255), 2.5f)
            }
            text(c, label, x + 110f, 138f, 13f, Color.WHITE, 1, 1)
        }

        val items = memoriesItems()
        if (items.isEmpty()) {
            text(
                c,
                when (memoriesMode) {
                    0 -> "Aucune photo prise à cette date dans les années précédentes."
                    1 -> "Aucune photo datée pour ce mois."
                    else -> "Aucune photo disponible."
                },
                640f, 375f, 16f, Color.rgb(180, 198, 218), 0, 1
            )
            text(c, "← → : catégorie • Retour : fermer", 640f, 625f, 11f, Color.rgb(147, 168, 193), 0, 1)
            return
        }

        memoriesIndex = memoriesIndex.coerceIn(0, items.lastIndex)
        items.forEachIndexed { index, item ->
            val col = index % 4
            val row = index / 4
            val x = 120f + col * 255f
            val y = 180f + row * 135f
            val key = item.uri.toString()
            val bmp = synchronized(highResCache) { highResCache[key] }
                ?: synchronized(bitmapCache) { bitmapCache[key] }
            if (bmp == null) preload(listOf(item.uri))
            round(c, x, y, x + 230f, y + 112f, 10f, Color.rgb(6, 16, 27))
            if (bmp != null && !bmp.isRecycled) {
                drawBitmapCenterCrop(c, bmp, x + 3f, y + 3f, 224f, 82f, 7f)
            }
            if (!memoriesTabFocus && index == memoriesIndex) {
                strokeRound(c, x - 3f, y - 3f, x + 233f, y + 115f, 12f, Color.rgb(76, 170, 255), 3f)
            }
            ellipsizedText(c, displayedTitle(item.title), x + 9f, y + 103f, 212f, 10.5f, Color.WHITE)
        }
        text(c, "↑ vers catégories • OK : ouvrir • Retour : fermer", 640f, 625f, 11f, Color.rgb(147, 168, 193), 0, 1)
    }

    private fun recentItemsForOverlay(): List<PhotoItem> =
        recentUris.toList().asReversed().mapNotNull { uriIndex[it] }.distinctBy { it.uri }.take(12)

    private fun drawHistoryOverlay(c: Canvas) {
        fill(c, 0f, 0f, 1280f, 720f, Color.argb(205, 0, 0, 0))
        round(c, 115f, 70f, 1165f, 650f, 20f, Color.rgb(11, 24, 38))
        strokeRound(c, 115f, 70f, 1165f, 650f, 20f, Color.rgb(52, 83, 116), 1.5f)
        text(c, "Historique récent", 145f, 112f, 23f, Color.WHITE, 1)
        val items = recentItemsForOverlay()
        if (items.isEmpty()) {
            text(c, "Aucune photo récente.", 640f, 360f, 17f, Color.rgb(180, 194, 211), 0, 1)
            return
        }
        historyOverlayIndex = historyOverlayIndex.coerceIn(0, items.lastIndex)
        items.forEachIndexed { i, item ->
            val col = i % 4
            val row = i / 4
            val x = 145f + col * 245f
            val y = 145f + row * 155f
            val bmp = synchronized(highResCache) { highResCache[item.uri.toString()] }
                ?: synchronized(bitmapCache) { bitmapCache[item.uri.toString()] }
            if (bmp == null) preload(listOf(item.uri))
            round(c, x, y, x + 220f, y + 126f, 10f, Color.rgb(7, 17, 28))
            if (bmp != null && !bmp.isRecycled) drawBitmapCenterCrop(c, bmp, x + 3f, y + 3f, 214f, 92f, 7f)
            if (i == historyOverlayIndex) strokeRound(c, x - 3f, y - 3f, x + 223f, y + 129f, 12f, Color.rgb(52, 154, 255), 3f)
            ellipsizedText(c, displayedTitle(item.title), x + 10f, y + 115f, 200f, 11f, Color.WHITE)
        }
        text(c, "Flèches : choisir • OK : ouvrir • Retour : fermer", 640f, 625f, 11f, Color.rgb(147, 168, 193), 0, 1)
    }

    private fun drawTechnicalDiagnosticsOverlay(c: Canvas) {
        fill(c, 0f, 0f, 1280f, 720f, Color.argb(215, 0, 0, 0))
        round(c, 150f, 80f, 1130f, 640f, 20f, Color.rgb(9, 22, 35))
        strokeRound(c, 150f, 80f, 1130f, 640f, 20f, Color.rgb(58, 91, 124), 1.5f)
        text(c, "Diagnostics techniques", 185f, 125f, 23f, Color.WHITE, 1)
        val lines = diagnosticReport().lines().filter { it.isNotBlank() }.take(17)
        lines.forEachIndexed { i, line ->
            ellipsizedText(c, line, 190f, 165f + i * 25f, 900f, 12f, if (i == 0) Color.rgb(109, 181, 255) else Color.rgb(205, 216, 230))
        }
        text(c, "OK ou Retour pour fermer", 640f, 610f, 11f, Color.rgb(147, 168, 193), 0, 1)
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
                box(370f, 200f, 918f, 508.25f, "Déplacer l'élément", green)
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
                if (settingsCategory == 8 && !advancedRulesOpen) {
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
        val wasLight = lightInterface
        lightInterface = false
        renderingSlideshowText = true
        val savedProgress = transitionProgress
        transitionProgress = 1f
        drawSlideshow(c)
        transitionProgress = savedProgress
        renderingSlideshowText = false
        p.clearShadowLayer()
        if (previewControlsVisible || navFocus) {
            val hint = if (navFocus) "← → Onglets  ·  ↑ Diaporama  ·  OK Masquer" else "← → Photos  ·  OK Diaporama"
            p.typeface = interfaceTypeface(0); p.textSize = 13f * interfaceFontScale
            val hintW = p.measureText(hint) + 32f
            val hintH = 30f * interfaceFontScale
            val hintX = 640f - hintW / 2f
            round(c,hintX,18f,hintX+hintW,18f+hintH,12f,Color.argb(175,7,20,36))
            strokeRound(c,hintX,18f,hintX+hintW,18f+hintH,12f,Color.argb(60,220,235,255),1f)
            text(c,hint,640f,18f+hintH/2f+4.5f*interfaceFontScale,13f,Color.rgb(233,241,252),0,1)
            val button = previewLaunchRect()
            gradientRound(c,button.left,button.top,button.right,button.bottom,14f,Color.rgb(13,141,108),Color.rgb(12,99,162))
            if (!navFocus) strokeRound(c,button.left-2f,button.top-2f,button.right+2f,button.bottom+2f,16f,Color.rgb(140,247,215),2f)
            drawBottomIcon(c,button.left+26f,button.centerY(),4)
            text(c,"Diaporama",button.left+52f,button.centerY()+6f*interfaceFontScale,17f,Color.WHITE,1)
        }
        lightInterface = wasLight
    }

    private fun previewLaunchRect(): RectF {
        p.typeface = interfaceTypeface(1); p.textSize = 17f * interfaceFontScale
        val w = p.measureText("Diaporama") + 76f
        val h = 46f * interfaceFontScale
        return RectF(1248f-w,620f-h,1248f,620f)
    }

    private fun albumColumns() = 3 + albumDensity
    private fun albumWindowStart(total: Int): Int {
        val columns = albumColumns()
        val rows = (total + columns - 1) / columns
        return (albumFocus / columns - 1).coerceIn(0,max(0,rows-2)) * columns
    }
    private fun adjustAlbumDensity(dir: Int) {
        albumDensity = (albumDensity + dir + 3) % 3
        savePrefs(); invalidate()
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
        drawSourceCard(c, 47f, sourceY, 388f, 102f, "Google Photos", "Connexion et import", 0, sourceFocus == 0 && photosRow == 0 && !navFocus, true)
        drawSourceCard(c, 450f, sourceY, 362f, 102f, "Choisir un dossier", "Stockage local", 1, sourceFocus == 1 && photosRow == 0 && !navFocus, false)
        drawSourceCard(c, 828f, sourceY, 410f, 102f, "Sélectionner des photos", "Choisir plusieurs fichiers", 2, sourceFocus == 2 && photosRow == 0 && !navFocus, false)

        val albums = albumPairs()
        val albumsTitle = if (library.isEmpty() || exactAlbums) "Mes albums Google Photos" else "Albums et dossiers"
        text(c, albumsTitle, 45f, 250f, 19f, Color.WHITE, 1)
        val densityFocus = photosRow == 3 && !navFocus
        val wasLight = lightInterface
        if (densityFocus) lightInterface = false
        round(c,780f,222f,1238f,262f,12f,if(densityFocus) Color.rgb(17,91,174) else Color.rgb(14,30,48))
        strokeRound(c,780f,222f,1238f,262f,12f,Color.rgb(81,148,212),if(densityFocus) 2f else 1f)
        text(c,"‹  Densité : ${albumDensityLabels[albumDensity]}  ›",1009f,248f,15f,Color.WHITE,1,1)
        lightInterface = wasLight

        val columns = albumColumns()
        val cardW = (1191f-(columns-1)*14f)/columns
        val cardH = 134f
        if (albums.isNotEmpty()) albumFocus = albumFocus.coerceIn(0,albums.lastIndex)
        val albumStart = albumWindowStart(albums.size)
        albumHitBoxes.clear()
        albums.drop(albumStart).take(columns*2).forEachIndexed { slot,pair ->
            val index = albumStart+slot
            val x = 47f+(slot%columns)*(cardW+14f)
            val y = 270f+(slot/columns)*148f
            drawAlbumCard(c,x,y,cardW,cardH,pair.first,pair.second,index,photosRow==1 && albumFocus==index && !navFocus)
            albumHitBoxes.add(index to RectF(x,y,x+cardW,y+cardH))
        }
        drawScrollIndicator(c,1245f,270f,552f,((albums.size+columns-1)/columns).toFloat(),2f,(albumStart/columns).toFloat())
        val end = min(albumStart+columns*2,albums.size)
        ellipsizedText(c,"Flèches : parcourir / défiler  ·  OK : masquer / réafficher",47f,566f+15f*interfaceFontScale,1000f,17f,Color.WHITE,1)
        text(c,if(albums.isEmpty()) "" else "${albumStart+1}–$end / ${albums.size}",1238f,566f+15f*interfaceFontScale,15f,Color.rgb(130,153,180),0,2)
        ellipsizedText(c,"$sourceName • ${library.size} médias • ${albums.size} albums",47f,602f+18f*interfaceFontScale,1188f,16f,Color.rgb(130,153,180))

    }

    private fun albumPairs(): List<Pair<String, Int>> {
        val base = if (library.isEmpty()) {
            if (automationMode) mockAlbums else emptyList()
        } else {
            albumIndex.entries.map { it.key to it.value.size }
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
        if (library.isEmpty()) return if (automationMode) List(6) { null } else emptyList()
        val name = currentAlbumName()
        return albumIndex[name].orEmpty().map { it as PhotoItem? }
    }
    private fun albumDisplay(item: PhotoItem?): String {
        if (item == null) return if (automationMode) "Norvège 2026" else ""
        val preferred = item.albums.filter { selectedAlbums.contains(it) && !hiddenAlbums.contains(it) }
        val values = if (preferred.isNotEmpty()) preferred else item.albums.toList()
        return values.joinToString(" • ").ifBlank { "Album" }
    }

    private fun displayedTitle(title: String): String = PhotoTitleDisplay.format(title, hideTitleExtensions)

    private fun metadataValues(item: PhotoItem? = currentItem()): List<String> = listOf(
        displayedTitle(item?.title ?: if (automationMode) "001. Opéra d'Oslo" else "Aucune photo chargée"),
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
        val h = 78f * interfaceFontScale.coerceAtLeast(1f)
        val gap = 9f
        val values = metadataValues()
        val visibleCount = (480f / (h + gap)).toInt().coerceAtLeast(1)
        val startY = 351f - (visibleCount * (h + gap) - gap) / 2f
        val first = (editorElement - visibleCount / 2).coerceIn(0, max(0, elementNames.size - visibleCount))
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

        drawScrollIndicator(c,leftX+leftW-6f,startY,startY+visibleCount*(h+gap)-gap,elementNames.size.toFloat(),visibleCount.toFloat(),first.toFloat())

        val canvasX = 370f
        val canvasY = 200f
        val canvasW = 548f
        val canvasH = canvasW * 9f / 16f
        round(c, canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, 5f, Color.rgb(10, 22, 32))
        text(c, "Aperçu complet · écran TV 16/9", canvasX + canvasW / 2f, 156f, 15f, Color.WHITE, 1, 1)
        c.save()
        c.translate(canvasX, canvasY)
        c.scale(canvasW / 1280f, canvasW / 1280f)
        c.clipRect(0f, 0f, 1280f, 720f)
        drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, currentBitmap())
        if (gridSnap) drawEditorGrid(c, 0f, 0f, 1280f, 720f)
        drawBottomGradient(c, 0f, 0f, 1280f, 720f)
        drawEditorOverlays(c, 0f, 0f, 1280f, 720f)
        c.restore()
        strokeRound(c, canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, 5f, if(editorColumn==1 && !navFocus) Color.rgb(76,190,255) else Color.rgb(81,111,145), if(editorColumn==1 && !navFocus) 3f else 1f)
        ellipsizedText(c, if(editorMoveMode) "Flèches : déplacer l’élément" else "← Éléments   ·   → Paramètres   ·   ↓ Menu", canvasX + canvasW / 2f, 548f,canvasW,12f,Color.rgb(177,191,209),0,1)
        ellipsizedText(c, "Appui long sur OK : déplacer · OK : terminer", canvasX + canvasW / 2f,574f,canvasW,14f,Color.rgb(130,153,180),1,1)

        val panelX = 940f
        val panelY = 64f
        val panelW = 308f
        val panelH = 574f
        round(c, panelX, panelY, panelX + panelW, panelY + panelH, 12f, Color.rgb(17, 29, 43))
        strokeRound(c, panelX, panelY, panelX + panelW, panelY + panelH, 12f, Color.rgb(38, 55, 74), 1f)
        drawEditorPanel(c, panelX, panelY, panelW)
    }

    private fun drawScrollIndicator(c: Canvas,right:Float,top:Float,bottom:Float,total:Float,visible:Float,offset:Float) {
        if(total<=visible || visible<=0f || bottom<=top) return
        val height=bottom-top
        val thumb=max(24f,height*visible/total).coerceAtMost(height)
        val y=top+(height-thumb)*(offset/(total-visible)).coerceIn(0f,1f)
        round(c,right-5f,top,right,bottom,3f,Color.rgb(57,79,105))
        round(c,right-5f,y,right,y+thumb,3f,Color.rgb(69,163,248))
    }

    private val wrappedTextCache = linkedMapOf<String,List<String>>()
    private fun wrappedText(value:String,width:Float):List<String> {
        val key="$width:${p.textSize}:${p.typeface?.hashCode()}:$value"
        wrappedTextCache[key]?.let { return it }
        val lines=TextWrapping.lines(value,width) { p.measureText(it) }
        if(wrappedTextCache.size>=24) wrappedTextCache.remove(wrappedTextCache.keys.first())
        wrappedTextCache[key]=lines
        return lines
    }

    private fun titleGeometry(value:String,x:Float,y:Float): Pair<List<String>,RectF> {
        val margin=24f
        val anchor=x.coerceIn(margin,1280f-margin)
        val width=when(p.textAlign) {
            Paint.Align.CENTER -> 2f*min(anchor-margin,1280f-margin-anchor)
            Paint.Align.RIGHT -> anchor-margin
            else -> 1280f-margin-anchor
        }.coerceAtLeast(96f).coerceAtMost(1232f)
        var lines=wrappedText(value,width)
        while(lines.size*(p.fontMetrics.descent-p.fontMetrics.ascent)*1.12f>672f && p.textSize>8f) {
            p.textSize*=.9f
            lines=wrappedText(value,width)
        }
        val fm=p.fontMetrics
        val lineH=(fm.descent-fm.ascent)*1.12f
        val height=(lines.size-1)*lineH+fm.descent-fm.ascent
        val top=(y+fm.ascent-(lines.size-1)*lineH).coerceIn(margin,max(margin,720f-margin-height))
        val left=when(p.textAlign) { Paint.Align.CENTER -> anchor-width/2f; Paint.Align.RIGHT -> anchor-width; else -> anchor }.coerceIn(margin,1280f-margin-width)
        val actualWidth=lines.maxOf { p.measureText(it) }.coerceAtMost(width)
        val actualLeft=when(p.textAlign) { Paint.Align.CENTER -> left+(width-actualWidth)/2f;Paint.Align.RIGHT -> left+width-actualWidth;else -> left }
        return lines to RectF(actualLeft,top,actualLeft+actualWidth,top+height)
    }

    private fun drawFullTitle(c:Canvas,value:String,x:Float,y:Float): RectF {
        val (lines,bounds)=titleGeometry(value,x,y)
        val fm=p.fontMetrics;val lineH=(fm.descent-fm.ascent)*1.12f
        val anchor=when(p.textAlign) { Paint.Align.CENTER -> bounds.centerX(); Paint.Align.RIGHT -> bounds.right; else -> bounds.left }
        lines.forEachIndexed { i,line -> c.drawText(line,anchor,bounds.top-fm.ascent+i*lineH,p) }
        return bounds
    }

    private fun albumBaseline(title:String,y:Float):Float {
        if(!styles[0].visible || title.isBlank()) return y
        val saved=Paint(p)
        val style=styles[0];applyStylePaint(style)
        p.textAlign=when(style.align) { 1 -> Paint.Align.CENTER;2 -> Paint.Align.RIGHT;else -> Paint.Align.LEFT }
        val bounds=titleGeometry(title,1280f*style.x/100f,720f*style.y/100f).second
        p.set(saved)
        val top=y+p.fontMetrics.ascent
        return if(top<bounds.bottom && y+p.fontMetrics.descent>bounds.top) bounds.top-14f-p.fontMetrics.descent else y
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

    private fun drawEditorOverlays(c: Canvas, x: Float, y: Float, w: Float, h: Float, highlightSelected: Boolean = true) {
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
            var ty = y + h * s.y / 100f
            applyStylePaint(s)
            p.textSize = s.size
            val align = when (s.align) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
            p.textAlign = align
            if(i==1) ty=albumBaseline(vals[0],ty)
            val titleBounds=if(i==0) drawFullTitle(c,vals[i],tx,ty) else { c.drawText(vals[i],tx,ty,p);null }

            if (highlightSelected && i == editorElement) {
                val width = p.measureText(vals[i])
                val fm = p.fontMetrics
                val left = titleBounds?.left ?: when (align) {
                    Paint.Align.CENTER -> tx - width / 2
                    Paint.Align.RIGHT -> tx - width
                    else -> tx
                }
                val top = (titleBounds?.top ?: (ty + fm.ascent)) - 8f
                val right = (titleBounds?.right ?: (left + width)) + 10f
                val bottom = (titleBounds?.bottom ?: (ty + fm.descent)) + 8f
                strokeRound(c, (titleBounds?.left ?: left) - 5f, top, right, bottom, 2f, Color.rgb(27, 130, 255), 2f)
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
        val st=styles[editorElement]
        ellipsizedText(c, "${elementNames[editorElement]}", x+w/2f,y+32f,w-32f,20f,Color.WHITE,1,1)
        val rows=listOf("Texte" to currentTextForElement(),"Police" to fontName(st.font),"Taille" to "${st.size.toInt()} points", "Couleur" to "Choisir",
            "Position X" to "${st.x.toInt()} %","Position Y" to "${st.y.toInt()} %","Alignement" to listOf("Gauche","Centre","Droite")[st.align],
            "Ombre" to if(st.shadow) "Activée" else "Désactivée","Annuler" to "Dernière modification","Rétablir" to "Dernière modification",
            "Réinitialiser l’élément" to "Valeurs de base","Réinitialiser tout" to "Valeurs de base")
        val rowH=70f*interfaceFontScale.coerceAtLeast(1f)
        p.typeface=interfaceTypeface(0);p.textSize=12f*interfaceFontScale
        val valueLines=wrappedText(rows[0].second,w-48f)
        val heights=rows.mapIndexed { i,_ -> if(i==0) max(rowH,(40f+18f*valueLines.size)*interfaceFontScale) else rowH }
        val starts=heights.runningFold(0f) { total,height -> total+height }
        val viewport=510f
        if(starts[editorControl]<editorScroll) editorScroll=starts[editorControl]
        if(starts[editorControl+1]>editorScroll+viewport) editorScroll=starts[editorControl+1]-viewport
        editorScroll=editorScroll.coerceIn(0f,max(0f,starts.last()-viewport))
        editorHitBoxes.clear(); c.save(); c.clipRect(x+8f,y+50f,x+w-8f,y+560f)
        rows.forEachIndexed { i,row ->
            val yy=y+50f+starts[i]-editorScroll
            val hh=heights[i]
            if(yy+hh>=y+50f && yy<y+560f) {
                val focus=editorColumn==2 && editorControl==i && !navFocus
                round(c,x+12f,yy,x+w-12f,yy+hh-8f,9f,Color.rgb(10,24,38))
                if(focus) strokeRound(c,x+12f,yy,x+w-12f,yy+hh-8f,9f,Color.rgb(105,196,255),3f)
                ellipsizedText(c,row.first,x+23f,yy+25f*interfaceFontScale,w-48f,13f,Color.WHITE,1)
                if(i==0) valueLines.forEachIndexed { line,value -> text(c,value,x+23f,yy+(48f+18f*line)*interfaceFontScale,12f,Color.rgb(156,203,255)) }
                else ellipsizedText(c,row.second,x+23f,yy+48f*interfaceFontScale,w-48f,12f,Color.rgb(156,203,255))
                editorHitBoxes.add(i to android.graphics.RectF(x+12f,max(y+50f,yy),x+w-12f,min(y+560f,yy+hh-8f)))
            }
        }; c.restore()
        drawScrollIndicator(c,x+w-5f,y+50f,y+560f,starts.last(),viewport,editorScroll)
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

    private data class SettingRow(val id: Int, val label: String, val value: String, val action: Boolean = false)

    private fun settingRows(): List<SettingRow> {
        fun yes(v: Boolean) = if (v) "Activé" else "Désactivé"
        fun row(id: Int, label: String, value: String, action: Boolean = false) = SettingRow(id, label, value, action)
        return when (settingsCategory) {
            0 -> listOf(row(10,"Cadrage des photos",imageModeLabel()), row(0,"Durée par photo","$durationSeconds secondes"),
                row(1,"Changement des photos",if(fixedImage) "À la télécommande uniquement" else "Automatique"),
                row(2,"Lecture en boucle",yes(loop)),row(3,"Ordre des photos",if(randomOrder) "Aléatoire" else "Dans l’ordre"),
                row(4,"Transition",transitions[transitionIndex]),row(5,"Durée de transition","${format1(transitionSeconds)} secondes"),row(11,"Intensité du fondu","$fadeIntensityPercent %"),
                row(6,"Effet panoramique",yes(kenBurns)),row(7,"Agrandissement",listOf("Aucun","Léger","Moyen","Fort")[zoomLevel+1]),
                row(8,"Diaporama intelligent autonome",yes(autonomousSlideshow)),row(9,"Thème",if(darkTheme) "Sombre" else "Clair"))
            1 -> elementNames.indices.map { row(it,elementNames[it],yes(when(it) { 2->showDate; 3->showTime; 4->showTemp; else->styles[it].visible }),true) } + row(10,"Masquer les extensions des titres",yes(hideTitleExtensions),true)
            2 -> listOf(row(10,"Police de l’interface",fontName(interfaceFont)),row(13,"Taille du texte de l’interface","${(interfaceFontScale*100).toInt()} %"),
                row(14,"Thème de l’interface",if(darkTheme) "Sombre" else "Clair"),row(11,"Police du diaporama",fontName(slideshowFont)),row(12,"Modèle de présentation",presetName(layoutPreset))) +
                elementNames.indices.map { row(it,elementNames[it],"Police, taille et position · Modifier",true) }
            3 -> transitions.indices.map { row(it,transitions[it],if(transitionIndex == it) "Sélectionnée" else "Choisir",true) }
            4 -> listOf(row(0,"Afficher la date",yes(showDate)),row(1,"Format de date",dateFormatLabel()),row(2,"Afficher l’heure",yes(showTime)),
                row(3,"Format de l’heure",if(time24h) "24 h" else "12 h"),row(4,"Afficher les secondes",yes(showSeconds)),row(5,"Mode nuit automatique",yes(nightModeEnabled)),
                row(6,"Début du mode nuit","%02d:00".format(nightStartHour)),row(7,"Fin du mode nuit","%02d:00".format(nightEndHour)),row(8,"Assombrissement","$nightDimPercent %"),row(9,"Masquer les informations la nuit",yes(nightHideOverlays)))
            5 -> listOf(row(0,"Afficher la température",yes(showTemp)),row(1,"Unité",if(tempCelsius) "°C" else "°F"),row(2,"Ville météo",weatherLocation.ifBlank { "Automatique" },true),
                row(3,"Météo actuelle","${tempText()} · $weatherSummary",true),row(4,"Températures du jour","${temperatureLabel(forecastMinC,true)} / ${temperatureLabel(forecastMaxC,true)}",true),
                row(5,"Température ressentie",temperatureLabel(feelsLikeC,true),true)) + forecastLines.take(3).mapIndexed { i,line->row(6+i,"Prévision ${i+1}",line,true) }
            6 -> listOf(row(0,"Google Photos","Connexion Drive",true),row(1,"Dossier local","Parcourir",true),row(2,"Choisir des photos","Fichiers",true),
                row(3,"Tri des albums",if(albumSort == 0) "Nom" else "Nombre de photos"),row(4,"Rechercher un album",albumSearch.ifBlank { "Tous les albums" },true),
                row(5,"Sélection des albums","Tout sélectionner / masquer",true),row(6,"Albums masqués","Réafficher tous les albums",true),row(7,"Source réseau","SMB / WebDAV",true),row(8,"Analyse en arrière-plan",yes(backgroundAnalysis)))
            7 -> listOf(row(0,"Diaporama intelligent autonome",yes(autonomousSlideshow)),row(1,"Sélection intelligente",SmartSelectionPolicy.modeLabel(smartSelectionMode)),row(2,"Pré-analyse locale",yes(backgroundAnalysis)),
                row(3,"Reconnaissance des scènes",currentScene(currentItem()) ?: "En attente",true),row(4,"Recadrage intelligent","Automatique en mode Remplir",true),row(5,"Anti-doublons et diversité","Automatique",true),row(6,"Préchargement adaptatif","Automatique",true))
            else -> if (advancedRulesOpen) ruleRows() else listOf(
                row(0,"Affichage de l’image",imageModeLabel()),row(1,"Grille et magnétisme de l’éditeur",yes(gridSnap)),row(2,"Protection OLED",yes(oledProtection)),row(3,"Masquer les informations après 10 s",yes(overlaysAutoHide)),
                row(4,"Démarrage après inactivité",if(autoStartMinutes == 0) "Désactivé" else "$autoStartMinutes min"),row(5,"Démarrer directement le diaporama",yes(startDirectly)),row(6,"Afficher uniquement les favoris",yes(favoritesOnly)),
                row(7,"Disposition des informations",presetName(layoutPreset)),row(10,"Son des vidéos",yes(videoSound)),row(15,"Sélection intelligente",SmartSelectionPolicy.modeLabel(smartSelectionMode)),
                row(14,"Code PIN des réglages","Configurer",true),row(13,"Télécommande depuis le navigateur",yes(remoteEnabled),true),row(12,"Afficher les zones de diagnostic",yes(interactionDiagnostics),true),
                row(11,"Règles par album ou source","Configurer",true),row(16,"Économiseur d’écran de la télé","Choisir Photos TV ou celui de la télé",true),row(8,"Exporter les réglages","Sauvegarder",true),row(9,"Importer les réglages","Restaurer",true))
        }
    }

    private fun ruleRows(): List<SettingRow> {
        val name = if (rulesBySource) sourceLabels().getOrNull(ruleSourceIndex) else albumPairs().getOrNull(ruleAlbumIndex)?.first
        val source = sourceRules.getOrPut(name.orEmpty()) { SourceRule() }
        val album = albumRules.getOrPut(name.orEmpty()) { AlbumRule() }
        val enabled = if(rulesBySource) source.enabled else album.enabled
        val start = if(rulesBySource) source.startHour else album.startHour
        val end = if(rulesBySource) source.endHour else album.endHour
        val duration = if(rulesBySource) source.durationSeconds else album.durationSeconds
        val transition = if(rulesBySource) source.transitionIndex else album.transitionIndex
        val metadata = if(rulesBySource) source.showMetadata else album.showMetadata
        return listOf(SettingRow(0,if(rulesBySource) "Source" else "Album",name ?: "Aucune photothèque"),SettingRow(1,"Activer la règle",if(enabled) "Activée" else "Désactivée"),
            SettingRow(2,if(rulesBySource) "Saison" else "Jours",if(rulesBySource) listOf("Toute l’année","Hiver","Printemps","Été","Automne")[source.seasonMode.coerceIn(0,4)] else listOf("Tous","Semaine","Week-end")[album.daysMode.coerceIn(0,2)]),
            SettingRow(3,"À partir de","%02d:00".format(start)),SettingRow(4,"Jusqu’à","%02d:00".format(end)),SettingRow(5,"Durée par photo",if(duration<=0) "Réglage global" else "$duration s"),
            SettingRow(6,"Transition",if(transition<0) "Réglage global" else transitions[transition.coerceIn(0,transitions.lastIndex)]),SettingRow(7,"Afficher les métadonnées",if(metadata) "Oui" else "Non"),
            SettingRow(9,if(rulesBySource) "Voir les règles par album" else "Voir les règles par source","Ouvrir",true),SettingRow(8,"Retour aux réglages avancés","Retour",true))
    }

    private fun drawSettings(c: Canvas) {
        drawAppBackground(c)
        drawBrand(c, 50f, 20f, "RÉGLAGES")
        val cats = listOf("Diaporama","Éléments affichés","Style et position","Transitions","Heure et date","Température","Source des photos","Intelligence","Avancés")

        val sideH = 50f * interfaceFontScale.coerceAtLeast(1f)
        val visible = (510f / sideH).toInt().coerceAtLeast(1)
        val first = (settingsCategory - visible / 2).coerceIn(0,max(0,cats.size-visible))
        val sideCount = min(visible, cats.size)
        val sideTop = 354.5f - (sideCount * sideH + 16f) / 2f
        round(c,32f,sideTop,352f,sideTop+sideCount*sideH+16f,16f,Color.rgb(6,18,29))
        strokeRound(c,32f,sideTop,352f,sideTop+sideCount*sideH+16f,16f,Color.rgb(87,124,165),2f)
        cats.drop(first).take(visible).forEachIndexed { slot,name ->
            val index = first + slot; val y = sideTop + 8f + slot * sideH
            val active = settingsCategory == index
            if(active) { val light=lightInterface; lightInterface=false; round(c,40f,y,344f,y+sideH-6f,11f,Color.rgb(14,102,219)); lightInterface=light }
            if(active && settingsColumn == 0 && !navFocus) strokeRound(c,40f,y,344f,y+sideH-6f,11f,Color.rgb(146,209,255),3f)
            val wasLight=lightInterface
            if(active) lightInterface=false
            p.shader=null; p.style=Paint.Style.FILL
            drawSideIcon(c,64f,y+sideH/2f,index)
            ellipsizedText(c,name,201f,y+sideH/2f+5f*interfaceFontScale,260f,14f,Color.WHITE,if(active) 1 else 0,1)
            lightInterface=wasLight
        }
        round(c,380f,77f,1248f,632f,16f,Color.rgb(17,30,44))
        strokeRound(c,380f,77f,1248f,632f,16f,Color.rgb(87,124,165),2f)
        drawScrollIndicator(c,346f,sideTop+8f,sideTop+8f+sideCount*sideH,cats.size.toFloat(),sideCount.toFloat(),first.toFloat())
        ellipsizedText(c,if(advancedRulesOpen && settingsCategory == 8) "Règles par album et source" else cats[settingsCategory],814f,135f,820f,25f,Color.WHITE,1,1)

        settingHitBoxes.clear()
        val rows=settingRows()
        val rowH=78f * interfaceFontScale.coerceAtLeast(1f)
        val grouped = settingsCategory == 6
        val groups = if(grouped) listOf(rows.take(3)) + rows.drop(3).map { listOf(it) } else rows.map { listOf(it) }
        val index=groups.indexOfFirst { group->group.any { it.id == settingsControl } }.coerceAtLeast(0)
        val viewport=448f
        val focusTop=index*rowH
        if(focusTop<settingsScroll) settingsScroll=focusTop
        if(focusTop+rowH>settingsScroll+viewport) settingsScroll=focusTop+rowH-viewport
        settingsScroll=settingsScroll.coerceIn(0f,max(0f,groups.size*rowH-viewport))
        c.save(); c.clipRect(395f,160f,1233f,610f)
        groups.forEachIndexed { groupIndex,group ->
            val y=162f+groupIndex*rowH-settingsScroll
            if(y+rowH>=160f && y<=610f) group.forEachIndexed { slot,row ->
                val w=820f/group.size; val x=404f+slot*w
                val focused=settingsColumn==1 && settingsControl==row.id && !navFocus
                round(c,x,y,x+w-10f,y+rowH-10f,11f,if(focused) Color.rgb(17,61,108) else Color.rgb(9,23,37))
                if(focused) strokeRound(c,x,y,x+w-10f,y+rowH-10f,11f,if(settingsEditing) Color.rgb(255,200,95) else Color.rgb(105,196,255),3f)
                ellipsizedText(c,row.label,x+16f,y+28f*interfaceFontScale,w-42f,15f,Color.WHITE,1)
                ellipsizedText(c,(if(focused && settingsEditing) "‹  " else "")+row.value+(if(focused && settingsEditing) "  ›" else ""),x+16f,y+53f*interfaceFontScale,w-42f,13f,Color.rgb(157,205,255))
                val rect=android.graphics.RectF(x,max(160f,y),x+w-10f,min(610f,y+rowH-10f))
                if(rect.height()>0) settingHitBoxes.add(row.id to rect)
            }
        }
        c.restore()
        if(groups.size*rowH>viewport) {
            val thumb=viewport*viewport/(groups.size*rowH)
            val top=162f+(viewport-thumb)*settingsScroll/(groups.size*rowH-viewport)
            round(c,1235f,162f,1240f,610f,3f,Color.rgb(35,57,81))
            round(c,1235f,top,1240f,top+thumb,3f,Color.rgb(88,167,243))
        }
    }

    private fun drawSettingsSlideshow(c: Canvas, x: Float, y: Float) {
        text(c, "Diaporama", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)
        settingsSegment(c, "Cadrage des photos", listOf("Photo entière", "Remplir", "Fond flouté"), when (imageMode) { 0 -> 1; 3 -> 2; else -> 0 }, x, y + 42f, 10)
        settingsSlider(c, "Durée d'affichage par photo", durationSeconds.toFloat(), 2f, 120f, "$durationSeconds secondes", x, y + 84f, 0)
        settingsSegment(c, "Mode d'affichage", listOf("Automatique", "Image fixe"), if (fixedImage) 1 else 0, x, y + 126f, 1)
        settingsToggle(c, "Lecture en boucle", loop, x, y + 168f, 2)
        settingsChoice(c, "Ordre des photos", if (randomOrder) "Aléatoire" else "Dans l'ordre de l'album", x, y + 210f, 3)

        text(c, "Transition entre les photos", x + 22f, y + 282f, 13f, Color.WHITE)
        drawTransitionCards(c, x + 22f, y + 291f, settingsControl == 4 && settingsColumn == 1 && !navFocus)

        settingsSlider(c, "Durée de la transition", transitionSeconds, .2f, 4f, "${format1(transitionSeconds)} secondes", x, y + 390f, 5)
        settingsToggle(c, "Effet panoramique (Ken Burns)", kenBurns, x, y + 430f, 6)
        settingsSegment(c, "Style d'agrandissement", listOf("Aucun", "Léger", "Moyen", "Fort"), zoomLevel + 1, x, y + 471f, 7)
        settingsToggle(c, "Diaporama intelligent autonome", autonomousSlideshow, x, y + 510f, 8)
    }

    private fun drawSettingsElements(c: Canvas, x: Float, y: Float, w: Float) {
        text(c, "Éléments affichés", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)
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
        settingsToggle(c, "Masquer les extensions des titres", hideTitleExtensions, x, y + 476f, elementNames.size)
        text(c, "${first + 1}–${min(first + visibleCount, elementNames.size)} / ${elementNames.size}", x + w - 24f, y + 545f, 11f, Color.rgb(128, 149, 173), 0, 2)
    }

    private fun elementVisible(i: Int): Boolean = when (i) {
        2 -> showDate
        3 -> showTime
        4 -> showTemp
        else -> styles.getOrNull(i)?.visible ?: false
    }

    private fun drawSettingsStyle(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        text(c, "Style et position", x + w / 2f, y + 34f, 18f, Color.WHITE, 1, 1)
        text(c, "Police de l’interface", x + 24f, y + 84f, 14f, Color.WHITE)
        controlBox(c, x + 440f, y + 59f, 400f, 42f, fontName(interfaceFont), settingsControl == 10 && settingsColumn == 1 && !navFocus)
        text(c, "Police du diaporama", x + 24f, y + 139f, 14f, Color.WHITE)
        controlBox(c, x + 440f, y + 114f, 400f, 42f, fontName(slideshowFont), settingsControl == 11 && settingsColumn == 1 && !navFocus)
        text(c, "Éléments du diaporama · réglages individuels dans l’éditeur", x + 24f, y + 192f, 12f, Color.rgb(184, 196, 212))
        val visibleCount = 4
        val first = (editorElement - 2).coerceIn(0, max(0, elementNames.size - visibleCount))
        for (slot in 0 until visibleCount) {
            val i = first + slot
            if (i !in elementNames.indices) break
            val yy = y + 212f + slot * 64f
            round(c, x + 22f, yy, x + 840f, yy + 50f, 10f, if (i == editorElement) Color.rgb(10, 101, 214) else Color.rgb(11, 25, 38))
            if (settingsControl == i && settingsColumn == 1 && !navFocus) strokeRound(c, x + 22f, yy, x + 840f, yy + 50f, 10f, Color.rgb(32, 131, 244), 2f)
            text(c, elementNames[i], x + 40f, yy + 22f, 14f, Color.WHITE)
            text(c, "Police : ${fontName(styles[i].font)}", x + 40f, yy + 42f, 10f, Color.rgb(177, 191, 209))
            text(c, "${styles[i].size.toInt()} px  ·  X ${styles[i].x.toInt()}  ·  Y ${styles[i].y.toInt()}", x + 445f, yy + 31f, 13f, Color.rgb(177, 191, 209))
        }
        settingsChoice(c, "Modèle de départ", presetName(layoutPreset), x, y + 476f, 12)
        text(c, "OK ouvre l’éditeur · Police et taille pour chaque élément", x + w / 2f, y + h - 12f, 12f, Color.rgb(126, 151, 181), 0, 1)
    }

    private fun drawSettingsTransitions(c: Canvas, x: Float, y: Float) {
        text(c, "Transitions", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)
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
        text(c, "Heure et date", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)
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

    private fun isNightModeActive(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean =
        NightSchedule.isActive(
            enabled = nightModeEnabled,
            startHour = nightStartHour,
            endHour = nightEndHour,
            currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
        )

    private fun drawSettingsTemp(c: Canvas, x: Float, y: Float) {
        text(c, "Température", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)
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
        text(c, "Source des photos", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)

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
            "OK sur un album : masquer / réafficher durablement.",
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
        settingsToggle(c, "Pré-analyse locale en arrière-plan", backgroundAnalysis, x, y + 510f, 8)
    }

    private fun drawSettingsIntelligence(c: Canvas, x: Float, y: Float, w: Float) {
        text(c, "Intelligence et performances", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)
        text(c, "Les fonctions automatiques restent visibles ici même lorsqu'elles ne demandent aucun réglage.", x + 22f, y + 58f, 11f, Color.rgb(150, 171, 194))

        fun row(
            index: Int,
            top: Float,
            title: String,
            explanation: String,
            value: String,
            toggle: Boolean? = null
        ) {
            val focused = settingsColumn == 1 && settingsControl == index && !navFocus
            round(c, x + 22f, top, x + w - 22f, top + 58f, 10f, Color.rgb(10, 24, 38))
            if (focused) strokeRound(c, x + 19f, top - 3f, x + w - 19f, top + 61f, 11f, Color.rgb(49, 151, 255), 2f)
            text(c, title, x + 38f, top + 23f, 13f, Color.WHITE, 1)
            ellipsizedText(c, explanation, x + 38f, top + 44f, 590f, 10f, Color.rgb(142, 163, 187))
            if (toggle != null) {
                drawToggle(c, x + w - 94f, top + 15f, toggle, false)
            } else {
                text(c, value, x + w - 38f, top + 31f, 11f, Color.rgb(109, 190, 255), 1, 2)
            }
        }

        row(
            0, y + 74f,
            "Diaporama intelligent autonome",
            "Adapte sélection, durée, souvenirs, favoris et mosaïques au contenu et au moment de la journée.",
            if (autonomousSlideshow) "Activé" else "Désactivé",
            autonomousSlideshow
        )
        row(
            1, y + 139f,
            "Sélection intelligente",
            "Utilise le score qualité local et limite les répétitions selon le mode choisi.",
            SmartSelectionPolicy.modeLabel(smartSelectionMode)
        )
        row(
            2, y + 204f,
            "Pré-analyse locale",
            "Analyse progressivement scènes, qualité et recadrages pour accélérer les affichages suivants.",
            "$backgroundAnalysisDone / $backgroundAnalysisTotal",
            backgroundAnalysis
        )
        row(
            3, y + 269f,
            "Reconnaissance locale des scènes",
            "Classe sur la TV personnes, animaux, nourriture, paysages, ville, nuit et autres scènes.",
            currentScene(currentItem()) ?: "En attente"
        )
        row(
            4, y + 334f,
            "Recadrage intelligent",
            "Détecte visage ou sujet principal pour éviter de couper l'élément important en mode Remplir.",
            "Automatique"
        )
        row(
            5, y + 399f,
            "Anti-doublons et diversité",
            "Écarte les photos identiques ou très proches et espace les contenus visuellement similaires.",
            "Automatique"
        )
        row(
            6, y + 464f,
            "Transitions et préchargement adaptatifs",
            "Allège les effets si la TV manque de ressources et prépare les médias suivants selon ses performances.",
            "Automatique"
        )
    }

    private fun drawSettingsRules(c: Canvas, x: Float, y: Float, w: Float) {
        text(c, if (rulesBySource) "Règles par source" else "Règles par album", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)
        controlBox(c, x + w - 385f, y + 10f, 175f, 38f, if (rulesBySource) "Voir albums" else "Voir sources", settingsColumn == 1 && settingsControl == 9 && !navFocus)
        controlBox(c, x + w - 195f, y + 10f, 170f, 38f, "Retour avancés", settingsColumn == 1 && settingsControl == 8 && !navFocus)

        if (rulesBySource) {
            val sources = sourceLabels()
            if (sources.isEmpty()) {
                text(c, "Chargez d’abord une photothèque.", x + 22f, y + 85f, 14f, Color.rgb(174, 188, 205))
                return
            }
            ruleSourceIndex = ruleSourceIndex.coerceIn(0, sources.lastIndex)
            val source = sources[ruleSourceIndex]
            val rule = sourceRules.getOrPut(source) { SourceRule() }
            val seasons = listOf("Toute l’année", "Hiver", "Printemps", "Été", "Automne")
            settingsChoice(c, "Source", source, x, y + 55f, 0)
            settingsToggle(c, "Activer une règle pour cette source", rule.enabled, x, y + 113f, 1)
            settingsChoice(c, "Saison", seasons[rule.seasonMode.coerceIn(0, 4)], x, y + 171f, 2)
            settingsChoice(c, "À partir de", "%02d:00".format(rule.startHour), x, y + 229f, 3)
            settingsChoice(c, "Jusqu’à", if (rule.endHour == 24) "24:00" else "%02d:00".format(rule.endHour), x, y + 287f, 4)
            settingsChoice(c, "Durée par photo", if (rule.durationSeconds <= 0) "Réglage global" else rule.durationSeconds.toString() + " s", x, y + 345f, 5)
            settingsChoice(c, "Transition", if (rule.transitionIndex < 0) "Réglage global" else transitions[rule.transitionIndex], x, y + 403f, 6)
            settingsToggle(c, "Afficher les métadonnées", rule.showMetadata, x, y + 461f, 7)
            text(c, "La saison et la plage horaire peuvent limiter automatiquement cette source.", x + 22f, y + 528f, 10.5f, Color.rgb(128, 151, 178))
            return
        }

        val albums = albumPairs()
        if (albums.isEmpty()) {
            text(c, "Chargez d’abord une photothèque.", x + 22f, y + 85f, 14f, Color.rgb(174, 188, 205))
            return
        }
        ruleAlbumIndex = ruleAlbumIndex.coerceIn(0, albums.lastIndex)
        val albumName = albums[ruleAlbumIndex].first
        val rule = albumRules.getOrPut(albumName) { AlbumRule() }
        settingsChoice(c, "Album", albumName, x, y + 55f, 0)
        settingsToggle(c, "Activer une règle pour cet album", rule.enabled, x, y + 113f, 1)
        settingsSegment(c, "Jours", listOf("Tous", "Semaine", "Week-end"), rule.daysMode, x, y + 171f, 2)
        settingsChoice(c, "À partir de", "%02d:00".format(rule.startHour), x, y + 229f, 3)
        settingsChoice(c, "Jusqu’à", if (rule.endHour == 24) "24:00" else "%02d:00".format(rule.endHour), x, y + 287f, 4)
        settingsChoice(c, "Durée par photo", if (rule.durationSeconds <= 0) "Réglage global" else rule.durationSeconds.toString() + " s", x, y + 345f, 5)
        settingsChoice(c, "Transition", if (rule.transitionIndex < 0) "Réglage global" else transitions[rule.transitionIndex], x, y + 403f, 6)
        settingsToggle(c, "Afficher les métadonnées", rule.showMetadata, x, y + 461f, 7)
        text(c, "Une photo multi-albums reste visible si au moins une règle autorise son affichage.", x + 22f, y + 528f, 10.5f, Color.rgb(128, 151, 178))
    }
    private fun drawSettingsAdvanced(c: Canvas, x: Float, y: Float) {
        text(c, "Avancés", x + 434f, y + 34f, 18f, Color.WHITE, 1, 1)
        controlBox(c, x + 22f, y + 8f, 82f, 38f, "Sélect.", settingsColumn == 1 && settingsControl == 15 && !navFocus)
        controlBox(c, x + 118f, y + 8f, 92f, 38f, "PIN", settingsColumn == 1 && settingsControl == 14 && !navFocus)
        controlBox(c, x + 225f, y + 8f, 205f, 38f, if (remoteEnabled) "Télécommande : ON" else "Télécommande : OFF", settingsColumn == 1 && settingsControl == 13 && !navFocus)
        controlBox(c, x + 445f, y + 8f, 205f, 38f, if (interactionDiagnostics) "Zones : ON" else "Zones : OFF", settingsColumn == 1 && settingsControl == 12 && !navFocus)
        controlBox(c, x + 665f, y + 8f, 190f, 38f, "Règles albums/sources", settingsColumn == 1 && settingsControl == 11 && !navFocus)
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

        controlBox(c, x + 22f, y + 468f, 300f, 38f, "Économiseur de la télé", settingsColumn == 1 && settingsControl == 16 && !navFocus)
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
            val remoteHint = (remoteServer?.url() ?: "Télécommande : connexion réseau en attente") +
                " • maintien OK sur Télécommande = nouveau lien"
            ellipsizedText(c, remoteHint, x + 22f, y + 575f, 820f, 9f, Color.rgb(137, 200, 173))
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
            appendLine("Sélection intelligente: " + SmartSelectionPolicy.modeLabel(smartSelectionMode))
            appendLine("Diaporama autonome: " + autonomousSlideshow)
            appendLine("Scène courante: " + (currentScene(currentItem()) ?: "non analysée"))
            appendLine("Pré-analyse: " + backgroundAnalysisDone + "/" + backgroundAnalysisTotal)
            appendLine("Santé réseau: " + NetworkLibrary.healthSummary())
            appendLine("Transition demandée: " + transitions[transitionIndex.coerceIn(0, transitions.lastIndex)])
            appendLine("Transition effective: " + transitions[effectiveTransition(currentItem()).coerceIn(0, transitions.lastIndex)])
            appendLine("Mode image: " + imageModeLabel())
            appendLine("Fichiers illisibles: " + decodeFailureCount)
            appendLine("Dernière erreur: " + lastDecodeFailure)
            appendLine("Historique: " + recentUris.size)
            appendLine("Mémoire: " + memoryDiagnostics())
            appendLine("Cache réseau: " + cache.first + " fichiers • " + (cache.second / (1024L * 1024L)) + " Mo")
            appendLine("Performance réseau: " + NetworkLibrary.runtimeStats())
            appendLine("Index persistant: " + MediaMetadataStore.stats())
            val thumbStats = ThumbnailDiskCache.stats(context)
            appendLine("Miniatures télécommande: " + thumbStats.first + " fichiers • " + (thumbStats.second / (1024L * 1024L)) + " Mo")
            appendLine("Scan incrémental: " + FolderScanIndex.stats())
            appendLine("Réseau temporairement hors ligne: " + NetworkLibrary.isTemporarilyOffline())
            appendLine("Télécommande: " + remoteEnabled)
            appendLine(
                "Adresses télécommande: " +
                    remoteServer?.urls().orEmpty().map { it.substringBefore("#") }.joinToString()
            )
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

    private fun displayImageMode(): Int = if (imageMode in 1..3) imageMode else autonomousImageModeOverride ?: imageMode

    private fun currentScene(item: PhotoItem?): String? {
        if (item == null || item.mediaType != "image") return null
        val key = item.uri.toString()
        sceneCache[key]?.let { return it }
        val mime = runCatching { context.contentResolver.getType(item.uri) }.getOrNull().orEmpty()
        val signature = MediaMetadataStore.signature(context, item.uri, mime)
        val cached = MediaMetadataStore.readScene(context, item.uri, signature)?.label
        if (!cached.isNullOrBlank()) sceneCache[key] = cached
        return cached
    }

    private fun effectiveSmartSelectionMode(): Int =
        if (autonomousSlideshow) AutonomousSlideshowPolicy.current(
            slideNumber = autonomousSlideNumber,
            scene = currentScene(currentItem())
        ).smartSelectionMode else smartSelectionMode

    private fun updateAutonomousPresentation() {
        if (!autonomousSlideshow) {
            autonomousImageModeOverride = null
            return
        }
        val item = currentItem()
        val presentation = AutonomousSlideshowPolicy.current(
            slideNumber = autonomousSlideNumber,
            scene = currentScene(item)
        )
        autonomousImageModeOverride = if (item?.mediaType == "image") presentation.imageModeOverride else null
    }

    private val imageModes: List<String>
        get() = listOf("Remplir l’écran (recadrage)", "Photo entière", "Original (sans agrandir)", "Photo entière · fond flouté", "Mosaïque 2", "Mosaïque 3", "Mosaïque 4")

    private fun imageModeLabel(): String =
        imageModes[imageMode.coerceIn(0, imageModes.lastIndex)]

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
        val previousItem = items.getOrNull(previousPhoto)
        val previousBitmap = previousItem?.let {
            val key = it.uri.toString()
            synchronized(highResCache) { highResCache[key] } ?: synchronized(bitmapCache) { bitmapCache[key] }
        }
        val previous = previousBitmap ?: current

        fun drawTransitionLayer(canvas: Canvas, bitmap: Bitmap?, x: Float, y: Float, w: Float, h: Float) {
            val alpha = imagePaint.alpha
            val photo = if (previousBitmap != null && bitmap === previous) previousItem else item
            imagePaint.alpha = 255
            val saved = canvas.saveLayerAlpha(x, y, x + w, y + h, alpha)
            drawBackgroundPhoto(canvas, x, y, w, h, bitmap, photo)
            canvas.restoreToCount(saved)
            imagePaint.alpha = alpha
        }
        val localTransition = effectiveTransition(item)
        val selectedTransition = transitions[localTransition.coerceIn(0, transitions.lastIndex)]
        val name = if ((zoomLevel < 0 || displayImageMode() in 1..3) && selectedTransition in listOf("Zoom", "Zoom arrière", "Ken Burns")) "Fondu" else selectedTransition
        if (displayImageMode() >= 4) {
            drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, current, item)
        } else if (item?.mediaType == "video" && supportsVideoPlayback && slideshow) {
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
                drawTransitionLayer(c, previous, -dir * transitionProgress * 1280f, 0f, 1280f, 720f)
                drawTransitionLayer(c, current, dir * (1f - transitionProgress) * 1280f, 0f, 1280f, 720f)
            }

            name == "Glissement haut" || name == "Glissement bas" -> {
                val dir = if (name == "Glissement haut") 1f else -1f
                drawTransitionLayer(c, previous, 0f, -dir * transitionProgress * 720f, 1280f, 720f)
                drawTransitionLayer(c, current, 0f, dir * (1f - transitionProgress) * 720f, 1280f, 720f)
            }

            name == "Zoom" || name == "Zoom arrière" -> {
                drawTransitionLayer(c, previous, 0f, 0f, 1280f, 720f)
                val t = transitionProgress
                val scale = if (name == "Zoom") .82f + .18f * t else 1.18f - .18f * t
                c.save()
                c.translate(640f, 360f)
                c.scale(scale, scale)
                c.translate(-640f, -360f)
                imagePaint.alpha = (255 * t).toInt().coerceIn(0, 255)
                drawTransitionLayer(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
                c.restore()
            }

            name == "Rotation douce" -> {
                drawTransitionLayer(c, previous, 0f, 0f, 1280f, 720f)
                c.save()
                c.rotate((1f - transitionProgress) * 4f, 640f, 360f)
                imagePaint.alpha = (255 * transitionProgress).toInt().coerceIn(0, 255)
                drawTransitionLayer(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
                c.restore()
            }

            name == "Balayage" -> {
                drawTransitionLayer(c, previous, 0f, 0f, 1280f, 720f)
                c.save()
                c.clipRect(0f, 0f, 1280f * transitionProgress, 720f)
                drawTransitionLayer(c, current, 0f, 0f, 1280f, 720f)
                c.restore()
            }

            name == "Cube 3D" -> {
                drawTransitionLayer(c, previous, 0f, 0f, 1280f, 720f)
                c.save()
                val scaleX = (.12f + .88f * transitionProgress).coerceIn(.12f, 1f)
                c.translate(640f, 0f)
                c.scale(scaleX, 1f)
                c.translate(-640f, 0f)
                imagePaint.alpha = (255 * transitionProgress).toInt().coerceIn(0, 255)
                drawTransitionLayer(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
                c.restore()
            }

            name == "Fondu au noir" || name == "Fondu au blanc" -> {
                val bg = if (name == "Fondu au blanc") Color.WHITE else Color.BLACK
                if (transitionProgress < .5f) {
                    drawTransitionLayer(c, previous, 0f, 0f, 1280f, 720f)
                    fill(c, 0f, 0f, 1280f, 720f, Color.argb((transitionProgress * 2f * 255f).toInt(), Color.red(bg), Color.green(bg), Color.blue(bg)))
                } else {
                    drawTransitionLayer(c, current, 0f, 0f, 1280f, 720f)
                    fill(c, 0f, 0f, 1280f, 720f, Color.argb(((1f - transitionProgress) * 2f * 255f).toInt(), Color.red(bg), Color.green(bg), Color.blue(bg)))
                }
            }

            name == "Dissolution" -> {
                drawTransitionLayer(c, previous, 0f, 0f, 1280f, 720f)
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
                drawTransitionLayer(c, current, 0f, 0f, 1280f, 720f)
                c.restore()
            }

            name == "Flou progressif" -> {
                drawTransitionLayer(c, previous, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = (220f * transitionProgress).toInt().coerceIn(0, 220)
                drawSoftBackground(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = (255f * transitionProgress * transitionProgress).toInt().coerceIn(0, 255)
                drawTransitionLayer(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
            }

            name == "Aucune" -> drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, current)

            else -> {
                val fadeProgress = FadeBlendPolicy.progress(transitionProgress, fadeIntensityPercent)
                imagePaint.alpha = ((1f - fadeProgress) * 255).toInt().coerceIn(0, 255)
                drawTransitionLayer(c, previous, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = (fadeProgress * 255).toInt().coerceIn(0, 255)
                drawTransitionLayer(c, current, 0f, 0f, 1280f, 720f)
                imagePaint.alpha = 255
            }
        }

        drawBottomGradient(c, 0f, 0f, 1280f, 720f)
        val vals = metadataValues(item)
        val ruleAllowsMetadata = effectiveShowMetadata(item)
        val nightActive = isNightModeActive()
        val hideOverlays = !ruleAllowsMetadata || (nightActive && nightHideOverlays) || (slideshow && overlaysAutoHide && System.currentTimeMillis() - slideStartedAt > 10_000L)
        val shift = if (slideshow) oledShift() else 0f to 0f
        if (!hideOverlays) {
            styles.forEachIndexed { i, st ->
                val global = when (i) { 2 -> showDate; 3 -> showTime; 4 -> showTemp; else -> true }
                if (!st.visible || !global || vals.getOrElse(i) { "—" } == "—") return@forEachIndexed
                applyStylePaint(st)
                val x = 1280f * st.x / 100f + shift.first
                val y = 720f * st.y / 100f + shift.second
                p.textAlign = when (st.align) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
                if(i==0) drawFullTitle(c,vals[i],x,y)
                else c.drawText(vals[i],x,if(i==1) albumBaseline(vals[0],y) else y,p)
            }
        }

        p.clearShadowLayer()
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
        if (bmp == null || bmp.isRecycled || bmp === emptySlideshowBackground) { drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, bmp); return }
        if (zoomLevel < 0 || displayImageMode() in 1..3) { drawBackgroundPhoto(c, 0f, 0f, 1280f, 720f, bmp); return }
        val localDuration = effectiveDuration(item)
        val elapsed = if (slideshow) (System.currentTimeMillis() - slideStartedAt).coerceAtLeast(0L) else 0L
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
            if (paused) "Reprendre" else "Pause",
            "Historique visuel",
            "Diagnostics techniques",
            "Souvenirs"
        )
        val x = 855f
        val y = 185f
        val w = 370f
        val h = 449f
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
            "Titre" to displayedTitle(item?.title ?: "—"),
            "Album(s)" to (item?.albums?.joinToString(" • ") ?: "—"),
            "Date" to itemDate(item),
            "Lieu" to (item?.location?.ifBlank { "—" } ?: "—"),
            "Appareil" to (item?.camera?.ifBlank { "—" } ?: "—"),
            "Dimensions" to (item?.dimensionsLabel?.ifBlank { "—" } ?: "—"),
            "Orientation" to (item?.orientationLabel?.ifBlank { "—" } ?: "—"),
            "Type" to (item?.mediaType ?: "—")
        )
        p.typeface=interfaceTypeface(0);p.textSize=12f*interfaceFontScale
        val wrapped=rows.map { wrappedText(it.second,w-177f) }
        val heights=wrapped.map { max(33f*interfaceFontScale,(it.size*18f+12f)*interfaceFontScale) }
        val total=heights.sum();val viewport=h-100f
        infoPanelScroll=infoPanelScroll.coerceIn(0f,max(0f,total-viewport))
        var offset=0f
        c.save();c.clipRect(x+16f,y+55f,x+w-16f,y+h-45f)
        rows.forEachIndexed { i,row ->
            val yy=y+72f+offset-infoPanelScroll
            text(c,row.first,x+22f,yy,11f,Color.rgb(148,167,190))
            wrapped[i].forEachIndexed { line,value -> text(c,value,x+145f,yy+line*18f*interfaceFontScale,12f,Color.WHITE) }
            offset+=heights[i]
        }
        c.restore()
        drawScrollIndicator(c,x+w-8f,y+55f,y+h-45f,total,viewport,infoPanelScroll)
        text(c,"↑ ↓ Défiler  ·  OK / Retour : fermer",x+w/2f,y+h-18f,10f,Color.rgb(148,167,190),0,1)
    }

    private val navLabels = listOf("Aperçu", "Photos", "Éditeur", "Réglages")
    private fun navRects(): List<RectF> {
        p.typeface = interfaceTypeface(1); p.textSize = 17f * interfaceFontScale
        val widths = navLabels.map { p.measureText(it) + 72f }
        var x = (1280f - widths.sum()) / 2f
        return widths.map { w -> RectF(x,644f,x+w,708f).also { x+=w } }
    }
    private fun drawBottomNav(c: Canvas) {
        val wasLight = lightInterface; lightInterface = false
        val rects = navRects()
        rects.forEachIndexed { i, r ->
            val active = page == i
            round(c,r.left,r.top,r.right,r.bottom,14f,if(active) Color.rgb(17,80,156) else Color.rgb(7,20,36))
            if(active) strokeRound(c,r.left+2f,r.top+2f,r.right-2f,r.bottom-2f,12f,if(navFocus) Color.rgb(147,224,255) else Color.rgb(60,140,220),if(navFocus) 3f else 1f)
            drawBottomIcon(c,r.left+27f,676f,i)
            text(c,navLabels[i],r.left+52f,682f,17f,Color.WHITE,1)
        }
        lightInterface = wasLight
    }

    private fun drawBrand(c: Canvas, x: Float, y: Float, subtitle: String) {
        c.save()
        val iconBounds = RectF(x - 8f, y - 4f, x + 50f, y + 54f)
        val mask = Path().apply { addRoundRect(iconBounds, 13f, 13f, Path.Direction.CW) }
        c.clipPath(mask)
        c.drawBitmap(brandIcon, null, iconBounds, imagePaint)
        c.restore()
        text(c, "Photo TV", x + 66f, y + 24f, 25f, Color.WHITE, 1)
        text(c, subtitle, x + 66f, y + 48f, 10.5f, Color.rgb(178, 196, 219))
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
        val wasLight=lightInterface
        if(focused) lightInterface=false
        if (focused) gradientRound(c, x, y, x + w, y + h, 14f, Color.rgb(10, 137, 255), Color.rgb(7, 90, 235))
        else gradientRound(c, x, y, x + w, y + h, 14f, Color.rgb(18, 33, 49), Color.rgb(10, 23, 36))
        strokeRound(c, x, y, x + w, y + h, 14f, Color.rgb(39, 58, 79), 1f)
        if (focused) strokeRound(c, x - 3f, y - 3f, x + w + 3f, y + h + 3f, 16f, Color.rgb(183, 224, 255), 3f)
        drawSourceIcon(c, x + 36f, y + h / 2, icon)
        ellipsizedText(c, title, x + 70f, y + 42f, w - 86f, 16f, Color.WHITE, 1)
        ellipsizedText(c, sub, x + 70f, y + 42f + 25f * interfaceFontScale, w - 86f, 12f, Color.rgb(221, 231, 244))
        lightInterface=wasLight
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

    private fun drawAlbumCard(c: Canvas,x:Float,y:Float,w:Float,h:Float,name:String,count:Int,index:Int,focused:Boolean) {
        val selected = if(library.isEmpty()) index==0 else selectedAlbums.contains(name)
        val hidden = hiddenAlbums.contains(name)
        round(c,x,y,x+w,y+h,12f,Color.rgb(8,18,27))
        val imageW = (w*.38f).coerceIn(80f,144f)
        drawBitmapCenterCrop(c,albumBitmap(name,index),x+5f,y+5f,imageW,h-10f,9f)
        strokeRound(c,x,y,x+w,y+h,12f,if(selected) Color.rgb(49,177,255) else Color.rgb(75,99,128),if(selected) 2f else 1f)
        if(focused) strokeRound(c,x-3f,y-3f,x+w+3f,y+h+3f,14f,Color.rgb(32,135,242),3f)
        val textX=x+imageW+18f
        val textW=w-imageW-28f
        ellipsizedText(c,name,textX,y+34f*interfaceFontScale,textW,15f,Color.WHITE,1)
        ellipsizedText(c,"$count photos",textX,y+61f*interfaceFontScale,textW,12f,Color.rgb(130,153,180))
        ellipsizedText(c,if(hidden) "Masqué" else "Visible",textX,y+78f*interfaceFontScale,textW,11f,if(hidden) Color.rgb(214,117,42) else Color.rgb(130,153,180))
        if(selected && !hidden) {
            circle(c,x+imageW-8f,y+h-20f,11f,Color.rgb(15,124,255))
            val wasLight=lightInterface;lightInterface=false
            text(c,"✓",x+imageW-8f,y+h-16f,11f,Color.WHITE,1,1)
            lightInterface=wasLight
        }
    }

    private fun albumBitmap(name: String, index: Int): Bitmap? {
        if (library.isEmpty()) return mockAlbumBitmaps[index] ?: demoBitmap
        val items = albumIndex[name].orEmpty()
        if (items.isEmpty()) return if (automationMode) demoBitmap else null
        // Keep a real cover from this album even when its first Drive photo
        // is temporarily unavailable; do not retry the same blocked image forever.
        synchronized(bitmapCache) {
            items.firstNotNullOfOrNull { bitmapCache[it.uri.toString()]?.takeUnless { bmp -> bmp.isRecycled } }
        }?.let { return it }
        val now = android.os.SystemClock.elapsedRealtime()
        val item = items.firstOrNull {
            val key = it.uri.toString()
            !failedMediaUris.contains(key) && (driveRetryAfter[key] ?: 0L) <= now
        } ?: return if (automationMode) demoBitmap else null
        preload(listOf(item.uri), targetWidth = 600, targetHeight = 450)
        return if (automationMode) demoBitmap else null
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
        p.shader=null; p.style=Paint.Style.FILL; p.color = interfaceColor(Color.WHITE)
        when (type) {
            0 -> {
                val path = Path()
                path.moveTo(x - 7f, y - 9f); path.lineTo(x + 9f, y); path.lineTo(x - 7f, y + 9f); path.close()
                c.drawPath(path, p)
            }
            1 -> text(c, "T", x, y + 7f, 20f, Color.WHITE, 1, 1)
            2 -> { strokeRound(c, x - 10f, y - 8f, x + 10f, y + 8f, 2f, Color.WHITE, 2f); c.drawLine(x - 6f,y-2f,x+6f,y-2f,stroke) }
            3 -> drawBottomIcon(c, x, y, 1)
            4 -> { stroke.style=Paint.Style.STROKE;stroke.color=interfaceColor(Color.WHITE);stroke.strokeWidth=2f;c.drawCircle(x,y,10f,stroke);c.drawLine(x,y,x,y-6f,stroke);c.drawLine(x,y,x+5f,y+2f,stroke) }
            5 -> text(c, "♨", x, y + 7f, 18f, Color.WHITE, 0, 1)
            6 -> drawSourceIcon(c, x, y, 2)
            7 -> {
                stroke.style = Paint.Style.STROKE
                stroke.color = interfaceColor(Color.WHITE)
                stroke.strokeWidth = 1.8f
                c.drawCircle(x, y, 9f, stroke)
                c.drawLine(x - 5f, y, x + 5f, y, stroke)
                c.drawLine(x, y - 5f, x, y + 5f, stroke)
                circle(c, x, y, 2.5f, Color.WHITE)
            }
            else -> drawGear(c, x, y, 9f)
        }
    }

    private fun drawBottomIcon(c: Canvas, x: Float, y: Float, type: Int) {
        p.shader=null; p.clearShadowLayer(); p.style=Paint.Style.FILL
        val colors = intArrayOf(Color.rgb(38,177,240),Color.rgb(255,173,60),Color.rgb(194,112,250),Color.rgb(58,215,184),Color.rgb(73,224,139))
        circle(c,x,y,19f,Color.argb(55,Color.red(colors[type]),Color.green(colors[type]),Color.blue(colors[type])))
        stroke.style=Paint.Style.STROKE;stroke.strokeWidth=2.2f;stroke.color=colors[type]
        when(type) {
            0 -> { c.drawRoundRect(x-12,y-8,x+12,y+7,3f,3f,stroke); c.drawLine(x,y+7,x,y+12,stroke);c.drawLine(x-7,y+12,x+7,y+12,stroke) }
            1 -> {
                c.drawRoundRect(x-11,y-9,x+11,y+9,3f,3f,stroke)
                circle(c,x+5,y-4,3f,Color.rgb(255,214,80))
                p.color=Color.rgb(62,207,151)
                c.drawPath(Path().apply { moveTo(x-9,y+7);lineTo(x-3,y-1);lineTo(x+1,y+3);lineTo(x+5,y);lineTo(x+9,y+7);close() },p)
            }
            2 -> { c.drawCircle(x,y,11f,stroke); circle(c,x-4,y-4,3f,Color.rgb(255,133,139));circle(c,x+5,y-3,3f,Color.rgb(255,208,78));circle(c,x-2,y+5,3f,Color.rgb(76,217,215)) }
            3 -> {
                for(i in 0..2) { val yy=y-8+i*8; c.drawLine(x-11,yy,x+11,yy,stroke);circle(c,x+if(i==1) -4f else 4f,yy,3.5f,colors[type]) }
            }
            4 -> { p.color=colors[type];c.drawPath(Path().apply { moveTo(x-6,y-11);lineTo(x+11,y);lineTo(x-6,y+11);close() },p) }
        }
    }

    private fun drawGear(c: Canvas, x: Float, y: Float, r: Float) {
        p.color = interfaceColor(Color.WHITE)
        c.drawCircle(x, y, r, p)
        p.color = Color.rgb(9, 24, 38)
        c.drawCircle(x, y, r * .4f, p)
        stroke.style=Paint.Style.STROKE;stroke.color=interfaceColor(Color.WHITE);stroke.strokeWidth=3f
        for(i in 0 until 8){
            val a=Math.toRadians((i*45).toDouble())
            c.drawLine(x+(Math.cos(a)*r).toFloat(),y+(Math.sin(a)*r).toFloat(),x+(Math.cos(a)*(r+4)).toFloat(),y+(Math.sin(a)*(r+4)).toFloat(),stroke)
        }
    }

    private fun drawAlignIcon(c: Canvas, x: Float, y: Float, align: Int) {
        stroke.style=Paint.Style.STROKE;stroke.color=interfaceColor(Color.WHITE);stroke.strokeWidth=1.5f
        val widths = floatArrayOf(14f, 10f, 16f)
        for(i in 0..2){
            val yy=y-6f+i*6f
            val ww=widths[i]
            val start=when(align){1->x-ww/2;2->x+7f-ww;else->x-7f}
            c.drawLine(start,yy,start+ww,yy,stroke)
        }
    }

    private fun applyStylePaint(s: Style) {
        p.shader = null
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

    private fun fontName(index: Int) = listOf("Sans sérif", "Sans sérif légère", "Sérif", "Monospace")[index.coerceIn(0, 3)]
    private fun interfaceTypeface(weight: Int): Typeface {
        val font = if (renderingSlideshowText || slideshow) slideshowFont else interfaceFont
        val family = listOf("sans-serif", "sans-serif-light", "serif", "monospace")[font.coerceIn(0, 3)]
        return Typeface.create(family, if (weight == 1) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun format1(v: Float): String = String.format(Locale.FRANCE, "%.1f", v)

    private fun drawAppBackground(c: Canvas) {
        p.shader = LinearGradient(0f, 0f, 1280f, 720f, if (lightInterface) Color.rgb(245, 248, 252) else Color.rgb(4, 13, 21), if (lightInterface) Color.rgb(231, 239, 249) else Color.rgb(1, 7, 12), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1280f, 720f, p)
        p.shader = null
        p.shader = RadialGradient(1020f, 20f, 520f, Color.argb(35, 130, 170, 220), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,1280f,720f,p)
        p.shader=null
    }

    private fun drawBottomGradient(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        p.shader = LinearGradient(0f, y + h * .45f, 0f, y + h, Color.TRANSPARENT, Color.argb(220, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(x, y, x + w, y + h, p)
        p.shader = null
    }

    private fun drawBackgroundPhoto(
        c: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        bmp: Bitmap?,
        item: PhotoItem? = currentItem()
    ) {
        fill(c, x, y, x + w, y + h, Color.rgb(6, 12, 18))
        if (bmp == null || bmp === emptySlideshowBackground) {
            drawBitmapCenterCrop(c, emptySlideshowBackground, x, y, w, h)
            return
        }
        when (displayImageMode()) {
            1 -> drawBitmapFit(c, bmp, x, y, w, h)
            2 -> drawBitmapOriginal(c, bmp, x, y, w, h)
            3 -> {
                drawSoftBackground(c, bmp, x, y, w, h)
                fill(c, x, y, x + w, y + h, Color.argb(75, 0, 0, 0))
                drawBitmapFit(c, bmp, x, y, w, h)
            }
            in 4..6 -> drawMosaic(c, x, y, w, h, imageMode - 2)
            else -> drawBitmapSmartCrop(c, bmp, x, y, w, h, item)
        }
    }

    private fun drawMosaic(c: Canvas, x: Float, y: Float, w: Float, h: Float, requestedCount: Int) {
        val count = requestedCount.coerceIn(2, 4)
        val items = mosaicItems(count)
        if (items.isEmpty()) {
            drawBitmapSmartCrop(c, currentBitmap(), x, y, w, h, currentItem())
            return
        }
        val gap = 6f
        val rects = when (count) {
            2 -> listOf(
                RectF(x, y, x + (w - gap) / 2f, y + h),
                RectF(x + (w + gap) / 2f, y, x + w, y + h)
            )
            3 -> listOf(
                RectF(x, y, x + w * .58f - gap / 2f, y + h),
                RectF(x + w * .58f + gap / 2f, y, x + w, y + (h - gap) / 2f),
                RectF(x + w * .58f + gap / 2f, y + (h + gap) / 2f, x + w, y + h)
            )
            else -> listOf(
                RectF(x, y, x + (w - gap) / 2f, y + (h - gap) / 2f),
                RectF(x + (w + gap) / 2f, y, x + w, y + (h - gap) / 2f),
                RectF(x, y + (h + gap) / 2f, x + (w - gap) / 2f, y + h),
                RectF(x + (w + gap) / 2f, y + (h + gap) / 2f, x + w, y + h)
            )
        }
        rects.forEachIndexed { index, rect ->
            val mosaicItem = items.getOrNull(index) ?: return@forEachIndexed
            val key = mosaicItem.uri.toString()
            val mosaicBitmap = synchronized(highResCache) { highResCache[key] }
                ?: synchronized(bitmapCache) { bitmapCache[key] }
            if (mosaicBitmap == null) {
                preload(listOf(mosaicItem.uri))
                drawBitmapCenterCrop(c, emptySlideshowBackground, rect.left, rect.top, rect.width(), rect.height())
            } else {
                drawBitmapSmartCrop(c, mosaicBitmap, rect.left, rect.top, rect.width(), rect.height(), mosaicItem)
            }
        }
    }

    private fun drawBitmapSmartCrop(
        c: Canvas,
        bmp: Bitmap?,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        item: PhotoItem?
    ) {
        if (bmp == null || bmp.isRecycled) return
        val crop = SmartCropPolicy.compute(bmp.width, bmp.height, w, h, smartCropAnchors(item, bmp))
        val src = Rect(
            crop.left.toInt().coerceIn(0, max(0, bmp.width - 1)),
            crop.top.toInt().coerceIn(0, max(0, bmp.height - 1)),
            crop.right.toInt().coerceIn(1, bmp.width),
            crop.bottom.toInt().coerceIn(1, bmp.height)
        )
        c.drawBitmap(bmp, src, RectF(x, y, x + w, y + h), imagePaint)
    }

    private fun smartCropAnchors(item: PhotoItem?, bmp: Bitmap): List<SmartCropPolicy.Anchor> {
        val key = item?.uri?.toString() ?: ("demo:" + bmp.width + "x" + bmp.height)
        smartCropAnchorCache[key]?.let { return it }

        val signature = item?.let {
            val mime = runCatching { context.contentResolver.getType(it.uri) }.getOrNull().orEmpty()
            MediaMetadataStore.signature(context, it.uri, mime)
        }
        if (item != null && signature != null) {
            MediaMetadataStore.readCropAnchor(context, item.uri, signature)?.let { cached ->
                return listOf(
                    SmartCropPolicy.Anchor(cached.x, cached.y, cached.weight)
                ).also { smartCropAnchorCache[key] = it }
            }
        }

        val detected = detectFaceAnchors(bmp).ifEmpty { listOf(estimateSubjectAnchor(bmp)) }
        val totalWeight = detected.sumOf { it.weight.coerceAtLeast(.001f).toDouble() }.toFloat().coerceAtLeast(.001f)
        val aggregate = SmartCropPolicy.Anchor(
            x = (detected.sumOf { (it.x * it.weight.coerceAtLeast(.001f)).toDouble() }.toFloat() / totalWeight).coerceIn(0f, 1f),
            y = (detected.sumOf { (it.y * it.weight.coerceAtLeast(.001f)).toDouble() }.toFloat() / totalWeight).coerceIn(0f, 1f),
            weight = totalWeight
        )
        val result = listOf(aggregate)
        smartCropAnchorCache[key] = result
        if (item != null && signature != null) {
            MediaMetadataStore.writeCropAnchor(
                context,
                item.uri,
                signature,
                MediaMetadataStore.CachedCropAnchor(aggregate.x, aggregate.y, aggregate.weight)
            )
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun detectFaceAnchors(source: Bitmap): List<SmartCropPolicy.Anchor> = runCatching {
        val maxSide = 480f
        val ratio = min(1f, maxSide / max(source.width, source.height).coerceAtLeast(1).toFloat())
        var w = max(2, (source.width * ratio).toInt())
        if (w % 2 != 0) w--
        val h = max(2, (source.height * ratio).toInt())
        val rgb = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(rgb).drawBitmap(source, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val faces = arrayOfNulls<android.media.FaceDetector.Face>(6)
        val detector = android.media.FaceDetector(w, h, faces.size)
        val found = detector.findFaces(rgb, faces)
        val point = PointF()
        val result = (0 until found).mapNotNull { index ->
            val face = faces[index] ?: return@mapNotNull null
            if (face.confidence() < .25f) return@mapNotNull null
            face.getMidPoint(point)
            SmartCropPolicy.Anchor(
                x = (point.x / w.toFloat()).coerceIn(0f, 1f),
                y = (point.y / h.toFloat()).coerceIn(0f, 1f),
                weight = max(1f, face.eyesDistance())
            )
        }
        rgb.recycle()
        result
    }.getOrDefault(emptyList())

    private fun estimateSubjectAnchor(source: Bitmap): SmartCropPolicy.Anchor {
        val cols = 9
        val rows = 7
        var bestScore = Float.NEGATIVE_INFINITY
        var bestX = .5f
        var bestY = .5f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val nx = (col + .5f) / cols
                val ny = (row + .5f) / rows
                val px = (nx * (source.width - 1)).toInt().coerceIn(0, source.width - 1)
                val py = (ny * (source.height - 1)).toInt().coerceIn(0, source.height - 1)
                val color = source.getPixel(px, py)
                val r = Color.red(color).toFloat()
                val g = Color.green(color).toFloat()
                val b = Color.blue(color).toFloat()
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val saturation = maxC - minC
                val centerBias = 1f - (kotlin.math.abs(nx - .5f) * .55f + kotlin.math.abs(ny - .45f) * .35f)
                val neighborX = min(source.width - 1, px + max(1, source.width / cols / 2))
                val neighborY = min(source.height - 1, py + max(1, source.height / rows / 2))
                val c2 = source.getPixel(neighborX, neighborY)
                val contrast = kotlin.math.abs(r - Color.red(c2)) +
                    kotlin.math.abs(g - Color.green(c2)) +
                    kotlin.math.abs(b - Color.blue(c2))
                val score = contrast + saturation * .55f + centerBias * 90f
                if (score > bestScore) {
                    bestScore = score
                    bestX = nx
                    bestY = ny
                }
            }
        }
        return SmartCropPolicy.Anchor(bestX, bestY, 1f)
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
        p.color = interfaceColor(color)
        p.textSize = size * if (renderingSlideshowText || slideshow) 1f else interfaceFontScale
        p.typeface = interfaceTypeface(weight)
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
        p.textSize = size * if (renderingSlideshowText || slideshow) 1f else interfaceFontScale
        p.typeface = interfaceTypeface(weight)
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
        p.clearShadowLayer(); p.shader = null; p.style = Paint.Style.FILL; p.color = interfaceColor(color); c.drawRect(l,t,r,b,p)
    }

    private fun round(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        p.clearShadowLayer(); p.shader = null; p.style = Paint.Style.FILL; p.color = interfaceColor(color); c.drawRoundRect(l,t,r,b,radius,radius,p)
    }

    private fun gradientRound(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, c1: Int, c2: Int) {
        p.clearShadowLayer()
        p.shader = LinearGradient(l,t,r,b,interfaceColor(c1),interfaceColor(c2),Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawRoundRect(l,t,r,b,radius,radius,p)
        p.shader = null
    }

    private fun strokeRound(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, width: Float) {
        stroke.style=Paint.Style.STROKE;stroke.color=if (lightInterface && Color.red(color) < 65 && Color.blue(color) < 110) Color.rgb(215, 225, 238) else interfaceColor(color);stroke.strokeWidth=width;c.drawRoundRect(l,t,r,b,radius,radius,stroke)
    }

    private fun circle(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        p.clearShadowLayer();p.shader=null;p.style=Paint.Style.FILL;p.color=color;c.drawCircle(x,y,radius,p)
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
            togglePermanentAlbum(album)
            return true
        }
        if (page == 2 && !navFocus) {
            editorColumn = 1
            editorMoveMode = !editorMoveMode
            invalidate()
            return true
        }
        if (page == 3 && settingsCategory == 8 && settingsColumn == 1 &&
            settingsControl == 13 && remoteEnabled
        ) {
            remoteToken = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
            remoteQrBitmap?.let { if (!it.isRecycled) it.recycle() }
            remoteQrBitmap = null
            remoteQrUrl = ""
            updateRemoteServer()
            savePrefs()
            showRemoteQrIfAvailable()
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
        if (page == 1 && photosRow == 1 && !slideshow &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (event?.repeatCount ?: 0) > 0) return true

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

        if (memoriesOverlayVisible) {
            val items = memoriesItems()
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (memoriesTabFocus) {
                        memoriesMode = (memoriesMode - 1 + 3) % 3
                        memoriesIndex = 0
                    } else if (items.isNotEmpty()) {
                        memoriesIndex = (memoriesIndex - 1 + items.size) % items.size
                    }
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (memoriesTabFocus) {
                        memoriesMode = (memoriesMode + 1) % 3
                        memoriesIndex = 0
                    } else if (items.isNotEmpty()) {
                        memoriesIndex = (memoriesIndex + 1) % items.size
                    }
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!memoriesTabFocus) {
                        if (memoriesIndex < 4) memoriesTabFocus = true
                        else if (items.isNotEmpty()) memoriesIndex = (memoriesIndex - 4).coerceAtLeast(0)
                    }
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (memoriesTabFocus) {
                        memoriesTabFocus = false
                        memoriesIndex = 0
                    } else if (items.isNotEmpty()) {
                        memoriesIndex = (memoriesIndex + 4).coerceAtMost(items.lastIndex)
                    }
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (memoriesTabFocus) {
                        memoriesTabFocus = false
                        memoriesIndex = 0
                    } else {
                        items.getOrNull(memoriesIndex)?.let { item ->
                            val active = activePhotos()
                            val idx = active.indexOfFirst { it.uri == item.uri }
                            if (idx >= 0) currentPhoto = idx
                            memoriesOverlayVisible = false
                            preloadAroundCurrent()
                            invalidate()
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_BACK -> {
                    memoriesOverlayVisible = false
                    invalidate()
                    true
                }
                else -> true
            }
        }

        if (historyOverlayVisible) {
            val items = recentItemsForOverlay()
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (items.isNotEmpty()) historyOverlayIndex = (historyOverlayIndex - 1 + items.size) % items.size; invalidate(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (items.isNotEmpty()) historyOverlayIndex = (historyOverlayIndex + 1) % items.size; invalidate(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (items.isNotEmpty()) historyOverlayIndex = (historyOverlayIndex - 4 + items.size) % items.size; invalidate(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (items.isNotEmpty()) historyOverlayIndex = (historyOverlayIndex + 4) % items.size; invalidate(); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    items.getOrNull(historyOverlayIndex)?.let { item ->
                        val active = activePhotos()
                        val idx = active.indexOfFirst { it.uri == item.uri }
                        if (idx >= 0) currentPhoto = idx
                    }
                    historyOverlayVisible = false
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_BACK -> { historyOverlayVisible = false; invalidate(); true }
                else -> true
            }
        }

        if (technicalDiagnosticsVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    technicalDiagnosticsVisible = false
                    invalidate()
                    true
                }
                else -> true
            }
        }

        if (quickMenuVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    quickMenuIndex = (quickMenuIndex - 1 + 8) % 8
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    quickMenuIndex = (quickMenuIndex + 1) % 8
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
                KeyEvent.KEYCODE_DPAD_DOWN -> { infoPanelScroll+=40f*interfaceFontScale;invalidate();true }
                KeyEvent.KEYCODE_DPAD_UP -> { infoPanelScroll=max(0f,infoPanelScroll-40f*interfaceFontScale);invalidate();true }
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

        if (page == 0 && !navFocus && !previewControlsVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { previewNext(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { previewNext(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    previewControlsVisible = true; navFocus = true; invalidate(); return true
                }
                else -> return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> if (page == 3 && settingsEditing) {
                settingsEditing = false; invalidate(); return true
            } else if (page == 3 && settingsColumn == 1 && !advancedRulesOpen) {
                settingsColumn = 0; invalidate(); return true
            } else if (page == 3 && settingsCategory == 8 && advancedRulesOpen) {
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
                rebuildLibraryIndexes()
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
                infoPanelScroll = 0f
                infoPanelVisible = true
            }
            4 -> {
                quickMenuVisible = false
                togglePause()
            }
            5 -> {
                quickMenuVisible = false
                historyOverlayIndex = 0
                historyOverlayVisible = true
            }
            6 -> {
                quickMenuVisible = false
                technicalDiagnosticsVisible = true
            }
            7 -> {
                quickMenuVisible = false
                memoriesMode = 0
                memoriesIndex = 0
                memoriesTabFocus = true
                memoriesOverlayVisible = true
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
                    0 -> previewControlsVisible = true
                    1 -> photosRow = 1
                    2 -> editorColumn = 1
                    3 -> Unit
                }
            }
            invalidate()
            announceAccessibilityState()
            return
        }

        when (page) {
            0 -> if (dir > 0) navFocus = true
            1 -> when(photosRow) {
                0 -> if(dir>0) photosRow=3
                3 -> photosRow=if(dir>0) 1 else 0
                1 -> {
                    val columns=albumColumns()
                    val total=albumPairs().size
                    if(dir<0 && albumFocus<columns) photosRow=3
                    else if(dir>0 && albumFocus/columns==(max(0,total-1)/columns)) { photosRow=2;navFocus=true }
                    else albumFocus=(albumFocus+dir*columns).coerceIn(0,max(0,total-1))
                }
                else -> navFocus=true
            }
            2 -> {
                if (editorColumn == 1 && editorMoveMode) {
                    moveEditorByPixels(0, dir)
                } else if (editorColumn == 0) {
                    editorElement = (editorElement + dir).coerceIn(0, elementNames.lastIndex)
                    editorControl = 0; editorScroll = 0f
                } else if (editorColumn == 2) {
                    if(editorControl == 11 && dir>0) navFocus=true else editorControl = (editorControl + dir).coerceIn(0, 11)
                } else if (dir > 0) {
                    navFocus = true
                }
            }
            3 -> {
                settingsEditing = false
                if (settingsColumn == 0) {
                    if (dir > 0 && settingsCategory == 8) navFocus = true
                    else settingsCategory = (settingsCategory + dir).coerceIn(0, 8)
                    settingsControl = settingRows().first().id
                    settingsScroll = 0f
                } else {
                    val order = settingRows().map { it.id }.distinct()
                    val index = order.indexOf(settingsControl).coerceAtLeast(0)
                    if (dir > 0 && index == order.lastIndex) navFocus = true
                    else settingsControl = order[(index + dir).coerceIn(0, order.lastIndex)]
                    if (settingsCategory == 6 && settingsControl in 1..2) settingsControl = if (dir > 0) 3 else 0
                }
            }
        }
        savePrefs()
        invalidate()
        announceAccessibilityState()
    }

    private fun moveHorizontal(dir: Int) {
        if (navFocus) {
            val target = (page + dir + 4) % 4
            if (target == 3 && !canOpenSettings()) {
                onUnlockSettings()
                return
            }
            page = target
            previewControlsVisible = false
            invalidate()
            announceAccessibilityState()
            return
        }

        when (page) {
            0 -> if (activePhotos().isNotEmpty()) previewNext(dir)
            1 -> when (photosRow) {
                0 -> sourceFocus = (sourceFocus + dir).coerceIn(0, 2)
                3 -> adjustAlbumDensity(dir)
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
                    editorColumn == 0 && dir > 0 -> { editorColumn = 1; editorControl = 0; editorScroll = 0f }
                    editorColumn == 2 && dir < 0 -> editorColumn = 1
                    editorColumn == 2 -> adjustEditor(dir)
                }
            }
            3 -> {
                if (settingsColumn == 0 && dir > 0) {
                    settingsColumn = 1
                    settingsControl = settingRows().first().id
                    settingsScroll = 0f
                    settingsEditing = false
                } else if (settingsColumn == 1 && settingsCategory == 6 && settingsControl in 0..2) {
                    if (settingsControl == 0 && dir < 0) settingsColumn = 0
                    else settingsControl = (settingsControl + dir).coerceIn(0, 2)
                } else if (settingsColumn == 1 && settingsEditing) adjustSettings(dir)
                else if (settingsColumn == 1 && dir < 0) settingsColumn = 0
                else if (settingsColumn == 1 && dir > 0 && !settingRows().first { it.id == settingsControl }.action) {
                    settingsEditing = true
                    adjustSettings(dir)
                }
            }
        }
        savePrefs()
        invalidate()
        announceAccessibilityState()
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
        0 -> 11
        1 -> elementNames.size
        2 -> 14
        3 -> transitions.lastIndex
        4 -> 9
        5 -> 2
        6 -> 8
        7 -> 6
        else -> if (advancedRulesOpen) 9 else 16
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
        if (settingsControl == 8) {
            advancedRulesOpen = false
            settingsControl = 0
            return
        }
        if (settingsControl == 9) {
            rulesBySource = !rulesBySource
            settingsControl = 0
            return
        }

        if (rulesBySource) {
            val sources = sourceLabels()
            if (sources.isEmpty()) return
            if (settingsControl == 0) {
                ruleSourceIndex = (ruleSourceIndex + dir + sources.size) % sources.size
                return
            }
            val sourceName = sources[ruleSourceIndex.coerceIn(0, sources.lastIndex)]
            val rule = sourceRules.getOrPut(sourceName) { SourceRule() }
            when (settingsControl) {
                1 -> rule.enabled = !rule.enabled
                2 -> rule.seasonMode = (rule.seasonMode + dir + 5) % 5
                3 -> rule.startHour = (rule.startHour + dir + 24) % 24
                4 -> {
                    var next = rule.endHour + dir
                    if (next < 1) next = 24
                    if (next > 24) next = 1
                    rule.endHour = next
                }
                5 -> {
                    rule.durationSeconds = if (dir > 0) {
                        if (rule.durationSeconds == 0) 5 else (rule.durationSeconds + 5).coerceAtMost(120)
                    } else {
                        if (rule.durationSeconds <= 5) 0 else rule.durationSeconds - 5
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
            return
        }

        val albums = albumPairs()
        if (albums.isEmpty()) return
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
                rule.durationSeconds = if (dir > 0) {
                    if (rule.durationSeconds == 0) 5 else (rule.durationSeconds + 5).coerceAtMost(120)
                } else {
                    if (rule.durationSeconds <= 5) 0 else rule.durationSeconds - 5
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
                10 -> { val modes = listOf(1, 0, 3); val index = modes.indexOf(imageMode).coerceAtLeast(0); imageMode = modes[(index + dir + modes.size) % modes.size]; autonomousImageModeOverride = null }
                9 -> darkTheme = !darkTheme
                0 -> durationSeconds = (durationSeconds + dir).coerceIn(2, 120)
                1 -> fixedImage = !fixedImage
                2 -> loop = !loop
                3 -> randomOrder = !randomOrder
                4 -> transitionIndex = (transitionIndex + dir + transitions.size) % transitions.size
                5 -> transitionSeconds = (transitionSeconds + dir * .1f).coerceIn(.2f, 4f)
                11 -> fadeIntensityPercent = (fadeIntensityPercent + dir * 5).coerceIn(0, 100)
                6 -> kenBurns = !kenBurns
                7 -> zoomLevel = (zoomLevel + 1 + dir + 4) % 4 - 1
                8 -> {
                    autonomousSlideshow = !autonomousSlideshow
                    if (!autonomousSlideshow) autonomousImageModeOverride = null
                }
            }
            1 -> if (settingsControl == elementNames.size) hideTitleExtensions = !hideTitleExtensions else toggleElement(settingsControl)
            2 -> when (settingsControl) {
                13 -> interfaceFontScale = (interfaceFontScale + dir * .05f).coerceIn(.85f, 1.6f)
                14 -> darkTheme = !darkTheme
                12 -> applyPreset((layoutPreset + dir + 4) % 4)
                10 -> interfaceFont = (interfaceFont + dir + 4) % 4
                11 -> {
                    slideshowFont = (slideshowFont + dir + 4) % 4
                    styles.forEach { it.font = slideshowFont }
                }
                else -> editorElement = (editorElement + dir + elementNames.size) % elementNames.size
            }
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
                8 -> {
                    backgroundAnalysis = !backgroundAnalysis
                    if (backgroundAnalysis) scheduleBackgroundAnalysis() else backgroundAnalysisGeneration++
                }
            }
            7 -> when (settingsControl) {
                0 -> {
                    autonomousSlideshow = !autonomousSlideshow
                    if (!autonomousSlideshow) autonomousImageModeOverride = null
                }
                1 -> smartSelectionMode = (smartSelectionMode + dir + 5) % 5
                2 -> {
                    backgroundAnalysis = !backgroundAnalysis
                    if (backgroundAnalysis) scheduleBackgroundAnalysis() else backgroundAnalysisGeneration++
                }
            }
            else -> if (advancedRulesOpen) {
                adjustAlbumRule(dir)
            } else when (settingsControl) {
                0 -> imageMode = (imageMode + dir + imageModes.size) % imageModes.size
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
                14 -> onSettingsPin()
                15 -> smartSelectionMode = (smartSelectionMode + dir + 5) % 5
            }
        }
        scheduleSlideshow()
        scheduleInactivity()
    }

    private fun launchFromPreview() {
        if (activePhotos().isNotEmpty()) {
            navFocus = true
            startSlideshow()
        } else {
            page = 1
            navFocus = false
            photosRow = 0
            sourceFocus = 0
            invalidate()
        }
    }

    private fun activate() {
        if (navFocus) {
            navFocus = false
            when (page) { 0 -> previewControlsVisible = false; 1 -> photosRow = 0; 2 -> editorColumn = 0; 3 -> settingsColumn = 0 }
            invalidate(); return
        }
        when (page) {
            0 -> if (previewControlsVisible) launchFromPreview() else { previewControlsVisible = true; navFocus = true }
            1 -> when (photosRow) {
                3 -> adjustAlbumDensity(1)
                0 -> when (sourceFocus) {
                    0 -> onExactSource()
                    1 -> onFolderSource()
                    else -> onPickPhotos()
                }
                1 -> {
                    val name = currentAlbumName()
                    if (library.isNotEmpty()) {
                        togglePermanentAlbum(name)
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
                    0 -> { editorColumn = 2; editorControl = 0; editorScroll = 0f }
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
                if (settingsColumn == 0) {
                    settingsColumn = 1; settingsControl = settingRows().first().id
                    settingsEditing = false; settingsScroll = 0f
                } else if (!settingRows().first { it.id == settingsControl }.action) {
                    settingsEditing = !settingsEditing
                } else when(settingsCategory) {
                    1 -> if(settingsControl == elementNames.size) hideTitleExtensions = !hideTitleExtensions else toggleElement(settingsControl)
                    2 -> { page=2; editorElement=settingsControl; editorColumn=0 }
                    3 -> transitionIndex=settingsControl.coerceIn(0,transitions.lastIndex)
                    5 -> if(settingsControl == 2) onWeatherLocation() else Unit
                    6 -> when(settingsControl) {
                        0 -> onExactSource(); 1 -> onFolderSource(); 2 -> onPickPhotos()
                        4 -> onAlbumSearch(); 5 -> toggleAllAlbums(); 6 -> clearAllMasks(); 7 -> onNetworkSource()
                    }
                    7 -> Unit
                    8 -> if(advancedRulesOpen) adjustAlbumRule(1) else when(settingsControl) {
                        8 -> onExportSettings(); 9 -> onImportSettings()
                        11 -> { advancedRulesOpen=true; settingsControl=0; settingsScroll=0f }
                        12 -> interactionDiagnostics = !interactionDiagnostics
                        13 -> { remoteEnabled = !remoteEnabled; updateRemoteServer(); if(remoteEnabled) showRemoteQrIfAvailable() else remoteQrVisible=false }
                        14 -> onSettingsPin(); 16 -> onScreensaver()
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
                    title = displayedTitle(item?.title ?: "Photo TV"),
                    album = albumDisplay(item),
                    slideshow = slideshow,
                    paused = paused,
                    durationSeconds = durationSeconds,
                    transition = transitions[transitionIndex.coerceIn(0, transitions.lastIndex)],
                    imageMode = imageModeLabel(),
                    albums = albumPairs().map { it.first },
                    sources = sourceLabels(),
                    sourceFilter = remoteSourceFilter,
                    transitions = transitions,
                    imageModes = imageModes,
                    smartModes = (0..4).map(SmartSelectionPolicy::modeLabel),
                    position = currentPhoto,
                    total = activePhotos().size,
                    favorite = item?.uri?.toString()?.let(favorites::contains) == true,
                    previewTitles = remotePreviewItems().map { displayedTitle(it.title) },
                    smartMode = SmartSelectionPolicy.modeLabel(smartSelectionMode)
                )
            },
            thumbnailProvider = { slot -> remoteThumbnail(slot) }
        ) { command ->
            post { handleRemoteCommand(command) }
        }.also { it.start() }
    }

    private fun mosaicItems(count: Int): List<PhotoItem> {
        val items = activePhotos()
        if (items.isEmpty()) return emptyList()
        val poolIndices = if ((randomOrder || autonomousSlideshow) && shuffleBag.isNotEmpty()) {
            buildList {
                add(currentPhoto.coerceIn(0, items.lastIndex))
                shuffleBag.take(47).forEach { if (it in items.indices) add(it) }
            }
        } else {
            (0 until min(48, items.size)).map { (currentPhoto + it) % items.size }
        }.distinct()

        val candidates = poolIndices.mapNotNull { index ->
            items.getOrNull(index)?.let { item ->
                MosaicSelectionPolicy.Candidate(
                    index = index,
                    source = item.sourceLabel,
                    album = item.albums.firstOrNull().orEmpty(),
                    width = item.width,
                    height = item.height,
                    takenAt = item.takenAt,
                    mediaType = item.mediaType,
                    scene = sceneCache[item.uri.toString()].orEmpty()
                )
            }
        }
        return MosaicSelectionPolicy.select(candidates, currentPhoto, count)
            .mapNotNull { items.getOrNull(it) }
    }

    private fun remotePreviewItems(count: Int = 4): List<PhotoItem> {
        val items = activePhotos()
        if (items.isEmpty()) return emptyList()
        val indices = if ((randomOrder || autonomousSlideshow) && shuffleBag.isNotEmpty()) {
            buildList {
                add(currentPhoto.coerceIn(0, items.lastIndex))
                shuffleBag.take(count - 1).forEach { if (it in items.indices) add(it) }
            }
        } else {
            (0 until min(count, items.size)).map { (currentPhoto + it) % items.size }
        }
        return indices.distinct().mapNotNull { items.getOrNull(it) }
    }

    private fun remoteThumbnail(slot: Int): ByteArray? {
        val item = remotePreviewItems().getOrNull(slot) ?: return null
        if (item.mediaType == "video") return null
        val key = item.uri.toString()
        val mime = runCatching { context.contentResolver.getType(item.uri) }.getOrNull().orEmpty()
        val signature = MediaMetadataStore.signature(context, item.uri, mime)
        val persistentKey = buildString {
            append(key)
            append('|').append(signature.modified)
            append('|').append(signature.size)
            append('|').append(item.width).append('x').append(item.height)
            append('|').append(item.takenAt)
        }
        if (signature.cacheable) {
            ThumbnailDiskCache.read(context, persistentKey)?.let { return it }
        }

        val bmp = synchronized(highResCache) { highResCache[key] }
            ?: synchronized(bitmapCache) { bitmapCache[key] }
        if (bmp == null) {
            preload(listOf(item.uri))
            return null
        }
        if (bmp.isRecycled) return null
        val maxSide = 360
        val scale = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height).coerceAtLeast(1).toFloat())
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                max(1, (bmp.width * scale).toInt()),
                max(1, (bmp.height * scale).toInt()),
                true
            )
        } else bmp
        return runCatching {
            java.io.ByteArrayOutputStream().use { stream ->
                out.compress(Bitmap.CompressFormat.JPEG, 78, stream)
                stream.toByteArray()
            }
        }.getOrNull().also { bytes ->
            if (bytes != null && signature.cacheable) {
                ThumbnailDiskCache.write(context, persistentKey, bytes)
            }
            if (out !== bmp && !out.isRecycled) out.recycle()
        }
    }

    private fun handleRemoteCommand(command: String) {
        val commandName = command.substringBefore('|')
        val commandValue = command.substringAfter('|', "").trim()
        when (commandName) {
            "prev" -> if (slideshow) slideshowNext(-1) else previewNext(-1)
            "next" -> if (slideshow) slideshowNext(1) else previewNext(1)
            "pause" -> if (slideshow) togglePause() else startSlideshow()
            "stop" -> if (slideshow) stopSlideshow()
            "start" -> if (!slideshow) startSlideshow()
            "sources" -> {
                if (slideshow) stopSlideshow()
                page = 1
                photosRow = 0
                sourceFocus = 0
                navFocus = false
                invalidate()
            }
            "album_select" -> {
                val all = albumIndex.keys.toList()
                selectedAlbums.clear()
                if (commandValue.isBlank() || commandValue == "Tous les albums") {
                    selectedAlbums.addAll(all)
                } else if (all.contains(commandValue)) {
                    selectedAlbums += commandValue
                } else {
                    selectedAlbums.addAll(all)
                }
                currentPhoto = 0
                preloadAroundCurrent()
                savePrefs()
                invalidate()
            }
            "source_select" -> {
                remoteSourceFilter = commandValue.takeUnless {
                    it.isBlank() || it == "Toutes les sources"
                }
                currentPhoto = 0
                preloadAroundCurrent()
                invalidate()
            }
            "search" -> {
                albumSearch = commandValue
                page = 1
                photosRow = 1
                navFocus = false
                albumFocus = 0
                photoFocus = 0
                savePrefs()
                invalidate()
            }
            "network_prepare" -> {
                val parts = commandValue.split('	')
                val kind = runCatching { NetworkLibrary.Kind.valueOf(parts.getOrNull(0).orEmpty()) }.getOrNull()
                val address = parts.getOrNull(1).orEmpty().trim()
                val user = parts.getOrNull(2).orEmpty().trim()
                if (kind != null && address.isNotBlank()) {
                    onPrepareNetworkSource(kind, address, user)
                }
            }
            "favorite" -> currentItem()?.let { item ->
                val key = item.uri.toString()
                if (favorites.contains(key)) favorites.remove(key) else favorites.add(key)
                rebuildLibraryIndexes()
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
            "transition_select" -> {
                val idx = transitions.indexOf(commandValue)
                if (idx >= 0) {
                    transitionIndex = idx
                    savePrefs()
                    invalidate()
                }
            }
            "mode_next" -> {
                imageMode = (imageMode + 1) % imageModes.size
                savePrefs()
                syncVideoPlayback()
                preloadAroundCurrent()
                invalidate()
            }
            "mode_select" -> {
                val idx = imageModes.indexOf(commandValue)
                if (idx >= 0) {
                    imageMode = idx
                    savePrefs()
                    syncVideoPlayback()
                    preloadAroundCurrent()
                    invalidate()
                }
            }
            "smart_select" -> {
                val idx = (0..4).firstOrNull { SmartSelectionPolicy.modeLabel(it) == commandValue }
                if (idx != null) {
                    smartSelectionMode = idx
                    rebuildShuffleBag()
                    savePrefs()
                    invalidate()
                }
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
        selectedAlbums.addAll(albumIndex.keys)
        savePrefs()
    }

    private fun togglePermanentAlbum(name: String) {
        if (name.isBlank()) return
        if (hiddenAlbums.remove(name)) selectedAlbums.add(name)
        else {
            hiddenAlbums.add(name)
            selectedAlbums.remove(name)
        }
        currentPhoto = 0
        photoFocus = 0
        savePrefs()
        invalidate()
    }

    private fun toggleAllAlbums() {
        val all = albumIndex.keys.toList()
        if (all.isEmpty()) return
        if (all.none { it in hiddenAlbums }) {
            hiddenAlbums.addAll(all)
            selectedAlbums.clear()
        }
        else {
            hiddenAlbums.removeAll(all.toSet())
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
        if (displayImageMode() >= 4) {
            onVideoPlayback(null, videoSound)
            return
        }
        val item = if (slideshow) currentItem() else null
        if (item?.mediaType != "video" || !supportsVideoPlayback) {
            onVideoPlayback(null, videoSound)
            return
        }

        if (!NetworkLibrary.isNetworkUri(item.uri)) {
            onVideoPlayback(item.uri, videoSound)
            return
        }

        val expected = item.uri
        onVideoPlayback(null, videoSound)
        executor.execute {
            val file = NetworkLibrary.materialize(context, expected)
            post {
                if (slideshow && currentItem()?.uri == expected && file != null) {
                    onVideoPlayback(Uri.fromFile(file), videoSound)
                }
            }
        }
    }

    private fun startSlideshow() {
        if (lifecycleClosed) return
        val items = activePhotos()
        if (items.isEmpty()) return
        currentPhoto = currentPhoto.coerceIn(0, items.lastIndex)
        slideshow = true
        paused = false
        quickMenuVisible = false
        infoPanelVisible = false
        history.clear()
        autonomousSlideNumber = 0L
        updateAutonomousPresentation()
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
        if (!automationMode && currentItem()?.uri?.let { DriveRemoteLibrary.isRemote(it) } == true && !currentPhotoLoaded()) {
            slideStartedAt = System.currentTimeMillis()
            handler.postDelayed({ scheduleSlideshow() }, 500L)
            return
        }
        val seconds = effectiveDuration(currentItem())
        handler.postDelayed({ slideshowNext(1) }, seconds * 1000L)
    }

    private fun rebuildShuffleBag() {
        val items = activePhotos()
        shuffleBag.clear()
        val smartCandidates = items.mapIndexed { index, item ->
            SmartSelectionPolicy.Candidate(
                index = index,
                favorite = favorites.contains(item.uri.toString()),
                width = item.width,
                height = item.height,
                takenAt = item.takenAt,
                sourceLabel = item.sourceLabel,
                albumKey = item.albums.firstOrNull().orEmpty(),
                mediaType = item.mediaType,
                qualityScore = qualityCache[item.uri.toString()]?.score ?: -1
            )
        }
        val activeSmartMode = effectiveSmartSelectionMode()
        val baseOrder = if (activeSmartMode == SmartSelectionPolicy.OFF) {
            SlideshowOrder.newBag(items.size, currentPhoto)
        } else {
            SmartSelectionPolicy.buildOrder(
                candidates = smartCandidates,
                currentIndex = currentPhoto,
                mode = activeSmartMode
            )
        }
        val diversityCandidates = items.mapIndexed { index, item ->
            SlideshowDiversityPolicy.Candidate(
                index = index,
                source = item.sourceLabel,
                album = item.albums.firstOrNull().orEmpty(),
                width = item.width,
                height = item.height,
                takenAt = item.takenAt,
                scene = if (autonomousSlideshow) currentScene(item).orEmpty() else ""
            )
        }
        shuffleBag.addAll(
            SlideshowDiversityPolicy.reorder(baseOrder, diversityCandidates, currentPhoto)
        )
    }

    private fun preloadAroundCurrent() {
        if (automationMode) return
        val items = activePhotos()
        if (items.isEmpty()) return

        val rt = Runtime.getRuntime()
        val freeRatio = ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())).toDouble() /
            rt.maxMemory().coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
        val decision = PreloadPolicy.decide(
            freeRatio = freeRatio,
            secondsPerItem = effectiveDuration(currentItem()),
            avgDecodeMs = if (decodeCount <= 0L) 0L else decodeTotalMs / decodeCount,
            avgNetworkMs = NetworkLibrary.averageRemoteReadMs()
        )
        val displayMode = displayImageMode()
        val mosaicCount = if (displayMode >= 4) (displayMode - 2).coerceIn(2, 4) else 1
        val ahead = max(decision.ahead, mosaicCount - 1)
        val hdAhead = max(decision.hdAhead, if (displayMode >= 4) mosaicCount else 1)

        val indices = if ((randomOrder || autonomousSlideshow) && shuffleBag.isNotEmpty()) {
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
                highResCache.remove(first.key)
            }
        }
        synchronized(bitmapCache) {
            val keep = if (aggressive) 2 else 6
            while (bitmapCache.size > keep) {
                val first = bitmapCache.entries.firstOrNull() ?: break
                bitmapCacheBytes -= first.value.allocationByteCount.toLong()
                bitmapCache.remove(first.key)
            }
        }
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

        if ((randomOrder || autonomousSlideshow) && items.size > 1) {
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
        autonomousSlideNumber++
        updateAutonomousPresentation()
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
                photosRow = 0
                sourceFocus = if (x < 450f) 0 else if (x < 828f) 1 else 2
                when {
                    x in 47f..435f -> onExactSource()
                    x in 450f..812f -> onFolderSource()
                    x in 828f..1238f -> onPickPhotos()
                }
            }
            x in 780f..1238f && y in 222f..262f -> {
                photosRow=3
                adjustAlbumDensity(if(x<1009f) -1 else 1)
            }
            else -> albumHitBoxes.firstOrNull { it.second.contains(x,y) }?.let { hit ->
                photosRow=1;albumFocus=hit.first;photoFocus=0
                if(library.isNotEmpty()) togglePermanentAlbum(currentAlbumName())
            }


        }
        savePrefs()
        invalidate()
    }

    private fun handleEditorTap(x: Float, y: Float) {
        if(x in 32f..352f && y in 120f..600f) {
            val h=78f*interfaceFontScale.coerceAtLeast(1f)+9f
            val count=(480f/h).toInt().coerceAtLeast(1)
            val top=351f-(count*h-9f)/2f
            val first=(editorElement-count/2).coerceIn(0,max(0,elementNames.size-count))
            editorElement=(first+((y-top)/h).toInt()).coerceIn(0,elementNames.lastIndex);editorColumn=0
        } else editorHitBoxes.firstOrNull { it.second.contains(x,y) }?.let {
            editorColumn=2;editorControl=it.first
            if(editorControl in 1..6) adjustEditor(if(x<1094f) -1 else 1) else activate()
        }
        savePrefs();invalidate()
    }

    private fun setSliderFromTap(x: Float, minValue: Float, maxValue: Float): Float {
        val t = ((x - 730f) / 375f).coerceIn(0f, 1f)
        return minValue + (maxValue - minValue) * t
    }

    private fun handleSettingsTap(x: Float, y: Float) {
        navFocus = false
        if (x in 32f..352f && y in 100f..626f) {
            val rowH = 50f * interfaceFontScale.coerceAtLeast(1f)
            val visible = (510f / rowH).toInt().coerceAtLeast(1)
            val first = (settingsCategory - visible / 2).coerceIn(0, max(0, 9 - visible))
            val top = 354.5f - (min(visible,9)*rowH+16f)/2f + 8f
            if (y < top || y >= top+min(visible,9)*rowH) return
            settingsCategory = (first + ((y - top) / rowH).toInt()).coerceIn(0, 8)
            settingsColumn = 0; settingsControl = settingRows().first().id; settingsEditing = false; settingsScroll = 0f
        } else {
            settingHitBoxes.firstOrNull { it.second.contains(x, y) }?.let {
                settingsColumn = 1; settingsControl = it.first
                val row = settingRows().first { row -> row.id == settingsControl }
                if (row.action) activate() else { adjustSettings(if (x < 814f) -1 else 1); settingsEditing = false }
            }
        }
        savePrefs(); scheduleInactivity(); invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        markInteraction()

        val scale = viewportScale()
        val x = (event.x - viewportLeft()) / scale
        val y = (event.y - viewportTop()) / scale
        if (x !in 0f..1280f || y !in 0f..720f) return true
        diagnosticTouchX = x
        diagnosticTouchY = y

        if (!slideshow && !quickMenuVisible && !infoPanelVisible &&
            ((page == 3 && y in 100f..626f) || (page == 2 && (x < 352f || x > 940f)))) {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    listTouchActive=true;listTouchMoved=false;listTouchY=y;navFocus=false
                    if(page==3) settingsColumn=if(x<352f)0 else 1 else editorColumn=if(x<352f)0 else 2
                }
                MotionEvent.ACTION_MOVE -> if(listTouchActive && kotlin.math.abs(y-listTouchY)>50f) {
                    moveVertical(if(y<listTouchY)1 else -1);listTouchY=y;listTouchMoved=true;return true
                }
            }
        }
        if(event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            listTouchActive=false
            if(listTouchMoved) { listTouchMoved=false;return true }
        }

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
                    if (x in 370f..918f && y in 200f..508.25f) {
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

        if (memoriesOverlayVisible) {
            if (y in 110f..155f && x in 120f..840f) {
                memoriesMode = (((x - 120f) / 250f).toInt()).coerceIn(0, 2)
                memoriesIndex = 0
                memoriesTabFocus = true
                invalidate()
                return true
            }
            if (x in 120f..1140f && y in 180f..585f) {
                val col = ((x - 120f) / 255f).toInt().coerceIn(0, 3)
                val row = ((y - 180f) / 135f).toInt().coerceIn(0, 2)
                val index = row * 4 + col
                val items = memoriesItems()
                items.getOrNull(index)?.let { item ->
                    val active = activePhotos()
                    val idx = active.indexOfFirst { it.uri == item.uri }
                    if (idx >= 0) currentPhoto = idx
                }
                memoriesOverlayVisible = false
                preloadAroundCurrent()
                invalidate()
                return true
            }
            memoriesOverlayVisible = false
            invalidate()
            return true
        }

        if (historyOverlayVisible) {
            if (x in 145f..1125f && y in 145f..610f) {
                val col = ((x - 145f) / 245f).toInt().coerceIn(0, 3)
                val row = ((y - 145f) / 155f).toInt().coerceIn(0, 2)
                val index = row * 4 + col
                val items = recentItemsForOverlay()
                items.getOrNull(index)?.let { item ->
                    val active = activePhotos()
                    val idx = active.indexOfFirst { it.uri == item.uri }
                    if (idx >= 0) currentPhoto = idx
                }
            }
            historyOverlayVisible = false
            invalidate()
            return true
        }

        if (technicalDiagnosticsVisible) {
            technicalDiagnosticsVisible = false
            invalidate()
            return true
        }

        if (quickMenuVisible) {
            if (x in 855f..1225f && y in 241f..616f) {
                quickMenuIndex = (((y - 241f) / 47f).toInt()).coerceIn(0, 7)
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

        val tappedTab = navRects().indexOfFirst { it.contains(x,y) }
        if (tappedTab >= 0 && (page != 0 || previewControlsVisible || navFocus)) {
            val target = tappedTab
            if (target == 3 && !canOpenSettings()) {
                onUnlockSettings()
                return true
            }
            page = target
            previewControlsVisible = false
            if (page == 1) photosRow = 0
            if (page == 3) settingsColumn = 0
            navFocus = false
            editorMoveMode = false
            invalidate()
            return true
        }

        when (page) {
            0 -> {
                if ((previewControlsVisible || navFocus) && previewLaunchRect().contains(x,y)) launchFromPreview()
                else if (x < 426f) previewNext(-1)
                else if (x > 854f) previewNext(1)
                else { previewControlsVisible = !previewControlsVisible; navFocus = previewControlsVisible }
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
        st.y = (((y - 200f) / 308.25f) * 100f).coerceIn(0f, 100f)
        if (gridSnap) {
            st.x = snapPercent(st.x)
            st.y = snapPercent(st.y)
        }
    }

    override fun onDetachedFromWindow() {
        lifecycleClosed = true
        remoteServer?.stop()
        remoteServer = null
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
        analysisExecutor.shutdownNow()
    }
}
