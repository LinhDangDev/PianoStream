package com.pianostream.core.designsystem.art

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Dominant-color extractor for the NowPlaying gradient: decodes the artwork
 * through the app [ImageLoader] (already F9-sanitized upstream), rasterizes
 * it small and averages — a stable, dependency-free alternative to the
 * `palette` support library. Results are memoized per `mediaId`.
 */
class ArtworkPalette(
    private val context: Context,
    private val imageLoader: ImageLoader,
) {

    private val cache = ConcurrentHashMap<String, Color>()

    /** Dominant color for `mediaId`'s artwork, or `null` when undecodable. */
    suspend fun colorFor(mediaId: String, artworkUri: String?): Color? {
        cache[mediaId]?.let { return it }
        val uri = ArtworkUrls.sanitize(artworkUri) ?: return null
        val color = extract(uri) ?: return null
        cache[mediaId] = color
        return color
    }

    private suspend fun extract(uri: String): Color? {
        val request = ImageRequest.Builder(context).data(uri).size(64).build()
        val result = runCatching { imageLoader.execute(request) }.getOrNull()
            as? SuccessResult ?: return null
        val src = (result.image as? BitmapImage)?.bitmap ?: return null
        val w = 32
        val h = 32
        val bmp = Bitmap.createScaledBitmap(src, w, h, false)
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                r += android.graphics.Color.red(p)
                g += android.graphics.Color.green(p)
                bl += android.graphics.Color.blue(p)
                n++
            }
        }
        if (n == 0) return null
        return Color(
            (r / n).toInt().coerceIn(0, 255),
            (g / n).toInt().coerceIn(0, 255),
            (bl / n).toInt().coerceIn(0, 255),
        )
    }
}
