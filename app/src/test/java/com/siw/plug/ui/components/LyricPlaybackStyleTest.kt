package com.siw.plug.ui.components

import android.app.Application
import android.app.Activity
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.siw.plug.logic.utils.SemanticLyrics
import com.siw.plug.logic.utils.SpeakerEntity
import com.siw.plug.logic.utils.LyricsResponseCache
import com.siw.plug.logic.ui.MyRecyclerView
import com.siw.plug.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricPlaybackStyleTest {
    @Test
    fun customColorsAreOptionalAndValidated() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
        prefs.edit().clear().commit()
        assertNull(customLyricColor(prefs, true))
        prefs.edit().putBoolean("lyric_custom_active_enabled", true)
            .putString("lyric_custom_active_color", "#123AbC").commit()
        assertEquals(Color.rgb(0x12, 0x3a, 0xbc), customLyricColor(prefs, true))
        prefs.edit().putString("lyric_custom_active_color", "bad").commit()
        assertNull(customLyricColor(prefs, true))
    }

    @Test
    fun lyricSpringRespondsToSettingsAndCompletesAtDuration() {
        val default = LyricSpringSpec()
        val stiff = default.copy(stiffness = 300f)
        assertEquals(0f, lyricSpringProgress(0f, default), 0.001f)
        assertEquals(1f, lyricSpringProgress(1f, default), 0.001f)
        assertNotEquals(
            lyricSpringProgress(0.5f, default),
            lyricSpringProgress(0.5f, stiff),
            0.001f
        )
    }

    @Test
    fun lyricCacheExpiresEntriesAfterThirtyDaysAndCanBeCleared() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
        prefs.edit().clear().commit()
        val cache = LyricsResponseCache(prefs)
        cache.put("lrclib:test", "lyrics", 1_000L)
        assertEquals("lyrics", cache.get("lrclib:test", 1_000L + 29L * 24 * 60 * 60 * 1000))
        assertNull(cache.get("lrclib:test", 1_000L + 30L * 24 * 60 * 60 * 1000))
        cache.put("spicy:test", "lyrics", 1_000L)
        cache.clear()
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun wordsInLongLinesArePreparedAndGrowAtTheirOwnStart() {
        val words = listOf(
            SemanticLyrics.Word(0uL..1000uL, 0..2, false),
            SemanticLyrics.Word(1000uL..4000uL, 4..7, false),
            SemanticLyrics.Word(4000uL..5001uL, 9..12, false),
            SemanticLyrics.Word(5001uL, null, 14..16, false)
        )
        val prepared = prepareLongLyricWords(words, "abc defg hijk lmn", 0uL..5001uL)
        assertEquals(4, prepared.size)
        val word = prepared[1]
        assertEquals(1f, activeWordScale(word, 999uL, true), 0.001f)
        assertEquals(1f, activeWordScale(word, 1000uL, true), 0.001f)
        val firstHalf = activeWordScale(word, 1100uL, true)
        assertTrue(firstHalf > 1f && firstHalf < 1.12f)
        assertEquals(1.12f, activeWordScale(word, 1200uL, true), 0.001f)
        assertEquals(firstHalf, activeWordScale(word, 3900uL, true), 0.001f)
        assertEquals(1f, activeWordScale(word, 4000uL, true), 0.001f)
        assertEquals(1f, activeWordScale(word, 4001uL, true), 0.001f)
        assertEquals(firstHalf, activeWordScale(prepared[2], 4100uL, true), 0.001f)
        assertEquals(1.12f, activeWordScale(word, 1000uL, false), 0.001f)
        assertEquals(1f, activeWordScale(null, 2200uL, true), 0.001f)
        val slower = activeWordScale(word, 1200uL, true, 400)
        assertTrue(slower > 1f && slower < 1.12f)
        assertTrue(prepareLongLyricWords(words, "abc defg hijk lmn", 0uL..1000uL).isEmpty())
    }

    @Test
    fun newRendererPreparesWordGeometryForLongLines() {
        val context = RuntimeEnvironment.getApplication()
        val timed = line("one two three", 1000uL, 5000uL, null).copy(words = mutableListOf(
            SemanticLyrics.Word(1000uL..2500uL, 0..2, false),
            SemanticLyrics.Word(2500uL..3000uL, 4..6, false),
            SemanticLyrics.Word(3000uL..5000uL, 8..12, false)
        ))
        val item = NewLyricsView(context, null)
            .buildSpForMeasure(SemanticLyrics.SyncedLyrics(listOf(timed)), 800).second.single()
        assertEquals(3, item.longWords.size)
        assertEquals(0..2, item.longWords[0]?.word?.charRange)
        assertEquals(4..6, item.longWords[1]?.word?.charRange)
        assertEquals(8..12, item.longWords[2]?.word?.charRange)
        assertEquals(3, item.longWords[0]!!.characters.size)
        assertEquals(3, item.longWords[1]!!.characters.size)
        assertTrue(item.longWords[0]!!.characters[0].bounds.width() > 0f)
        assertTrue(item.longWords[1]!!.characters[0].bounds.width() > 0f)
        assertTrue(item.longWords[2]!!.characters[0].bounds.width() > 0f)
    }

    @Test
    fun tappingScrolledLyricSeeksTheRenderedLine() {
        val context = RuntimeEnvironment.getApplication()
        val lyrics = SemanticLyrics.SyncedLyrics(
            (0..11).map { index ->
                val start = index.toULong() * 1_000uL
                line("lyric line $index", start, start + 900uL, null)
            }
        )
        val view = NewLyricsView(context, null)
        var soughtPosition = -1L
        view.instance = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = 0uL
            override fun isPlaying() = false
            override fun speed() = 1f
            override fun seekTo(positionMs: Long) {
                soughtPosition = positionMs
            }
            override fun destroy() {}
        }
        view.updateLyrics(lyrics)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 400, 300)
        view.scrollTo(0, 300)
        assertTrue(view.scrollY > 0)

        val (metrics, items) = view.buildSpForMeasure(lyrics, 400)
        val targetIndex = 5
        val target = items[targetIndex]
        var rowTop = metrics[2].toFloat()
        for (index in 0 until targetIndex) {
            val item = items[index]
            rowTop += item.paddingTop + item.layout.height + item.paddingBottom
        }
        rowTop += target.paddingTop
        val xField = NewLyricsView::class.java.getDeclaredField("globalPaddingHorizontal")
            .apply { isAccessible = true }
        val globalHorizontal = xField.getFloat(view)
        val x = globalHorizontal + target.layout.getLineLeft(0) + target.layout.getLineWidth(0) / 2f
        val event = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_UP, x, rowTop + target.layout.height / 2f, 0
        )
        try {
            view.onSingleTapConfirmed(event)
        } finally {
            event.recycle()
        }
        assertEquals(target.line!!.start.toLong(), soughtPosition)
    }

    @Test
    fun lineWithoutWordTimingsSplitsItsDurationAcrossWords() {
        val words = prepareLongLyricWords(null, "one two three", 1000uL..4000uL)
        val thaiWords = prepareLongLyricWords(null, "ฉันรักเพลง", 1000uL..4000uL)

        assertEquals(3, words.size)
        assertTrue(thaiWords.size > 1)
        assertEquals(1000uL, words[0].begin)
        assertEquals(1999uL, words[0].end)
        assertEquals(2000uL, words[1].begin)
        assertEquals(2999uL, words[1].end)
        assertEquals(3000uL, words[2].begin)
        assertEquals(4000uL, words[2].end)
        assertEquals(1f, activeWordScale(words[1], 1999uL, true), 0.001f)
        assertTrue(activeWordScale(words[1], 2100uL, true) > 1f)
    }

    @Test
    fun longWordExpandsOneCharacterAtATime() {
        val word = LongLyricWord(1000uL, 1799uL, 0..7)
        val characters = prepareLongLyricCharacters(word, "Tomorrow")

        assertEquals(8, characters.size)
        assertEquals(0..0, characters[0].charRange)
        assertEquals(700uL, characters[7].timing.begin - word.begin)
        assertTrue(activeWordScale(characters[0].timing, 1050uL, true) > 1f)
        assertEquals(1f, activeWordScale(characters[1].timing, 1050uL, true), 0.001f)
        assertTrue(activeWordScale(characters[1].timing, 1150uL, true) > 1f)
    }

    @Test
    fun automixSplitShowsBothLyricsAndReturnsToWinner() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val oldAnimation = prefs.getBoolean("lyric_no_animation", false)
        val oldRenderer = prefs.getBoolean("lyric_ui_v2", true)
        prefs.edit().putBoolean("lyric_no_animation", true).putBoolean("lyric_ui_v2", true).commit()
        try {
            val view = LyricsView(activity, null)
            val original = SemanticLyrics.SyncedLyrics(listOf(line("old", 0uL, 5000uL, null)))
            val incoming = SemanticLyrics.SyncedLyrics(listOf(line("new", 0uL, 5000uL, null)))
            view.updateLyrics(original)
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 800)
            val main = view.findViewById<NewLyricsView>(com.siw.plug.R.id.lyric_view)
            val event = Bundle().apply {
                putBoolean("fading", true)
                putString("incoming_id", "next")
                putLong("position", 1200)
                putLong("outgoing_position", 4000)
                putLong("sample_time", SystemClock.elapsedRealtime())
                putBoolean("lyrics_updated", true)
                putParcelable("lyrics", incoming)
            }

            view.updateAutomixLyrics(event)
            val incomingContainer = view.getChildAt(view.childCount - 1) as FrameLayout
            val second = incomingContainer.findViewById<NewLyricsView>(com.siw.plug.R.id.lyric_view)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, main.layoutParams.height)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, incomingContainer.layoutParams.height)
            assertEquals(android.graphics.Rect(0, 0, 800, 400), main.clipBounds)
            assertEquals(android.graphics.Rect(0, 400, 800, 800), incomingContainer.clipBounds)
            assertEquals(4000uL, main.instance.getCurrentPosition())
            assertEquals(1200uL, second.instance.getCurrentPosition())
            view.updateAutomixLyrics(Bundle().apply {
                putBoolean("fading", false)
                putBoolean("handoff", true)
            })
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, main.layoutParams.height)
            val lyricsField = NewLyricsView::class.java.getDeclaredField("lyrics").apply { isAccessible = true }
            assertEquals(incoming, lyricsField.get(main))
            assertNull(
                LyricsView::class.java.getDeclaredField("incomingContainer")
                    .apply { isAccessible = true }.get(view)
            )
        } finally {
            prefs.edit().putBoolean("lyric_no_animation", oldAnimation)
                .putBoolean("lyric_ui_v2", oldRenderer).commit()
        }
    }

    @Test
    fun automixSplitAnimatesWithoutJumpAndRestoresOnCancel() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val oldAnimation = prefs.getBoolean("lyric_no_animation", false)
        val oldRenderer = prefs.getBoolean("lyric_ui_v2", true)
        prefs.edit().putBoolean("lyric_no_animation", false).putBoolean("lyric_ui_v2", true).commit()
        try {
            val view = LyricsView(activity, null)
            val original = SemanticLyrics.SyncedLyrics(listOf(line("old", 0uL, 5000uL, null)))
            view.updateLyrics(original)
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 800)
            val main = view.findViewById<NewLyricsView>(com.siw.plug.R.id.lyric_view)
            val animatorField = LyricsView::class.java.getDeclaredField("splitAnimator").apply { isAccessible = true }
            val event = Bundle().apply {
                putBoolean("fading", true)
                putString("incoming_id", "next")
                putLong("sample_time", SystemClock.elapsedRealtime())
            }
            view.updateAutomixLyrics(event)
            (animatorField.get(view) as ValueAnimator).end()
            val incoming = view.getChildAt(view.childCount - 1) as FrameLayout
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, main.layoutParams.height)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, incoming.layoutParams.height)
            assertEquals(android.graphics.Rect(0, 0, 800, 400), main.clipBounds)
            assertEquals(android.graphics.Rect(0, 400, 800, 800), incoming.clipBounds)
            val incomingContent = incoming.findViewById<View>(com.siw.plug.R.id.lyric_view)
            assertEquals(400f, incomingContent.translationY)
            view.updateAutomixLyrics(Bundle().apply { putBoolean("handoff", true) })
            assertEquals(0f, main.translationY)
            assertEquals(400f, incomingContent.translationY)
            (animatorField.get(view) as ValueAnimator).currentPlayTime = 200
            assertTrue(main.translationY < 0f)
            assertTrue(incomingContent.translationY in 1f..399f)
            view.updateAutomixLyrics(event)
            (animatorField.get(view) as ValueAnimator).end()
            view.updateAutomixLyrics(Bundle().apply { putBoolean("handoff", false) })
            (animatorField.get(view) as ValueAnimator).end()
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, main.layoutParams.height)
            val lyricsField = NewLyricsView::class.java.getDeclaredField("lyrics").apply { isAccessible = true }
            assertEquals(original, lyricsField.get(main))
        } finally {
            prefs.edit().putBoolean("lyric_no_animation", oldAnimation)
                .putBoolean("lyric_ui_v2", oldRenderer).commit()
        }
    }

    @Test
    fun automixSplitSupportsLegacyRendererAndCancellation() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(MaterialR.style.Theme_MaterialComponents)
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val oldAnimation = prefs.getBoolean("lyric_no_animation", false)
        val oldRenderer = prefs.getBoolean("lyric_ui_v2", true)
        prefs.edit().putBoolean("lyric_no_animation", true).putBoolean("lyric_ui_v2", false).commit()
        try {
            val view = LyricsView(activity, null)
            val original = SemanticLyrics.SyncedLyrics(listOf(line("old", 0uL, 5000uL, null)))
            val incoming = SemanticLyrics.SyncedLyrics(listOf(line("new", 0uL, 5000uL, null)))
            view.updateLyrics(original)
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 800)
            view.updateAutomixLyrics(Bundle().apply {
                putBoolean("fading", true)
                putString("incoming_id", "next")
                putLong("position", 1200)
                putLong("outgoing_position", 4000)
                putLong("sample_time", SystemClock.elapsedRealtime())
                putBoolean("lyrics_updated", true)
                putParcelable("lyrics", incoming)
            })
            val main = view.findViewById<MyRecyclerView>(com.siw.plug.R.id.recycler_view)
            val container = view.getChildAt(view.childCount - 1) as FrameLayout
            val secondary = container.findViewById<MyRecyclerView>(com.siw.plug.R.id.recycler_view)
            val primaryAdapter = main.adapter as LegacyLyricsAdapter
            val secondaryAdapter = secondary.adapter as LegacyLyricsAdapter
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, main.layoutParams.height)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, container.layoutParams.height)
            assertEquals(android.graphics.Rect(0, 0, 800, 400), main.clipBounds)
            assertEquals(android.graphics.Rect(0, 400, 800, 800), container.clipBounds)
            assertEquals(400f, secondary.translationY)
            assertEquals(4000uL, primaryAdapter.callback!!.getCurrentPosition())
            assertEquals(1200uL, secondaryAdapter.callback!!.getCurrentPosition())
            val holder = secondaryAdapter.onCreateViewHolder(container, 0)
            secondaryAdapter.onBindViewHolder(holder, 0, mutableListOf())
            assertEquals("new", holder.lyricTextView.text.toString())
            view.updateAutomixLyrics(Bundle().apply { putBoolean("handoff", false) })
            val primaryHolder = primaryAdapter.onCreateViewHolder(FrameLayout(activity), 0)
            primaryAdapter.onBindViewHolder(primaryHolder, 0, mutableListOf())
            assertEquals("old", primaryHolder.lyricTextView.text.toString())
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, main.layoutParams.height)
        } finally {
            prefs.edit().putBoolean("lyric_no_animation", oldAnimation)
                .putBoolean("lyric_ui_v2", oldRenderer).commit()
        }
    }

    @Test
    fun legacyBackgroundRowsAnimateTogetherAndSnapWhenPaused() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(MaterialR.style.Theme_MaterialComponents)
        val container = FrameLayout(activity)
        activity.setContentView(container)
        var position = 900uL
        var playing = true
        val adapter = LegacyLyricsAdapter(activity)
        adapter.callback = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = position
            override fun isPlaying() = playing
            override fun speed() = 1f
            override fun destroy() {}
        }
        adapter.updateLyricStatus()
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(
            line("lead", 1000uL, 4000uL, null),
            line("one", 1200uL, 2000uL, SpeakerEntity.VoiceBackground),
            line("two", 2500uL, 3500uL, SpeakerEntity.VoiceBackground)
        ))
        adapter.updateLyrics(lyrics)
        val holders = listOf(adapter.onCreateViewHolder(container, 0), adapter.onCreateViewHolder(container, 0))
        holders.forEachIndexed { index, holder ->
            container.addView(holder.itemView)
            adapter.onBindViewHolder(holder, index + 1, mutableListOf())
        }
        container.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        container.layout(0, 0, 800, 800)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                if (positionStart in 1..2) adapter.onBindViewHolder(
                    holders[positionStart - 1], positionStart, mutableListOf(payload!!)
                )
            }
        })
        adapter.updateLyricPositionFromPlaybackPos()
        position = 1000uL
        adapter.updateLyricPositionFromPlaybackPos()
        assertTrue(holders.all { it.backgroundAnimator != null })
        holders.forEach {
            assertEquals(400L, it.backgroundAnimator!!.duration)
            it.backgroundAnimator!!.currentPlayTime = 200L
        }
        assertTrue(
            holders.all { it.lyricCard.alpha > 0f && it.lyricCard.alpha < 1f })
        holders.forEach { it.backgroundAnimator!!.end() }
        assertTrue(holders.all { it.lyricCard.visibility == View.VISIBLE && it.backgroundAnimator == null })
        playing = false
        position = 3501uL
        adapter.updateLyricPositionFromPlaybackPos()
        assertTrue(holders.all { it.lyricCard.visibility == View.GONE && it.backgroundAnimator == null })
        activity.finish()
    }

    @Test
    fun overlappingBackgroundLinesStartAndEndTogether() {
        val primary = line("lead", 1000uL, 4000uL, null)
        val bg1 = line("one", 1200uL, 2700uL, SpeakerEntity.VoiceBackground)
        val bg2 = line("two", 2500uL, 3000uL, SpeakerEntity.Voice2Background)
        val nextPrimary = line("next lead", 4500uL, 7000uL, null)
        val later = line("later", 5000uL, 6000uL, SpeakerEntity.VoiceBackground)
        val lines = listOf(primary, bg1, bg2, nextPrimary, later)
        assertFalse(lyricBackgroundVisible(bg1, lines, 999uL))
        assertTrue(lyricBackgroundVisible(bg1, lines, 1000uL))
        assertTrue(lyricBackgroundVisible(bg2, lines, 1000uL))
        assertTrue(lyricBackgroundVisible(bg1, lines, 2000uL))
        assertTrue(lyricBackgroundVisible(bg2, lines, 2000uL))
        assertTrue(lyricBackgroundVisible(bg1, lines, 2600uL))
        assertTrue(lyricBackgroundVisible(bg2, lines, 2600uL))
        assertTrue(lyricBackgroundVisible(bg1, lines, 2800uL))
        assertTrue(lyricBackgroundVisible(bg2, lines, 2800uL))
        assertTrue(lyricBackgroundVisible(bg1, lines, 3000uL))
        assertFalse(lyricBackgroundVisible(bg1, lines, 3001uL))
        assertFalse(lyricBackgroundVisible(bg2, lines, 3001uL))
        assertFalse(lyricBackgroundVisible(later, lines, 2000uL))
        assertTrue(lyricBackgroundVisible(later, lines, 4500uL))
        assertFalse(lyricBackgroundVisible(bg1, lines, 4001uL))
        assertTrue(lyricBackgroundVisible(primary, lines, 0uL))
        val instrumental = line("• •", 1000uL, 4000uL, null)
        assertFalse(lyricBackgroundVisible(bg1, listOf(instrumental, bg1), 2800uL))
        assertTrue(lyricBackgroundVisible(bg1, listOf(instrumental, bg1), 1200uL))
    }

    @Test
    fun backgroundContinuesAfterPrimaryAndEndsAtItsOwnTime() {
        val primary = line("lead", 1000uL, 2000uL, null)
        val backing = line("back", 1500uL, 4000uL, SpeakerEntity.VoiceBackground)
        val next = line("next", 2500uL, 3000uL, null)
        val future = line("future", 5000uL, 6000uL, SpeakerEntity.VoiceBackground)
        val lines = listOf(primary, backing, next, future)
        assertFalse(lyricBackgroundVisible(backing, lines, 999uL))
        assertTrue(lyricBackgroundVisible(backing, lines, 1000uL))
        assertTrue(lyricBackgroundVisible(backing, lines, 2001uL))
        assertTrue(lyricBackgroundVisible(backing, lines, 3500uL))
        assertTrue(lyricBackgroundVisible(backing, lines, 4000uL))
        assertFalse(lyricBackgroundVisible(backing, lines, 4001uL))
        assertTrue(lyricBackgroundVisible(future, lines, 2500uL))
        assertFalse(lyricBackgroundVisible(future, lines, 2499uL))
        assertTrue(lyricBackgroundVisible(backing, lines, 1500uL))
    }

    @Test
    fun backgroundLinesWithoutOverlapFollowTheirLatestPrimary() {
        val first = line("first", 1000uL, 2000uL, null)
        val firstBg = line("first backing", 1500uL, 2400uL, SpeakerEntity.VoiceBackground)
        val second = line("second", 2500uL, 4000uL, null)
        val secondBg = line("second backing", 3000uL, 3500uL, SpeakerEntity.VoiceBackground)
        val translation = line("translation", 2500uL, 4000uL, null).copy(isTranslated = true)
        val lines = listOf(first, firstBg, second, translation, secondBg)
        assertTrue(lyricBackgroundVisible(firstBg, lines, 2000uL))
        assertFalse(lyricBackgroundVisible(firstBg, lines, 2401uL))
        assertTrue(lyricBackgroundVisible(secondBg, lines, 2500uL))
        assertFalse(lyricBackgroundVisible(secondBg, lines, 2499uL))
        assertTrue(lyricBackgroundVisible(secondBg, lines, 3500uL))
        assertFalse(lyricBackgroundVisible(secondBg, lines, 3501uL))
        assertTrue(lyricBackgroundVisible(firstBg, lines, 1500uL))
    }

    @Test
    fun backgroundLinesWithoutPrimaryGroupOnlyWhenTheirTimesOverlap() {
        val first = line("first backing", 1000uL, 2200uL, SpeakerEntity.VoiceBackground)
        val overlapping = line("overlapping backing", 2000uL, 4000uL, SpeakerEntity.Voice2Background)
        val separate = line("separate backing", 5000uL, 6000uL, SpeakerEntity.VoiceBackground)
        val lines = listOf(first, overlapping, separate)

        assertFalse(lyricBackgroundVisible(first, lines, 999uL))
        assertTrue(lyricBackgroundVisible(first, lines, 1000uL))
        assertTrue(lyricBackgroundVisible(overlapping, lines, 1000uL))
        assertTrue(lyricBackgroundVisible(first, lines, 3999uL))
        assertTrue(lyricBackgroundVisible(overlapping, lines, 3999uL))
        assertFalse(lyricBackgroundVisible(first, lines, 4001uL))
        assertFalse(lyricBackgroundVisible(separate, lines, 4000uL))
        assertTrue(lyricBackgroundVisible(separate, lines, 5000uL))
    }

    @Test
    fun backgroundGroupIdGroupsDuetLinesWithoutPrimaryLyrics() {
        val first = line("first backing", 1000uL, 2200uL, SpeakerEntity.VoiceBackground)
            .copy(backgroundGroupId = 7, backgroundGroupStart = 800uL)
        val second = line("second backing", 3000uL, 4000uL, SpeakerEntity.Voice2Background)
            .copy(backgroundGroupId = 7, backgroundGroupStart = 800uL)
        val separate = line("separate backing", 1500uL, 3500uL, SpeakerEntity.VoiceBackground)
            .copy(backgroundGroupId = 8, backgroundGroupStart = 1500uL)
        val lines = listOf(first, second, separate)

        assertTrue(lyricBackgroundVisible(first, lines, 800uL))
        assertTrue(lyricBackgroundVisible(second, lines, 800uL))
        assertFalse(lyricBackgroundVisible(separate, lines, 800uL))
        assertTrue(lyricBackgroundVisible(first, lines, 3999uL))
        assertTrue(lyricBackgroundVisible(second, lines, 3999uL))
        assertFalse(lyricBackgroundVisible(second, lines, 4001uL))
        assertTrue(lyricBackgroundVisible(separate, lines, 1500uL))
    }

    @Test
    fun sourceGroupKeepsLateBackingWithItsOriginalLead() {
        val first = line("first", 1000uL, 2000uL, null)
        val early = line("early", 1500uL, 2500uL, SpeakerEntity.VoiceBackground)
            .copy(backgroundGroupId = 0, backgroundGroupStart = 1000uL)
        val second = line("second", 2000uL, 4000uL, null)
        val late = line("late", 3000uL, 5000uL, SpeakerEntity.VoiceBackground)
            .copy(backgroundGroupId = 0, backgroundGroupStart = 1000uL)
        val other = line("other", 2100uL, 3500uL, SpeakerEntity.VoiceBackground)
            .copy(backgroundGroupId = 1, backgroundGroupStart = 2000uL)
        val lines = listOf(first, early, second, other, late)
        assertTrue(lyricBackgroundVisible(late, lines, 1000uL))
        assertTrue(lyricBackgroundVisible(early, lines, 4000uL))
        assertFalse(lyricBackgroundVisible(other, lines, 1000uL))
        assertFalse(lyricBackgroundVisible(other, lines, 4000uL))
        assertTrue(lyricBackgroundVisible(other, lines, 2000uL))
        assertFalse(lyricBackgroundVisible(early, lines, 5001uL))
        assertFalse(lyricBackgroundVisible(other, lines, 5001uL))
    }

    @Test
    fun autoScrollFocusesLatestStartedPrimaryOnly() {
        val lead = line("lead", 1000uL, 2000uL, null)
        val background = line("back", 1200uL, 5000uL, SpeakerEntity.VoiceBackground)
        val translation = line("translation", 2000uL, 4000uL, null).copy(isTranslated = true)
        val instrumental = line("• •", 2200uL, 2900uL, null)
        val next = line("next", 3000uL, 4000uL, null)
        val lines = listOf(background, lead, translation, instrumental, next, null)
        assertEquals(1, primaryLyricScrollIndex(lines, 0uL))
        assertEquals(1, primaryLyricScrollIndex(lines, 2500uL))
        assertEquals(1, primaryLyricScrollIndex(lines, 2999uL))
        assertEquals(4, primaryLyricScrollIndex(lines, 3000uL))
        assertEquals(1, primaryLyricScrollIndex(lines, 1500uL))
        assertEquals(4, primaryLyricScrollIndex(lines, 5500uL))
        assertNull(primaryLyricScrollIndex(listOf(background, translation, instrumental, null), 2500uL))
    }

    @Test
    fun legacyAutoScrollDoesNotFocusBackingOrAdvanceDuringGap() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(MaterialR.style.Theme_MaterialComponents)
        var position = 1000uL
        val adapter = LegacyLyricsAdapter(context)
        adapter.callback = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = position
            override fun isPlaying() = false
            override fun speed() = 1f
            override fun destroy() {}
        }
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(
            line("lead", 1000uL, 2000uL, null),
            line("back", 1200uL, 5000uL, SpeakerEntity.VoiceBackground),
            line("translation", 2000uL, 4000uL, null).copy(isTranslated = true),
            line("• •", 2200uL, 2900uL, null),
            line("next", 3000uL, 4000uL, null)
        ))
        adapter.updateLyrics(lyrics)
        position = 2500uL
        adapter.updateLyricPositionFromPlaybackPos()
        assertEquals(0, adapter.currentFocusPos)
        position = 3000uL
        adapter.updateLyricPositionFromPlaybackPos()
        assertEquals(4, adapter.currentFocusPos)
        position = 1500uL
        adapter.updateLyricPositionFromPlaybackPos()
        assertEquals(0, adapter.currentFocusPos)
    }

    @Test
    fun newRendererScrollsToPrimaryEvenWhenBackingContinues() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = prefs.getBoolean("lyric_no_animation", false)
        prefs.edit().putBoolean("lyric_no_animation", true).commit()
        try {
            var position = 1000uL
            val view = NewLyricsView(context, null)
            view.instance = object : NewLyricsView.Callbacks {
                override fun getCurrentPosition() = position
                override fun isPlaying() = false
                override fun speed() = 1f
                override fun destroy() {}
            }
            val lyrics = SemanticLyrics.SyncedLyrics(listOf(
                line("lead", 1000uL, 2000uL, null),
                line("back", 1200uL, 5000uL, SpeakerEntity.VoiceBackground),
                line("translation", 2000uL, 4000uL, null).copy(isTranslated = true),
                line("• •", 2200uL, 2900uL, null),
                line("next", 3000uL, 4000uL, null)
            ))
            view.updateLyrics(lyrics)
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 800)
            val bitmap = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            assertEquals(0, view.scrollY)
            position = 2500uL
            view.draw(canvas)
            assertEquals(0, view.scrollY)
            position = 3000uL
            view.draw(canvas)
            val items = view.buildSpForMeasure(lyrics, 800).second
            val expected = items.take(4).sumOf { it.paddingTop + it.layout.height + it.paddingBottom }
            assertTrue(kotlin.math.abs(expected - view.scrollY) <= 1)
            position = 1500uL
            view.draw(canvas)
            assertEquals(0, view.scrollY)
            bitmap.recycle()
        } finally {
            prefs.edit().putBoolean("lyric_no_animation", previous).commit()
        }
    }

    @Test
    fun legacyRendererUsesCustomColorAndHidesInactiveBackground() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(MaterialR.style.Theme_MaterialComponents)
        var position = 0uL
        val adapter = LegacyLyricsAdapter(context)
        adapter.callback = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = position
            override fun isPlaying() = false
            override fun speed() = 1f
            override fun destroy() {}
        }
        val primary = line("lead", 1000uL, 4000uL, null).copy(
            words = mutableListOf(SemanticLyrics.Word(1000uL..4000uL, 0..3, false))
        )
        val background = line("back", 1200uL, 4500uL, SpeakerEntity.VoiceBackground)
        adapter.updateLyrics(SemanticLyrics.SyncedLyrics(listOf(primary, background)))
        adapter.updateTextColor(Color.GRAY, Color.RED, true)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(android.view.View.GONE, holder.lyricCard.visibility)
        position = 2200uL
        adapter.updateLyricPositionFromPlaybackPos()
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(android.view.View.VISIBLE, holder.lyricCard.visibility)
        assertEquals(0.75f, holder.lyricTextView.alpha, 0.001f)
        adapter.onBindViewHolder(holder, 0, mutableListOf())
        assertEquals(Color.RED, holder.lyricTextView.currentTextColor)
        assertEquals(1, (holder.lyricTextView.text as Spanned)
            .getSpans(0, 4, RelativeSizeSpan::class.java).size)
        position = 3500uL
        adapter.updateLyricPositionFromPlaybackPos()
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(android.view.View.VISIBLE, holder.lyricCard.visibility)
        position = 4001uL
        adapter.updateLyricPositionFromPlaybackPos()
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(android.view.View.VISIBLE, holder.lyricCard.visibility)
        position = 4501uL
        adapter.updateLyricPositionFromPlaybackPos()
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(android.view.View.GONE, holder.lyricCard.visibility)
    }

    @Test
    fun legacyBackgroundGroupStaysVisibleWhileEachLineHighlightsAtItsOwnTime() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(MaterialR.style.Theme_MaterialComponents)
        var position = 1000uL
        val adapter = LegacyLyricsAdapter(context)
        adapter.callback = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = position
            override fun isPlaying() = false
            override fun speed() = 1f
            override fun destroy() {}
        }
        val primary = line("lead", 1000uL, 4000uL, null)
        val first = line("one", 1200uL, 2000uL, SpeakerEntity.VoiceBackground)
        val second = line("two", 2500uL, 3500uL, SpeakerEntity.VoiceBackground)
        adapter.updateLyrics(SemanticLyrics.SyncedLyrics(listOf(primary, first, second)))
        adapter.updateTextColor(Color.GRAY, Color.RED, true)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.updateLyricPositionFromPlaybackPos()
        adapter.onBindViewHolder(holder, 2, mutableListOf())
        assertEquals(android.view.View.VISIBLE, holder.lyricCard.visibility)
        assertEquals(Color.GRAY, holder.lyricTextView.currentTextColor)
        position = 2600uL
        adapter.updateLyricPositionFromPlaybackPos()
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(android.view.View.VISIBLE, holder.lyricCard.visibility)
        assertEquals(Color.GRAY, holder.lyricTextView.currentTextColor)
        adapter.onBindViewHolder(holder, 2, mutableListOf())
        assertEquals(Color.RED, holder.lyricTextView.currentTextColor)
        position = 3501uL
        adapter.updateLyricPositionFromPlaybackPos()
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(android.view.View.GONE, holder.lyricCard.visibility)
    }

    @Test
    fun backgroundRowsExpandAndCollapseTogetherAndSeekImmediately() {
        val context = RuntimeEnvironment.getApplication()
        val primary = line("lead", 1000uL, 5000uL, null)
        val bg1 = line("one", 1200uL, 3000uL, SpeakerEntity.VoiceBackground)
        val bg2 = line("two", 2000uL, 4000uL, SpeakerEntity.VoiceBackground)
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(primary, bg1, bg2))
        val view = NewLyricsView(context, null)
        view.instance = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = 0uL
            override fun isPlaying() = true
            override fun speed() = 1f
            override fun destroy() {}
        }
        view.updateLyrics(lyrics)
        val items = view.buildSpForMeasure(lyrics, 800).second
        fun fraction(i: Int) = view.backgroundRowFraction(i, items[i])
        view.updateBackgroundRows(items, 900uL, 1000L, true)
        val hiddenHeight = view.visibleContentHeight(items, 0, 0)
        assertEquals(0f, fraction(1), 0.001f)
        assertEquals(0f, fraction(2), 0.001f)
        view.updateBackgroundRows(items, 1000uL, 1100L, true)
        view.updateBackgroundRows(items, 1200uL, 1300L, true)
        assertTrue(fraction(1) > 0f && fraction(1) < 1f)
        assertEquals(fraction(1), fraction(2), 0.001f)
        view.updateBackgroundRows(items, 1400uL, 1500L, true)
        assertEquals(1f, fraction(1), 0.001f)
        assertEquals(1f, fraction(2), 0.001f)
        val bothHeight = view.visibleContentHeight(items, 0, 0)
        assertTrue(bothHeight > hiddenHeight)
        view.updateBackgroundRows(items, 3001uL, 2901L, true)
        view.updateBackgroundRows(items, 3251uL, 3151L, true)
        assertEquals(1f, fraction(1), 0.001f)
        assertEquals(1f, fraction(2), 0.001f)
        assertEquals(bothHeight, view.visibleContentHeight(items, 0, 0))
        view.updateBackgroundRows(items, 4001uL, 3901L, true)
        view.updateBackgroundRows(items, 4201uL, 4101L, true)
        assertTrue(fraction(1) > 0f && fraction(1) < 1f)
        assertEquals(fraction(1), fraction(2), 0.001f)
        view.updateBackgroundRows(items, 4401uL, 4301L, true)
        assertEquals(0f, fraction(1), 0.001f)
        assertEquals(0f, fraction(2), 0.001f)
        assertEquals(hiddenHeight, view.visibleContentHeight(items, 0, 0))
        view.updateBackgroundRows(items, 1500uL, 4350L, true)
        assertEquals(1f, fraction(1), 0.001f)
        assertEquals(1f, fraction(2), 0.001f)
        view.updateBackgroundRows(items, 4500uL, 4400L, false)
        assertEquals(0f, fraction(1), 0.001f)
        assertEquals(0f, fraction(2), 0.001f)
        assertEquals(hiddenHeight, view.visibleContentHeight(items, 0, 0))
    }

    @Test
    fun backgroundRowsRemainExpandedThroughGapsUntilLastLineEnds() {
        val context = RuntimeEnvironment.getApplication()
        val primary = line("lead", 1000uL, 2000uL, null)
        val firstBackground = line("back one", 1500uL, 2500uL, SpeakerEntity.VoiceBackground)
        val lastBackground = line("back two", 3500uL, 5000uL, SpeakerEntity.Voice2Background)
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(primary, firstBackground, lastBackground))
        val view = NewLyricsView(context, null)
        view.updateLyrics(lyrics)
        val items = view.buildSpForMeasure(lyrics, 800).second
        fun firstFraction() = view.backgroundRowFraction(1, items[1])
        fun lastFraction() = view.backgroundRowFraction(2, items[2])

        view.updateBackgroundRows(items, 900uL, 900L, true)
        assertEquals(0f, firstFraction(), 0.001f)
        assertEquals(0f, lastFraction(), 0.001f)
        view.updateBackgroundRows(items, 1000uL, 1000L, true)
        view.updateBackgroundRows(items, 1200uL, 1200L, true)
        assertTrue(firstFraction() > 0f && firstFraction() < 1f)
        assertEquals(firstFraction(), lastFraction(), 0.001f)
        view.updateBackgroundRows(items, 2001uL, 2001L, true)
        view.updateBackgroundRows(items, 3200uL, 3200L, true)
        assertEquals(1f, view.backgroundRowFraction(1, items[1]), 0.001f)
        assertEquals(1f, view.backgroundRowFraction(2, items[2]), 0.001f)
        assertTrue(lyricBackgroundVisible(lastBackground, lyrics.text, 4500uL))
        assertFalse(lyricBackgroundVisible(lastBackground, lyrics.text, 5001uL))
        view.updateBackgroundRows(items, 5001uL, 5001L, true)
        view.updateBackgroundRows(items, 5201uL, 5201L, true)
        assertTrue(firstFraction() > 0f && firstFraction() < 1f)
        assertEquals(firstFraction(), lastFraction(), 0.001f)
        view.updateBackgroundRows(items, 5401uL, 5401L, true)
        assertEquals(0f, firstFraction(), 0.001f)
        assertEquals(0f, lastFraction(), 0.001f)
    }

    @Test
    fun overlappingBackgroundRowsExpandTogetherAndRemainUntilLastOneEnds() {
        val context = RuntimeEnvironment.getApplication()
        val first = line("first", 1000uL, 2000uL, null)
        val firstBg = line("first backing", 1500uL, 5000uL, SpeakerEntity.VoiceBackground)
        val second = line("second", 2500uL, 4000uL, null)
        val secondBg = line("second backing", 3000uL, 3500uL, SpeakerEntity.Voice2Background)
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(first, firstBg, second, secondBg))
        val view = NewLyricsView(context, null)
        view.instance = object : NewLyricsView.Callbacks {
            override fun getCurrentPosition() = 0uL
            override fun isPlaying() = true
            override fun speed() = 1f
            override fun destroy() {}
        }
        view.updateLyrics(lyrics)
        val items = view.buildSpForMeasure(lyrics, 800).second
        fun assertFractionsMatch() = assertEquals(
            view.backgroundRowFraction(1, items[1]),
            view.backgroundRowFraction(3, items[3]),
            0.001f
        )

        view.updateBackgroundRows(items, 900uL, 1000L, true)
        view.updateBackgroundRows(items, 1000uL, 1100L, true)
        view.updateBackgroundRows(items, 1200uL, 1300L, true)
        assertFractionsMatch()
        assertTrue(view.backgroundRowFraction(1, items[1]) > 0f)
        assertTrue(view.backgroundRowFraction(1, items[1]) < 1f)

        view.updateBackgroundRows(items, 4000uL, 4100L, true)
        assertEquals(1f, view.backgroundRowFraction(1, items[1]), 0.001f)
        assertFractionsMatch()
        view.updateBackgroundRows(items, 5001uL, 5101L, true)
        view.updateBackgroundRows(items, 5201uL, 5301L, true)
        assertFractionsMatch()
        assertTrue(view.backgroundRowFraction(1, items[1]) < 1f)
        assertTrue(view.backgroundRowFraction(1, items[1]) > 0f)
        view.updateBackgroundRows(items, 5501uL, 5601L, true)
        assertEquals(0f, view.backgroundRowFraction(1, items[1]), 0.001f)
        assertFractionsMatch()
    }

    private fun line(text: String, start: ULong, end: ULong, speaker: SpeakerEntity?) =
        SemanticLyrics.LyricLine(text, start, end, false, null, speaker, false)
}
