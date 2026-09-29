package fr.wokgui.phototv

import android.net.Uri
import android.service.dreams.DreamService
import android.view.View

class PhotoDreamService : DreamService() {
    private var ui: PhotoTvView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        window?.decorView?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val renderer = PhotoTvView(
            context = this,
            onExactSource = {},
            onFolderSource = {},
            onPickPhotos = {}
        )
        ui = renderer
        setContentView(renderer)

        val prefs = getSharedPreferences("photo_tv", MODE_PRIVATE)
        val saved = prefs.getString("takeout_tree", null)
        val exactMode = prefs.getBoolean("takeout_tree_exact", true)
        if (saved != null) {
            val uri = runCatching { Uri.parse(saved) }.getOrNull()
            if (uri != null) {
                Thread {
                    val loaded = TakeoutLibrary.load(this, uri, exactMode = exactMode)
                    renderer.post {
                        renderer.startAsDream(loaded.items)
                    }
                }.start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        ui = null
        super.onDetachedFromWindow()
    }
}
