package com.animereader

import android.app.Application
import com.animereader.service.OverlayController

class AnimeReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Reload the persisted reading history so it survives app/service restarts.
        OverlayController.bind(this)
    }
}
