package com.siw.plug.ui.components

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.preference.PreferenceManager
import com.siw.plug.R
import com.siw.plug.logic.ui.MyRecyclerView
import com.siw.plug.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomixLyricsThemeTest {
    @Test
    fun themesRemainSeparateAndCustomColorsApplyToBothRenderers() {
        for (modern in listOf(true, false)) {
            val activity = Robolectric.buildActivity(MainActivity::class.java).get()
            activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents)
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            prefs.edit().clear().putBoolean("lyric_no_animation", true)
                .putBoolean("lyric_ui_v2", modern)
                .putBoolean("lyric_apple_music_effects", false).commit()
            val view = LyricsView(activity, null)
            val outgoing = LyricsTheme(Color.rgb(20, 30, 40), Color.RED, Color.GREEN, Color.CYAN)
            val incoming = LyricsTheme(Color.rgb(50, 60, 70), Color.BLUE, Color.YELLOW, Color.MAGENTA)
            view.setLyricBackgroundColor(outgoing.surface)
            view.updateTextColor(outgoing.inactive, outgoing.active, outgoing.translation)
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 800)
            view.updateAutomixLyrics(Bundle().apply {
                putBoolean("fading", true)
                putString("incoming_id", "next")
                putLong("sample_time", SystemClock.elapsedRealtime())
            })
            setField(view, "incomingTheme", incoming)
            invoke(view, "applySurfaceColor")
            invoke(view, "applyTextColors")
            val container = view.getChildAt(view.childCount - 1) as FrameLayout
            assertEquals(outgoing.surface, (view.background as ColorDrawable).color)
            assertEquals(incoming.surface, (container.background as ColorDrawable).color)
            val mainRenderer: Any
            val incomingRenderer: Any
            if (modern) {
                mainRenderer = view.findViewById<NewLyricsView>(R.id.lyric_view)
                incomingRenderer = container.findViewById<NewLyricsView>(R.id.lyric_view)
            } else {
                mainRenderer = view.findViewById<MyRecyclerView>(R.id.recycler_view).adapter!!
                incomingRenderer = container.findViewById<MyRecyclerView>(R.id.recycler_view).adapter!!
            }
            assertEquals(outgoing.inactive, getField(mainRenderer, "defaultTextColor"))
            assertEquals(incoming.inactive, getField(incomingRenderer, "defaultTextColor"))
            assertEquals(outgoing.active, getField(mainRenderer, "highlightTextColor"))
            assertEquals(incoming.active, getField(incomingRenderer, "highlightTextColor"))
            view.setLyricBackgroundColor(Color.BLACK)
            view.updateTextColor(Color.WHITE, Color.WHITE, Color.WHITE)
            assertEquals(outgoing.surface, (view.background as ColorDrawable).color)
            assertEquals(outgoing.inactive, getField(mainRenderer, "defaultTextColor"))
            prefs.edit().putBoolean("lyric_custom_active_enabled", true)
                .putString("lyric_custom_active_color", "#123456")
                .putBoolean("lyric_custom_inactive_enabled", true)
                .putString("lyric_custom_inactive_color", "#654321").commit()
            invoke(view, "applyTextColors")
            assertEquals(Color.rgb(0x65, 0x43, 0x21), getField(mainRenderer, "defaultTextColor"))
            assertEquals(Color.rgb(0x65, 0x43, 0x21), getField(incomingRenderer, "defaultTextColor"))
            assertEquals(Color.rgb(0x12, 0x34, 0x56), getField(mainRenderer, "highlightTextColor"))
            assertEquals(Color.rgb(0x12, 0x34, 0x56), getField(incomingRenderer, "highlightTextColor"))
            view.updateAutomixLyrics(Bundle().apply { putBoolean("handoff", true) })
            assertEquals(incoming.surface, (view.background as ColorDrawable).color)
            assertNotEquals(outgoing.surface, (view.background as ColorDrawable).color)
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun incomingBackgroundMovesWithSplitAndCancellationRestoresOutgoingTheme() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().clear().putBoolean("lyric_ui_v2", true)
            .putBoolean("lyric_apple_music_effects", false).commit()
        val view = LyricsView(activity, null)
        view.setLyricBackgroundColor(Color.RED)
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 800, 800)
        view.updateAutomixLyrics(Bundle().apply {
            putBoolean("fading", true)
            putString("incoming_id", "next")
            putLong("sample_time", SystemClock.elapsedRealtime())
        })
        (getField(view, "splitAnimator") as ValueAnimator).end()
        setField(view, "incomingTheme", LyricsTheme(Color.BLUE, Color.BLUE, Color.WHITE, Color.WHITE))
        invoke(view, "applySurfaceColor")
        val container = view.getChildAt(view.childCount - 1) as FrameLayout
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, container.layoutParams.height)
        assertEquals(Rect(0, 400, 800, 800), container.clipBounds)
        assertEquals(400f, container.findViewById<NewLyricsView>(R.id.lyric_view).translationY)
        val main = view.findViewById<NewLyricsView>(R.id.lyric_view)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, main.layoutParams.height)
        assertEquals(Rect(0, 0, 800, 400), main.clipBounds)
        assertEquals(0f, main.translationY)
        assertEquals(Color.BLUE, (container.background as ColorDrawable).color)

        setField(view, "splitAmount", 0.5f)
        invoke(view, "layoutSplit", false)
        assertEquals(Rect(0, 0, 800, 600), main.clipBounds)
        assertEquals(0f, main.translationY)
        assertEquals(Rect(0, 600, 800, 800), container.clipBounds)
        assertEquals(600f, container.findViewById<NewLyricsView>(R.id.lyric_view).translationY)

        setField(view, "splitAmount", 0.5f)
        setField(view, "handoffStartAmount", 1f)
        invoke(view, "layoutSplit", true)
        assertEquals(Rect(0, 0, 800, 400), main.clipBounds)
        assertEquals(-200f, main.translationY)
        assertEquals(Rect(0, 200, 800, 800), container.clipBounds)
        assertEquals(200f, container.findViewById<NewLyricsView>(R.id.lyric_view).translationY)
        view.updateAutomixLyrics(Bundle().apply { putBoolean("handoff", false) })
        (getField(view, "splitAnimator") as ValueAnimator).end()
        assertEquals(Color.RED, (view.background as ColorDrawable).color)
        prefs.edit().clear().commit()
    }

    @Test
    fun brightnessAppliesToBothArtworkLayersAndFallbackSurvivesHandoff() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().clear().putBoolean("lyric_no_animation", true)
            .putBoolean("lyric_apple_music_effects", true)
            .putBoolean("lyric_custom_brightness_enabled", true)
            .putInt("lyric_custom_brightness", 40).commit()
        val view = LyricsView(activity, null)
        view.updateAutomixLyrics(Bundle().apply {
            putBoolean("fading", true)
            putString("incoming_id", "next")
        })
        val fallback = getField(view, "fallbackIncomingTheme") as LyricsTheme
        val incoming = getField(view, "incomingArtwork") as ImageView
        val main = (getField(view, "albumArtBackgrounds") as List<*>).first() as ImageView
        for (art in listOf(main, incoming)) {
            val matrix = ColorMatrix()
            (art.colorFilter as ColorMatrixColorFilter).getColorMatrix(matrix)
            assertEquals(0.4f, matrix.array[0], 0.001f)
            assertEquals(0.4f, matrix.array[6], 0.001f)
            assertEquals(0.4f, matrix.array[12], 0.001f)
        }
        view.updateAutomixLyrics(Bundle().apply { putBoolean("handoff", true) })
        assertEquals(darkenLyricsSurface(fallback.surface, 0.4f, 1f),
            (view.background as ColorDrawable).color)
        assertEquals(fallback.active, view.highlightTextColor)
        prefs.edit().clear().commit()
    }

    @Test
    fun modernHandoffPreservesIncomingScrollPosition() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().clear().putBoolean("lyric_no_animation", true)
            .putBoolean("lyric_ui_v2", true).commit()
        val view = LyricsView(activity, null)
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 800, 800)
        view.updateAutomixLyrics(Bundle().apply {
            putBoolean("fading", true)
            putString("incoming_id", "next")
        })

        val main = view.findViewById<NewLyricsView>(R.id.lyric_view)
        val container = getField(view, "incomingContainer") as FrameLayout
        val incoming = container.findViewById<NewLyricsView>(R.id.lyric_view)
        setField(incoming, "currentScrollTarget", 7)
        setInheritedField(incoming, "mChildHeight", 2000)
        incoming.scrollTo(0, 600)
        assertEquals(600, incoming.scrollY)

        view.updateAutomixLyrics(Bundle().apply { putBoolean("handoff", true) })

        val pending = getField(main, "pendingScrollHandoff")!!
        assertEquals(600, getField(pending, "scrollY"))
        assertEquals(7, getField(pending, "targetIndex"))
        assertNull(getField(main, "currentSmoothScroll"))
        prefs.edit().clear().commit()
    }

    @Test
    fun transitionNormalizesArtworkBrightnessToTheDarkerCover() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().clear().putBoolean("lyric_no_animation", true)
            .putBoolean("lyric_apple_music_effects", false).commit()
        val view = LyricsView(activity, null)
        val backgrounds = getField(view, "albumArtBackgrounds") as List<*>
        val outgoing = backgrounds.first() as ImageView
        outgoing.setImageDrawable(BitmapDrawable(
            activity.resources, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(64, 64, 64))
            }
        ))
        view.updateAutomixLyrics(Bundle().apply {
            putBoolean("fading", true)
            putString("incoming_id", "next")
        })
        val incoming = getField(view, "incomingArtwork") as ImageView
        incoming.setImageDrawable(BitmapDrawable(
            activity.resources, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(128, 128, 128))
            }
        ))
        setField(view, "incomingArtworkLuminance", 128f / 255f)
        invoke(view, "applySurfaceColor")

        fun brightness(image: ImageView): Float {
            val matrix = ColorMatrix()
            (image.colorFilter as ColorMatrixColorFilter).getColorMatrix(matrix)
            return matrix.array[0]
        }
        assertEquals(0.7f, brightness(outgoing), 0.001f)
        assertEquals(0.35f, brightness(incoming), 0.001f)
        setField(view, "incomingArtworkUri", Uri.parse("content://test/cover"))
        view.updateAutomixLyrics(Bundle().apply { putBoolean("handoff", true) })
        assertEquals(0.35f, brightness(outgoing), 0.001f)
        prefs.edit().putBoolean("lyric_custom_brightness_enabled", true)
            .putInt("lyric_custom_brightness", 40).commit()
        invoke(view, "applySurfaceColor")
        assertEquals(0.2f, brightness(outgoing), 0.001f)
        prefs.edit().clear().commit()
    }

    private fun getField(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun setField(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun setInheritedField(instance: Any, name: String, value: Any?) {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                type.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
                return
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun invoke(instance: Any, name: String) {
        instance.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)
    }

    private fun invoke(instance: Any, name: String, arg: Boolean) {
        instance.javaClass.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(instance, arg)
    }
}
