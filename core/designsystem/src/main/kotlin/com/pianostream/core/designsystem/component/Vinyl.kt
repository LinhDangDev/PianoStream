package com.pianostream.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.unit.dp
import com.pianostream.core.designsystem.art.ArtworkUrls

/**
 * Shared "vinyl" palette — the champagne-on-charcoal language introduced by
 * the turntable player (after "Neumorphism Vinyl Player" by Marikutsa
 * Andriy). Browsing surfaces reuse these so the whole app reads as one
 * design: warm gold accents on a charcoal neumorphic base.
 */
object Vinyl {
    val BgTop = Color(0xFF2B2E33)
    val BgBottom = Color(0xFF17181B)
    val Face = Color(0xFF26292D)
    val Gold = Color(0xFFE2BE98)
    val GoldDim = Color(0xFF9E7E66)
    val IconTint = Color(0xFFCBA584)

    /** Record radius / turntable width. */
    const val RECORD_RATIO = 0.90f

    /** Label radius / record radius. */
    const val LABEL_RATIO = 0.36f
    const val MINI_LABEL_RATIO = 0.58f

    /** One revolution per 1.8 s — a 33⅓ rpm spin. */
    const val RPM_MS = 1_800
}

/** Groove field of a vinyl record (no label) filling [radius] at centre. */
fun DrawScope.drawVinylRecord(radius: Float, labelRadius: Float = radius * Vinyl.LABEL_RATIO) {
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF1C1C1F),
            0.55f to Color(0xFF0C0C0E),
            1f to Color(0xFF19191C),
            center = center,
            radius = radius,
        ),
        radius,
    )
    val step = 1.7.dp.toPx()
    var rr = labelRadius * 1.12f
    var i = 0
    while (rr < radius * 0.975f) {
        val a = if (i % 3 == 0) 0.055f else 0.028f
        drawCircle(Color.White.copy(alpha = a), rr, style = Stroke(0.6.dp.toPx()))
        rr += step
        i++
    }
    for (f in floatArrayOf(0.52f, 0.68f, 0.84f)) {
        drawCircle(Color.Black.copy(alpha = 0.75f), radius * f, style = Stroke(1.6.dp.toPx()))
    }
    drawCircle(Color.White.copy(alpha = 0.07f), labelRadius * 1.05f, style = Stroke(1.2.dp.toPx()))
    drawCircle(Color.White.copy(alpha = 0.10f), radius, style = Stroke(1.dp.toPx()))
}

/** A spinning-record thumbnail: grooves + circular artwork label + spindle. */
@Composable
fun VinylDisc(
    artworkUri: String?,
    title: String,
    modifier: Modifier = Modifier,
    labelRatio: Float = Vinyl.LABEL_RATIO,
    artworkContentDescription: String? = "Artwork for $title",
) {
    Box(modifier.clip(CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawVinylRecord(size.minDimension / 2f, size.minDimension / 2f * labelRatio)
        }
        val uri = ArtworkUrls.sanitize(artworkUri)
        Box(
            Modifier
                .fillMaxSize(labelRatio)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFB8262E), Color(0xFF5C0F15))))
                .drawWithContent {
                    drawContent()
                    val r = size.minDimension / 2f
                    drawCircle(
                        Brush.radialGradient(
                            0.75f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.35f),
                            center = center,
                            radius = r,
                        ),
                        r,
                    )
                    drawCircle(Color(0xFF141414), r * 0.07f)
                    drawCircle(Color(0xFFD9D9D9), r * 0.07f, style = Stroke(r * 0.025f))
                },
            contentAlignment = Alignment.Center,
        ) {
            if (uri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(uri).build(),
                    contentDescription = artworkContentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
