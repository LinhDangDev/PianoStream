package com.pianostream.feature.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.drawVinylRecord
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Dark neumorphic turntable look (after "Neumorphism Vinyl Player" by
 * Marikutsa Andriy, dribbble.com/shots/12239558): charcoal surfaces,
 * champagne-gold accents, spinning record with the artwork as its label.
 */

private val GoldTube = listOf(Color(0xFF9C7556), Color(0xFFF4DEC6), Color(0xFFB98F6C))

/** Soft extruded shadow pair (dark bottom-right, faint light top-left). */
private fun DrawScope.neuShadow(radius: Float, center: Offset, depth: Float) {
    drawIntoCanvas { canvas ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Vinyl.Face.toArgb()
        p.setShadowLayer(depth * 2.4f, depth, depth, Color.Black.copy(alpha = 0.7f).toArgb())
        canvas.nativeCanvas.drawCircle(center.x, center.y, radius, p)
        p.setShadowLayer(depth * 2f, -depth * 0.6f, -depth * 0.6f, Color.White.copy(alpha = 0.06f).toArgb())
        canvas.nativeCanvas.drawCircle(center.x, center.y, radius, p)
    }
}

@Composable
internal fun NeuButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Vinyl.IconTint,
    ring: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                val r = this.size.minDimension / 2f
                neuShadow(r, center, 5.dp.toPx())
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color(0xFF31343A), Color(0xFF1C1E21)),
                        start = Offset.Zero,
                        end = Offset(this.size.width, this.size.height),
                    ),
                    r,
                )
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.35f)),
                        start = Offset.Zero,
                        end = Offset(this.size.width, this.size.height),
                    ),
                    r - 0.5.dp.toPx(),
                    style = Stroke(1.dp.toPx()),
                )
                if (ring) {
                    val inner = r * 0.80f
                    drawCircle(
                        Brush.linearGradient(
                            listOf(Color(0xFF18191C), Color(0xFF2C2F34)),
                            start = Offset.Zero,
                            end = Offset(this.size.width, this.size.height),
                        ),
                        inner,
                    )
                    drawCircle(Vinyl.Gold.copy(alpha = 0.55f), inner, style = Stroke(1.5.dp.toPx()))
                }
            }
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Platter + spinning record + tonearm. The label is drawn by the caller. */
@Composable
internal fun Turntable(
    rotation: () -> Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val armAngle by animateFloatAsState(
        targetValue = if (playing) 0f else -24f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "tonearm",
    )
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) { drawPlatter() }
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation() }) {
            drawVinylRecord(size.width / 2f * Vinyl.RECORD_RATIO)
        }
        Canvas(Modifier.fillMaxSize()) { drawSheen(size.width / 2f * Vinyl.RECORD_RATIO) }
        Canvas(Modifier.fillMaxSize()) { drawTonearm(armAngle) }
    }
}

private fun DrawScope.drawPlatter() {
    val r = size.width / 2f * 0.985f
    neuShadow(r, center, 9.dp.toPx())
    drawCircle(
        Brush.linearGradient(
            listOf(Color(0xFF2F3237), Color(0xFF1A1B1E)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        r,
    )
    drawCircle(Color(0xFF121315), r * 0.945f)
    drawCircle(Color.White.copy(alpha = 0.05f), r * 0.945f, style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawSheen(radius: Float) {
    // Two soft light wedges built from stacked low-alpha arcs (a sweep
    // gradient leaves a visible seam line on some renderers).
    val topLeft = Offset(center.x - radius, center.y - radius)
    val box = Size(radius * 2, radius * 2)
    for ((mid, peak) in listOf(-55f to 0.024f, 125f to 0.017f)) {
        for (k in 0 until 6) {
            val half = 5f + 4f * k
            drawArc(
                Color.White.copy(alpha = peak),
                startAngle = mid - half,
                sweepAngle = half * 2,
                useCenter = true,
                topLeft = topLeft,
                size = box,
            )
        }
    }
}

private fun DrawScope.drawTonearm(angle: Float) {
    val w = size.width
    val pivot = Offset(0.86f * w, 0.12f * w)
    val bend = Offset(0.86f * w, 0.46f * w)
    val ctrl = Offset(0.86f * w, 0.60f * w)
    val tip = Offset(0.70f * w, 0.68f * w)
    val tube = 0.024f * w
    val arm = Path().apply {
        moveTo(pivot.x, pivot.y)
        lineTo(bend.x, bend.y)
        quadraticTo(ctrl.x, ctrl.y, tip.x, tip.y)
    }
    rotate(angle, pivot) {
        // Cast shadow first, then the tube.
        translate(0.018f * w, 0.024f * w) {
            drawPath(arm, Color.Black.copy(alpha = 0.40f), style = Stroke(tube * 1.3f, cap = StrokeCap.Round))
        }
        drawPath(
            arm,
            Brush.horizontalGradient(GoldTube, startX = bend.x - tube, endX = bend.x + tube),
            style = Stroke(tube, cap = StrokeCap.Round),
        )
        // Headshell along the arm's end tangent.
        val dir = Offset(tip.x - ctrl.x, tip.y - ctrl.y)
        val deg = Math.toDegrees(atan2(dir.y, dir.x).toDouble()).toFloat()
        val len = hypot(dir.x, dir.y)
        if (len > 0f) {
            rotate(deg, tip) {
                val hw = 0.115f * w
                val hh = 0.062f * w
                translate(0.012f * w, 0.012f * w) {
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.35f),
                        topLeft = Offset(tip.x - 0.02f * w, tip.y - hh / 2f),
                        size = Size(hw, hh),
                        cornerRadius = CornerRadius(0.014f * w),
                    )
                }
                drawRoundRect(
                    Brush.verticalGradient(GoldTube, startY = tip.y - hh / 2f, endY = tip.y + hh / 2f),
                    topLeft = Offset(tip.x - 0.02f * w, tip.y - hh / 2f),
                    size = Size(hw, hh),
                    cornerRadius = CornerRadius(0.014f * w),
                )
                drawRoundRect(
                    Color(0xFF2A2420),
                    topLeft = Offset(tip.x + hw * 0.55f, tip.y + hh * 0.18f),
                    size = Size(hw * 0.22f, hh * 0.34f),
                    cornerRadius = CornerRadius(0.006f * w),
                )
            }
        }
    }
    // Counterweight + pivot base stay put.
    val cw = Size(0.085f * w, 0.075f * w)
    drawRoundRect(
        Brush.horizontalGradient(GoldTube, startX = pivot.x - cw.width / 2f, endX = pivot.x + cw.width / 2f),
        topLeft = Offset(pivot.x - cw.width / 2f, pivot.y - 0.115f * w),
        size = cw,
        cornerRadius = CornerRadius(0.012f * w),
    )
    val baseR = 0.07f * w
    neuShadow(baseR, pivot, 4.dp.toPx())
    drawCircle(
        Brush.linearGradient(
            listOf(Color(0xFF3A3D43), Color(0xFF1E2023)),
            start = Offset(pivot.x - baseR, pivot.y - baseR),
            end = Offset(pivot.x + baseR, pivot.y + baseR),
        ),
        baseR,
        pivot,
    )
    drawCircle(
        Brush.linearGradient(
            GoldTube,
            start = Offset(pivot.x - baseR, pivot.y - baseR),
            end = Offset(pivot.x + baseR, pivot.y + baseR),
        ),
        baseR * 0.42f,
        pivot,
    )
    drawCircle(Color(0xFF2A2420), baseR * 0.12f, pivot)
}

/** Record label: the artwork (or a red PianoStream label) with a spindle hole. */
@Composable
internal fun VinylLabel(
    artworkUri: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(listOf(Color(0xFFB8262E), Color(0xFF5C0F15))),
            )
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
                drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.98f, style = Stroke(r * 0.03f))
                drawCircle(Color(0xFF141414), r * 0.07f)
                drawCircle(Color(0xFFD9D9D9), r * 0.07f, style = Stroke(r * 0.025f))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(artworkUri).build(),
                contentDescription = "Artwork for $title",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(Vinyl.Gold.copy(alpha = 0.45f), r * 0.86f, style = Stroke(r * 0.012f))
                drawCircle(Vinyl.Gold.copy(alpha = 0.25f), r * 0.80f, style = Stroke(r * 0.008f))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    "PIANOSTREAM",
                    color = Vinyl.Gold,
                    fontSize = 7.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    title,
                    color = Color(0xFFF6E7D6),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                )
            }
        }
    }
}

/** Small spinning record for the docked mini player. */
@Composable
internal fun MiniVinyl(
    artworkUri: String?,
    title: String,
    rotation: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.graphicsLayer { rotationZ = rotation() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawVinylRecord(size.minDimension / 2f, size.minDimension / 2f * Vinyl.MINI_LABEL_RATIO) }
        VinylLabel(
            artworkUri,
            title,
            Modifier.fillMaxSize(Vinyl.MINI_LABEL_RATIO),
        )
    }
}

/** Inset groove slider with gold fill and a ringed knob; tap or drag to seek. */
@Composable
internal fun GoldSlider(
    fraction: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> onChange((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val f = fraction.coerceIn(0f, 1f)
        val knobR = 9.dp.toPx()
        val h = 6.dp.toPx()
        val left = knobR
        val width = size.width - knobR * 2
        val top = (size.height - h) / 2f
        drawRoundRect(Color(0xFF0F1012), Offset(left, top), Size(width, h), CornerRadius(h / 2))
        drawRoundRect(
            Color.White.copy(alpha = 0.06f),
            Offset(left, top + 0.5.dp.toPx()),
            Size(width, h),
            CornerRadius(h / 2),
            style = Stroke(0.8.dp.toPx()),
        )
        val fillW = width * f
        if (fillW > 0f) {
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color(0xFF9C7556), Vinyl.Gold), startX = left, endX = left + width),
                Offset(left, top + 1.dp.toPx()),
                Size(fillW, h - 2.dp.toPx()),
                CornerRadius(h / 2),
            )
        }
        val kc = Offset(left + fillW, size.height / 2f)
        neuShadow(knobR, kc, 3.dp.toPx())
        drawCircle(Color(0xFF2B2E33), knobR, kc)
        drawCircle(Vinyl.Gold, knobR * 0.78f, kc, style = Stroke(2.dp.toPx()))
        drawCircle(Color(0xFF17181B), knobR * 0.36f, kc)
    }
}
