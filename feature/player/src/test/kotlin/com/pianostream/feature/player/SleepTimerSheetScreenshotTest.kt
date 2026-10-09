package com.pianostream.feature.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.designsystem.component.Vinyl
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders [SleepTimerContent] to PNGs under build/outputs/sleep-sheet/ so
 * the charcoal+gold look can be eyeballed without wiring the sheet into
 * ExpandingPlayer (that mount is the integrator's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SleepTimerSheetScreenshotTest {

    private fun renderToPng(name: String, state: SleepTimerUi): File {
        val activity = Robolectric
            .buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
        val density = activity.resources.displayMetrics.density
        val widthPx = (400 * density).toInt()

        val composeView = ComposeView(activity).apply {
            setContent {
                // Stand-in for the sheet surface (ModalBottomSheet paints it
                // at integration time; the content itself carries no bg).
                Box(Modifier.background(Vinyl.BgTop).fillMaxWidth()) {
                    SleepTimerContent(state = state, onPick = {}, onCancel = {})
                }
            }
        }
        activity.setContentView(composeView)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val bmp = Bitmap.createBitmap(
            composeView.measuredWidth.coerceAtLeast(1),
            composeView.measuredHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        composeView.draw(Canvas(bmp))

        // Test workers run with cwd = this module's project dir.
        val out = File("build/outputs/sleep-sheet/$name.png")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    @Test
    fun inactive_stateHint() {
        val png = renderToPng(
            "sleep_sheet_inactive",
            SleepTimerUi(active = false, remainingMs = 0L, mode = SleepTimerUi.Mode.MIN30),
        )
        assertThat(png.exists()).isTrue()
        assertThat(png.length()).isGreaterThan(0L)
    }

    @Test
    fun active_countdownAndCancelButton() {
        val png = renderToPng(
            "sleep_sheet_active",
            SleepTimerUi(
                active = true,
                remainingMs = 29 * 60_000L + 58_000L,
                mode = SleepTimerUi.Mode.MIN30,
            ),
        )
        assertThat(png.exists()).isTrue()
        assertThat(png.length()).isGreaterThan(0L)
    }

    @Test
    fun active_endOfTrackSelected() {
        val png = renderToPng(
            "sleep_sheet_end_of_track",
            SleepTimerUi(
                active = true,
                remainingMs = 3 * 60_000L + 42_000L,
                mode = SleepTimerUi.Mode.END_OF_TRACK,
            ),
        )
        assertThat(png.exists()).isTrue()
        assertThat(png.length()).isGreaterThan(0L)
    }
}
