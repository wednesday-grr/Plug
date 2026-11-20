package org.akanework.gramophone.ui.components

import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import org.akanework.gramophone.logic.utils.SemanticLyrics
import java.util.IdentityHashMap

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

internal class LyricBackgroundGroups(lines: List<SemanticLyrics.LyricLine>) {
    private val ranges = IdentityHashMap<SemanticLyrics.LyricLine, ULongRange>()

    init {
        val primary = lines.filter(::isPrimaryLyric)
        val groups = mutableMapOf<Pair<Boolean, Int>, MutableList<SemanticLyrics.LyricLine>>()
        val starts = mutableMapOf<Pair<Boolean, Int>, ULong>()
        lines.filter { it.speaker?.isBackground == true }.forEach { line ->
            val explicitId = line.backgroundGroupId
            val explicitStart = line.backgroundGroupStart
            val owner = if (explicitId == null || explicitStart == null) {
                primary.indices.filter { overlaps(primary[it], line) }
                    .maxByOrNull { index ->
                        if (primary[index].start <= line.start) primary[index].start else 0uL
                    }
            } else null
            val key = when {
                explicitId != null && explicitStart != null -> true to explicitId
                owner != null -> false to owner
                else -> null
            }
            if (key == null) {
                ranges[line] = line.timeRange
            } else {
                groups.getOrPut(key) { mutableListOf() }.add(line)
                starts[key] = explicitStart ?: primary[owner!!].start
            }
        }
        groups.forEach { (key, members) ->
            val range = starts.getValue(key)..members.maxOf { it.end }
            members.forEach { ranges[it] = range }
        }
    }

    fun visible(line: SemanticLyrics.LyricLine, position: ULong): Boolean =
        line.speaker?.isBackground != true || position in (ranges[line] ?: line.timeRange)
}

internal fun lyricBackgroundVisible(
    line: SemanticLyrics.LyricLine, lines: List<SemanticLyrics.LyricLine>, position: ULong
): Boolean = LyricBackgroundGroups(lines).visible(line, position)

private fun isPrimaryLyric(line: SemanticLyrics.LyricLine): Boolean =
    line.speaker?.isBackground != true && !line.isTranslated &&
        line.text.any { !it.isWhitespace() && it != '•' && it != '·' }

internal fun primaryLyricScrollIndex(
    lines: List<SemanticLyrics.LyricLine?>, position: ULong
): Int? {
    var started: Int? = null
    var upcoming: Int? = null
    lines.forEachIndexed { index, line ->
        if (line == null || !isPrimaryLyric(line)) return@forEachIndexed
        if (line.start <= position) {
            if (started == null || line.start > lines[started!!]!!.start) started = index
        } else if (upcoming == null || line.start < lines[upcoming!!]!!.start) {
            upcoming = index
        }
    }
    return started ?: upcoming
}

private fun overlaps(first: SemanticLyrics.LyricLine, second: SemanticLyrics.LyricLine): Boolean =
    first.start <= second.end && second.start <= first.end

internal const val BACKGROUND_ROW_ANIMATION_MS = 400L

internal data class LongLyricWord(val begin: ULong, val end: ULong, val charRange: IntRange)

internal fun prepareLongLyricWords(words: List<SemanticLyrics.Word>?): List<LongLyricWord?> =
    words.orEmpty().map { word ->
        val end = word.endInclusive
        if (end != null && end > word.begin && end - word.begin > 1000uL)
            LongLyricWord(word.begin, end, word.charRange)
        else null
    }

internal fun activeWordScale(word: LongLyricWord?, position: ULong, animate: Boolean): Float {
    word ?: return 1f
    if (position < word.begin || position > word.end) return 1f
    if (!animate) return 1.12f
    val elapsed = (position - word.begin).coerceAtMost(200uL).toFloat() / 200f
    val remaining = (word.end - position).coerceAtMost(200uL).toFloat() / 200f
    val progress = minOf(elapsed, remaining)
    return 1f + 0.12f * progress * progress * (3f - 2f * progress)
}
