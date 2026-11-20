package org.akanework.gramophone.ui.components

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.util.Log
import org.akanework.gramophone.logic.utils.SemanticLyrics
import java.text.BreakIterator
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
        data class Group(
            val members: MutableList<SemanticLyrics.LyricLine>,
            var start: ULong,
            var end: ULong,
            val explicitGroupId: Int?
        )

        val primary = lines.filter(::isPrimaryLyric)
        val groups = mutableMapOf<Pair<Boolean, Int>, Group>()
        val ungrouped = mutableListOf<Group>()
        lines.filter { it.speaker?.isBackground == true }.forEach { line ->
            val explicitId = line.backgroundGroupId
            val explicitStart = line.backgroundGroupStart
            val owner = if (explicitId == null) {
                val overlapping = primary.indices.filter { overlaps(primary[it], line) }
                overlapping
                    .maxByOrNull { index ->
                        if (primary[index].start <= line.start) primary[index].start else 0uL
                    }
                    ?: primary.indices.filter { primary[it].start <= line.start }
                        .maxByOrNull { primary[it].start }
            } else null
            val key = when {
                explicitId != null -> true to explicitId
                owner != null -> false to owner
                else -> null
            }
            val start = explicitStart ?: owner?.let { primary[it].start } ?: line.start
            if (key == null) {
                ungrouped += Group(mutableListOf(line), start, line.end, null)
            } else {
                val group = groups.getOrPut(key) {
                    Group(mutableListOf(), start, line.end, explicitId)
                }
                group.members += line
                group.start = minOf(group.start, start)
                group.end = maxOf(group.end, line.end)
            }
        }

        val mergedGroups = (groups.values + ungrouped).toMutableList()
        var merged: Boolean
        do {
            merged = false
            mergeLoop@ for (i in mergedGroups.indices) {
                for (j in i + 1 until mergedGroups.size) {
                    val first = mergedGroups[i]
                    val second = mergedGroups[j]
                    if (first.explicitGroupId != second.explicitGroupId) continue
                    if (first.members.any { firstLine ->
                            second.members.any { secondLine -> overlaps(firstLine, secondLine) }
                        }
                    ) {
                        first.members += second.members
                        first.start = minOf(first.start, second.start)
                        first.end = maxOf(first.end, second.end)
                        mergedGroups.removeAt(j)
                        merged = true
                        break@mergeLoop
                    }
                }
            }
        } while (merged)

        mergedGroups.forEach { group ->
            val range = group.start..group.end
            group.members.forEach { ranges[it] = range }
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

internal const val DEFAULT_WORD_EXPANSION_DURATION_MS = 200

internal data class LongLyricWord(val begin: ULong, val end: ULong, val charRange: IntRange)
internal data class LongLyricCharacter(val timing: LongLyricWord, val charRange: IntRange)

private fun splitIntoLyricWordRanges(text: String): List<IntRange> {
    val iterator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        android.icu.text.BreakIterator.getWordInstance().apply { setText(text) }
    } else null
    return if (iterator != null) {
        collectWordRanges(text, iterator.first()) { iterator.next() }
    } else {
        val fallback = BreakIterator.getWordInstance().apply { setText(text) }
        collectWordRanges(text, fallback.first()) { fallback.next() }
    }
}

private fun splitIntoCharacterRanges(text: String, range: IntRange): List<IntRange> {
    val boundedStart = range.first.coerceAtLeast(0)
    val boundedEnd = (range.last + 1).coerceAtMost(text.length)
    if (boundedStart >= boundedEnd) return emptyList()
    val substring = text.substring(boundedStart, boundedEnd)
    val iterator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        android.icu.text.BreakIterator.getCharacterInstance().apply { setText(substring) }
    } else null
    val localRanges = if (iterator != null) {
        collectCharacterRanges(substring, iterator.first()) { iterator.next() }
    } else {
        val fallback = BreakIterator.getCharacterInstance().apply { setText(substring) }
        collectCharacterRanges(substring, fallback.first()) { fallback.next() }
    }
    return localRanges.map { (boundedStart + it.first)..(boundedStart + it.last) }
}

private fun collectCharacterRanges(text: String, first: Int, next: () -> Int): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var charStart = first
    var charEnd = next()
    while (charEnd != BreakIterator.DONE) {
        if (charStart < charEnd && text.substring(charStart, charEnd).any { !it.isWhitespace() })
            ranges += charStart until charEnd
        charStart = charEnd
        charEnd = next()
    }
    return ranges
}

internal fun prepareLongLyricCharacters(word: LongLyricWord, text: String): List<LongLyricCharacter> {
    val ranges = splitIntoCharacterRanges(text, word.charRange)
    if (ranges.isEmpty()) return emptyList()
    val duration = word.end - word.begin + 1uL
    val count = ranges.size.toULong()
    return ranges.mapIndexed { index, range ->
        val beginOffset = duration * index.toULong() / count
        val endExclusiveOffset = duration * (index + 1).toULong() / count
        val endOffset = if (endExclusiveOffset > beginOffset)
            endExclusiveOffset - 1uL else beginOffset
        LongLyricCharacter(
            LongLyricWord(word.begin + beginOffset, word.begin + endOffset, range),
            range
        )
    }
}

private fun collectWordRanges(text: String, first: Int, next: () -> Int): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var tokenStart = first
    var tokenEnd = next()
    while (tokenEnd != BreakIterator.DONE) {
        if (text.substring(tokenStart, tokenEnd).any(Char::isLetterOrDigit))
            ranges += tokenStart until tokenEnd
        tokenStart = tokenEnd
        tokenEnd = next()
    }
    return ranges
}

internal fun prepareLongLyricWords(
    words: List<SemanticLyrics.Word>?,
    text: String,
    lineRange: ULongRange
): List<LongLyricWord> {
    if (lineRange.last <= lineRange.first || lineRange.last - lineRange.first <= 1000uL)
        return emptyList()

    val sourceWords = words.orEmpty()
    val timedWords = sourceWords.mapIndexedNotNull { index, word ->
        val end = word.endInclusive ?: sourceWords.getOrNull(index + 1)?.begin?.let {
            if (it > 0uL) it - 1uL else 0uL
        } ?: lineRange.last
        val begin = maxOf(word.begin, lineRange.first)
        val boundedEnd = minOf(end, lineRange.last)
        if (begin > boundedEnd || word.charRange.first < 0 ||
            word.charRange.last < word.charRange.first || word.charRange.last >= text.length
        ) null else LongLyricWord(begin, boundedEnd, word.charRange)
    }
    if (timedWords.isNotEmpty()) return timedWords

    val ranges = splitIntoLyricWordRanges(text)
    if (ranges.isEmpty()) return emptyList()

    val duration = lineRange.last - lineRange.first + 1uL
    val count = ranges.size.toULong()
    return ranges.mapIndexed { index, range ->
        val beginOffset = duration * index.toULong() / count
        val endExclusiveOffset = duration * (index + 1).toULong() / count
        val endOffset = if (endExclusiveOffset > beginOffset)
            endExclusiveOffset - 1uL else beginOffset
        LongLyricWord(
            lineRange.first + beginOffset,
            lineRange.first + endOffset,
            range
        )
    }
}

internal fun activeWordScale(
    word: LongLyricWord?,
    position: ULong,
    animate: Boolean,
    durationMs: Int = DEFAULT_WORD_EXPANSION_DURATION_MS
): Float {
    word ?: return 1f
    if (position < word.begin || position > word.end) return 1f
    if (!animate) return 1.12f
    val duration = durationMs.coerceAtLeast(1).toULong()
    val elapsed = (position - word.begin).coerceAtMost(duration).toFloat() / duration.toFloat()
    val remaining = (word.end - position).coerceAtMost(duration).toFloat() / duration.toFloat()
    val progress = minOf(elapsed, remaining)
    return 1f + 0.12f * progress * progress * (3f - 2f * progress)
}
