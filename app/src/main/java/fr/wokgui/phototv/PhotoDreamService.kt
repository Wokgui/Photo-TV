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

        when (val source = SourceStore.load(this)) {
            is PhotoSourceSpec.Tree -> Thread {
                val loaded = TakeoutLibrary.load(
                    this,
                    source.uri,
                    exactMode = source.exactMode
                )
                renderer.post {
                    renderer.startAsDream(
                        loaded.items,
                        exactAlbums = loaded.exactAlbums,
                        sourceName = if (source.exactMode) "Google Photos / Takeout" else "Dossier local"
                    )
                }
            }.start()

            is PhotoSourceSpec.Picked -> Thread {
                val items = PickedLibrary.load(this, source.uris)
                renderer.post {
                    renderer.startAsDream(
                        items,
                        exactAlbums = false,
                        sourceName = "Sélection de photos"
                    )
                }
            }.start()

            null -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        ui = null
        super.onDetachedFromWindow()
    }
}
