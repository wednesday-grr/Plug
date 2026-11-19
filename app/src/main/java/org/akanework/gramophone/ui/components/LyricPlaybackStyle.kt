package org.akanework.gramophone.ui.components

import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import org.akanework.gramophone.logic.utils.SemanticLyrics

internal fun customLyricColor(prefs: SharedPreferences, active: Boolean): Int? {
    val prefix = if (active) "lyric_custom_active" else "lyric_custom_inactive"
    if (!prefs.getBoolean("${prefix}_enabled", false)) return null
    val value = prefs.getString("${prefix}_color", if (active) "#FFFFFF" else "#BDBDBD")
    if (value == null || !value.matches(Regex("#[0-9a-fA-F]{6}"))) {
        Log.w("LyricPlaybackStyle", "Invalid custom lyric color in ${prefix}_color")
        return null
    }
    return Color.parseColor(value)
}

internal fun lyricBackgroundVisible(
    line: SemanticLyrics.LyricLine, lines: List<SemanticLyrics.LyricLine>, position: ULong
): Boolean {
    if (line.speaker?.isBackground != true) return true
    return lines.any {
        it.speaker?.isBackground != true && !it.isTranslated &&
            it.text.any { character -> !character.isWhitespace() && character != '•' && character != '·' } &&
            position in it.timeRange &&
            it.start <= line.end && line.start <= it.end
    }
}

internal fun activeWordScale(word: SemanticLyrics.Word?, position: ULong, animate: Boolean): Float {
    val end = word?.endInclusive ?: return 1f
    if (position < word.begin || position > end || position - word.begin < 1000uL) return 1f
    if (!animate) return 1.12f
    val elapsed = (position - word.begin - 1000uL).coerceAtMost(200uL).toFloat() / 200f
    val remaining = (end - position).coerceAtMost(200uL).toFloat() / 200f
    return 1f + 0.12f * minOf(elapsed, remaining)
}
