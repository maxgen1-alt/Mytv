package com.mytv.launcher

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** App-wide picture loading, tuned for a TV that should feel instant. */
class MyTvApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.mytv.launcher.system.AutoPlay.appContext = applicationContext
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Posters and backdrops have no transparency: half the memory and faster to decode.
        // (Logos with transparency keep full colour.)
        .allowRgb565(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.30).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        // Poster and logo pictures never change at the same address: always use the copy on
        // disk instead of asking the server first, so rows fill in instantly after a restart.
        .respectCacheHeaders(false)
        .build()
}
