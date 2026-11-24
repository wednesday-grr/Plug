package com.siw.plug.ui.components

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.TextPaint
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.siw.plug.logic.utils.SemanticLyrics
import com.siw.plug.logic.utils.SpeakerEntity
import com.siw.plug.logic.utils.SpicyLyricsAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LyricsAttributionTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun rendersCreditsAndOpensProfileFromApplicationContext() {
        val text = buildLyricsAttribution(
            context, SpicyLyricsAttribution(
                "SpicyLyrics", listOf("Writer"), "Uploader", "https://spicylyrics.org/u",
                "Maker", "https://spicylyrics.org/m"
            )
        )
        assertTrue(text.toString().contains("Lyrics provided by Spicy Lyrics\nSongwriters: Writer\n"))
        val spans = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals(2, spans.size)
        assertEquals("Uploader", text.subSequence(text.getSpanStart(spans[0]), text.getSpanEnd(spans[0])).toString())
        val paint = TextPaint()
        spans[0].updateDrawState(paint)
        assertEquals(Color.WHITE, paint.color)
        assertTrue(paint.isUnderlineText)
        spans[0].onClick(View(context))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://spicylyrics.org/u", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun showsNamesWithoutUsableLinks() {
        val text = buildLyricsAttribution(
            context, SpicyLyricsAttribution("unknown", emptyList(), "Uploader", null, "Maker", "javascript:bad")
        )
        assertTrue(text.toString().contains("Uploader: Uploader\nMaker: Maker"))
        assertTrue(text.getSpans(0, text.length, ClickableSpan::class.java).isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun duetBackgroundUsesSmallRightAlignedLayoutAndLegacyViewIsNotSelectable() {
        context.setTheme(com.google.android.material.R.style.Theme_MaterialComponents)
        val line = SemanticLyrics.LyricLine(
            "backing", 0uL, 1000uL, false, null, SpeakerEntity.Voice2Background, false
        )
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(line))
        val view = NewLyricsView(context, null)
        val item = view.buildSpForMeasure(lyrics, 800).second.single()
        assertEquals(Layout.Alignment.ALIGN_OPPOSITE, item.layout.alignment)
        assertTrue(item.speaker!!.isBackground)
        assertTrue(item.speaker!!.isVoice2)
        val primary = line.copy(text = "lead", speaker = SpeakerEntity.Voice2)
        val primaryItem = view.buildSpForMeasure(SemanticLyrics.SyncedLyrics(listOf(primary)), 800).second.single()
        assertTrue(item.layout.paint.textSize < primaryItem.layout.paint.textSize)
        val rtl = line.copy(text = "\u05e9\u05dc\u05d5\u05dd")
        val rtlItem = view.buildSpForMeasure(SemanticLyrics.SyncedLyrics(listOf(rtl)), 800).second.single()
        assertEquals(Layout.Alignment.ALIGN_NORMAL, rtlItem.layout.alignment)

        val adapter = LegacyLyricsAdapter(context)
        adapter.updateLyrics(lyrics)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0, mutableListOf())
        assertEquals(Gravity.END, holder.lyricTextView.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertFalse(holder.lyricTextView.isTextSelectable)
        assertTrue(holder.lyricTextView.isLongClickable)
        assertTrue(holder.lyricTextView.isClickable)
        assertFalse(holder.lyricCard.isClickable)
        assertFalse(holder.lyricCard.performClick())
        assertTrue(holder.lyricTextView.alpha < 1f)

        adapter.updateLyrics(
            lyrics, SpicyLyricsAttribution("spicy_lyrics", emptyList(), "Uploader",
                "https://spicylyrics.org/u", "Maker", "https://spicylyrics.org/m")
        )
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertFalse(holder.lyricTextView.isTextSelectable)
        assertTrue(holder.lyricTextView.movementMethod is LinkMovementMethod)
        val textView = holder.lyricTextView
        val links = (textView.text as Spanned)
            .getSpans(0, textView.length(), ClickableSpan::class.java)
        assertEquals(2, links.size)
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
        links.forEachIndexed { index, span ->
            val text = textView.text as Spanned
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val layout = textView.layout
            val lineIndex = layout.getLineForOffset(start)
            val x = textView.totalPaddingLeft +
                (layout.getPrimaryHorizontal(start) + layout.getPrimaryHorizontal(end)) / 2
            val y = textView.totalPaddingTop +
                (layout.getLineTop(lineIndex) + layout.getLineBottom(lineIndex)) / 2f
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0)
            textView.dispatchTouchEvent(down)
            textView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            val intent = shadowOf(context).nextStartedActivity
            assertNotNull(intent)
            assertEquals(if (index == 0) "https://spicylyrics.org/u" else "https://spicylyrics.org/m",
                intent!!.data.toString())
        }
        adapter.onBindViewHolder(holder, 0, mutableListOf())
        assertFalse(holder.lyricTextView.isTextSelectable)
        assertEquals(null, holder.lyricTextView.movementMethod)
        assertTrue(holder.lyricTextView.isClickable)
        assertTrue(holder.lyricTextView.isLongClickable)
        assertFalse(holder.lyricCard.isClickable)
        assertFalse(holder.lyricCard.performClick())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun customViewTapsOnlyOpenFooterLinks() {
        val view = NewLyricsView(context, null)
        view.instance = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = 0uL
            override fun isPlaying() = false
            override fun speed() = 1f
            override fun destroy() {}
        }
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(
            SemanticLyrics.LyricLine("lyric line", 0uL, 1000uL, false, null, null, false)
        ))
        view.setPadding(20, 30, 0, 0)
        view.updateLyrics(lyrics)
        val attribution = SpicyLyricsAttribution(
            "spicy_lyrics", listOf("Writer"), "Uploader", "https://spicylyrics.org/u",
            "Maker", "https://spicylyrics.org/m"
        )
        view.updateAttribution(attribution)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 800, 1200)
        view.updateAttribution(attribution)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 800, 1200)
        val measured = view.buildSpForMeasure(lyrics, 780)
        val lyric = measured.second[0]
        val footer = measured.second[1]
        val horizontalPadding = 28.5f * context.resources.displayMetrics.density
        val lyricY = view.paddingTop + measured.first[2] + lyric.paddingTop + lyric.layout.height / 2f
        val footerY = view.paddingTop + measured.first[2] + lyric.paddingTop +
            lyric.layout.height + lyric.paddingBottom + footer.paddingTop
        var clicks = 0
        view.setOnClickListener { clicks++ }
        fun tap(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0)
            view.onTouchEvent(down)
            view.onTouchEvent(up)
            down.recycle()
            up.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        }
        tap(view.paddingLeft + horizontalPadding + 5, lyricY)
        assertEquals(0, clicks)
        assertNull(shadowOf(context).nextStartedActivity)
        val spans = footer.text.getSpans(0, footer.text.length, ClickableSpan::class.java)
        spans.forEachIndexed { index, span ->
            val start = footer.text.getSpanStart(span)
            val end = footer.text.getSpanEnd(span)
            val line = footer.layout.getLineForOffset(start)
            val x = (footer.layout.getPrimaryHorizontal(start) + footer.layout.getPrimaryHorizontal(end)) / 2
            val y = footerY + (footer.layout.getLineTop(line) + footer.layout.getLineBottom(line)) / 2f
            tap(view.paddingLeft + horizontalPadding + x, y)
            val intent = shadowOf(context).nextStartedActivity
            assertNotNull("index=$index x=$x y=$y scroll=${view.scrollY} left=${footer.layout.getLineLeft(line)} right=${footer.layout.getLineRight(line)} offset=${footer.layout.getOffsetForHorizontal(line, x)} span=$start..$end height=${measured.first[2]}", intent)
            assertEquals(if (index == 0) "https://spicylyrics.org/u" else "https://spicylyrics.org/m",
                intent!!.data.toString())
        }
        view.scrollTo(0, 100)
        val firstSpan = spans.first()
        val start = footer.text.getSpanStart(firstSpan)
        val end = footer.text.getSpanEnd(firstSpan)
        val line = footer.layout.getLineForOffset(start)
        val x = (footer.layout.getPrimaryHorizontal(start) + footer.layout.getPrimaryHorizontal(end)) / 2
        val y = footerY + (footer.layout.getLineTop(line) + footer.layout.getLineBottom(line)) / 2f
        tap(view.paddingLeft + horizontalPadding + x, y - view.scrollY)
        assertEquals("https://spicylyrics.org/u", shadowOf(context).nextStartedActivity.data.toString())
        tap(799f, footerY + 1f)
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(0, clicks)
    }
}
