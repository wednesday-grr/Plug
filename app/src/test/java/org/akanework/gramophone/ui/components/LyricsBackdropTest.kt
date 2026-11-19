package org.akanework.gramophone.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsBackdropTest {
    @Test
    fun surfaceRetainsHueAndSaturationWhileReducingValue() {
        val original = Color.HSVToColor(floatArrayOf(210f, 0.8f, 0.5f))
        val hsv = FloatArray(3)
        Color.colorToHSV(darkenLyricsSurface(original), hsv)
        assertEquals(210f, hsv[0], 1f)
        assertEquals(0.8f, hsv[1], 0.02f)
        assertEquals(0.35f, hsv[2], 0.01f)

        Color.colorToHSV(darkenLyricsSurface(Color.WHITE), hsv)
        assertEquals(0.45f, hsv[2], 0.005f)
    }

    @Test
    fun whiteArtworkKeepsLyricAndFooterContrastDuringCrossfade() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            colorFilter = lyricsArtworkColorFilter()
        }
        for (step in 0..20) {
            val progress = step / 20f
            canvas.drawColor(darkenLyricsSurface(Color.WHITE))
            paint.alpha = (255 * LYRICS_ART_ALPHA * (1f - progress)).toInt()
            canvas.drawRect(0f, 0f, 1f, 1f, paint)
            paint.alpha = (255 * LYRICS_ART_ALPHA * progress).toInt()
            canvas.drawRect(0f, 0f, 1f, 1f, paint)
            canvas.drawColor(LYRICS_SCRIM_COLOR)
            val backdrop = bitmap.getPixel(0, 0)
            val footer = ColorUtils.compositeColors(LYRICS_ATTRIBUTION_COLOR, backdrop)
            assertTrue("active contrast at $progress", ColorUtils.calculateContrast(Color.WHITE, backdrop) >= 4.5)
            assertTrue("footer contrast at $progress", ColorUtils.calculateContrast(footer, backdrop) >= 4.5)
        }
    }
}
