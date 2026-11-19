package org.akanework.gramophone.ui.components

import android.app.Application
import android.graphics.Color
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import com.google.android.material.R as MaterialR
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SpeakerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    fun wordGrowsOnlyAfterOneSecondAndResetsAtEnd() {
        val word = SemanticLyrics.Word(1000uL..4000uL, 0..3, false)
        assertEquals(1f, activeWordScale(word, 1999uL, true), 0.001f)
        assertEquals(1f, activeWordScale(word, 2000uL, true), 0.001f)
        assertEquals(1.12f, activeWordScale(word, 2200uL, true), 0.001f)
        assertEquals(1.06f, activeWordScale(word, 3900uL, true), 0.001f)
        assertEquals(1f, activeWordScale(word, 4001uL, true), 0.001f)
        assertEquals(1.12f, activeWordScale(word, 2000uL, false), 0.001f)
        assertEquals(1f, activeWordScale(null, 2200uL, true), 0.001f)
    }

    @Test
    fun overlappingBackgroundLinesFollowPrimaryTogether() {
        val primary = line("lead", 1000uL, 4000uL, null)
        val bg1 = line("one", 1200uL, 1800uL, SpeakerEntity.VoiceBackground)
        val bg2 = line("two", 2500uL, 3000uL, SpeakerEntity.Voice2Background)
        val later = line("later", 5000uL, 6000uL, SpeakerEntity.VoiceBackground)
        val lines = listOf(primary, bg1, bg2, later)
        assertFalse(lyricBackgroundVisible(bg1, lines, 999uL))
        assertTrue(lyricBackgroundVisible(bg1, lines, 2000uL))
        assertTrue(lyricBackgroundVisible(bg2, lines, 2000uL))
        assertFalse(lyricBackgroundVisible(later, lines, 2000uL))
        assertFalse(lyricBackgroundVisible(bg1, lines, 4001uL))
        assertTrue(lyricBackgroundVisible(primary, lines, 0uL))
        val instrumental = line("• •", 1000uL, 4000uL, null)
        assertFalse(lyricBackgroundVisible(bg1, listOf(instrumental, bg1), 2000uL))
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
        val background = line("back", 1200uL, 1800uL, SpeakerEntity.VoiceBackground)
        adapter.updateLyrics(SemanticLyrics.SyncedLyrics(listOf(primary, background)))
        adapter.updateTextColor(Color.GRAY, Color.RED, true)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(0f, holder.lyricTextView.alpha, 0.001f)
        position = 2200uL
        adapter.updateLyricPositionFromPlaybackPos()
        adapter.onBindViewHolder(holder, 1, mutableListOf())
        assertEquals(0.75f, holder.lyricTextView.alpha, 0.001f)
        adapter.onBindViewHolder(holder, 0, mutableListOf())
        assertEquals(Color.RED, holder.lyricTextView.currentTextColor)
        assertEquals(1, (holder.lyricTextView.text as Spanned)
            .getSpans(0, 4, RelativeSizeSpan::class.java).size)
    }

    private fun line(text: String, start: ULong, end: ULong, speaker: SpeakerEntity?) =
        SemanticLyrics.LyricLine(text, start, end, false, null, speaker, false)
}
