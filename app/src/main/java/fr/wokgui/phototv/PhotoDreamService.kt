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
            onPickPhotos = {}
        )
        ui = renderer
        setContentView(renderer)

        val saved = getSharedPreferences("photo_tv", MODE_PRIVATE).getString("takeout_tree", null)
        if (saved != null) {
            val uri = runCatching { Uri.parse(saved) }.getOrNull()
            if (uri != null) {
                Thread {
                    val items = TakeoutLibrary.load(this, uri)
                    renderer.post {
                        renderer.startAsDream(items)
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
