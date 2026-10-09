package fr.wokgui.phototv

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

        Thread {
            val all = mutableListOf<PhotoItem>()
            var exact = true
            SourceStore.loadAll(this).forEach { source ->
                when (source) {
                    PhotoSourceSpec.Drive -> all += DriveRemoteLibrary.load(this)
                    is PhotoSourceSpec.Tree -> runCatching {
                        val loaded = TakeoutLibrary.load(this, source.uri, source.exactMode)
                        all += loaded.items
                        exact = exact && loaded.exactAlbums
                    }.onFailure { exact = false }
                    is PhotoSourceSpec.Picked -> {
                        all += PickedLibrary.load(this, source.uris)
                        exact = false
                    }
                }
            }
            renderer.post {
                if (ui !== renderer) return@post
                renderer.startAsDream(all.filter { it.mediaType == "image" }, exact, "Photos TV • économiseur")
            }
        }.start()
    }

    override fun onDetachedFromWindow() {
        ui = null
        super.onDetachedFromWindow()
    }
}
