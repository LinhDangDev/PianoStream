package com.pianostream.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import java.io.File

/**
 * Application class: Hilt root + the single app-scoped Coil [ImageLoader]
 * (~250 MB memory+disk, crossfade; `file://` artwork never reaches it —
 * every model is sanitized by `ArtworkUrls.sanitize`, F9).
 */
@HiltAndroidApp
public class PianoStreamApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(125 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "coil"))
                    .maxSizeBytes(125 * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
