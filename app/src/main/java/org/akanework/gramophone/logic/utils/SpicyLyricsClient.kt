/*
 *     Copyright (C) 2026 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import org.akanework.gramophone.logic.utils.SemanticLyrics.SyncedLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.UnsyncedLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.Word
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max

@Parcelize
data class SpicyLyricsAttribution(
    val source: String,
    val songWriters: List<String>,
    val uploaderName: String?,
    val uploaderUrl: String?,
    val makerName: String?,
    val makerUrl: String?
) : Parcelable

data class SpicyLyricsResult(
    val lyrics: SemanticLyrics,
    val attribution: SpicyLyricsAttribution
)

class SpicyLyricsClient(private val apiKey: String) {
    companion object {
        private const val API_BASE = "https://api.spicylyrics.org/v1"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MIN_INTERLUDE_MS = 2_000L
    }

    @Throws(IOException::class, JSONException::class)
    fun lookup(
        isrc: String?,
        title: String?,
        artist: String?,
        durationMs: Long?
    ): String? {
        if (!isrc.isNullOrBlank()) {
            return get("$API_BASE/lyrics/${encodePathSegment(isrc.trim())}")
        }
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return null

        val query = buildString {
            append("title=").append(encodeQueryValue(title.trim()))
            append("&artist=").append(encodeQueryValue(artist.trim()))
            durationMs?.takeIf { it > 0 }?.let {
                append("&duration=").append(it / 1000.0)
            }
        }
        val searchJson = JSONObject(get("$API_BASE/tracks/search?$query") ?: return null)
        val searchBody = searchJson.optJSONObject("Body") ?: searchJson
        val results = searchBody.optJSONArray("tracks")
            ?: searchBody.optJSONArray("results")
            ?: searchJson.optJSONArray("tracks")
            ?: searchJson.optJSONArray("results")
            ?: return null
        val exactMatch = (0 until results.length())
            .asSequence()
            .mapNotNull { results.optJSONObject(it) }
            .firstOrNull { it.optString("tier").equals("exact", ignoreCase = true) }
            ?: return null
        val id = exactMatch.optString("id").takeIf { it.isNotBlank() } ?: return null
        return get("$API_BASE/lyrics/${encodePathSegment(id)}")
    }

    @Throws(JSONException::class)
    fun parse(responseJson: String): SpicyLyricsResult? {
        val root = JSONObject(responseJson)
        val body = root.optJSONObject("Body") ?: root.optJSONObject("body") ?: return null
        val type = body.optString("Type", body.optString("type", ""))
            .lowercase()
        val content = body.optJSONArray("Content") ?: body.optJSONArray("content")
        val lyrics = when (type) {
            "syllable" -> content?.let { parseSyllableContent(body, it) }
            "line" -> content?.let { parseLineContent(body, it) }
            "static" -> content?.let(::parseStaticContent) ?: body
                .optString("Content", body.optString("content", ""))
                .takeIf(String::isNotBlank)
                ?.let { text -> UnsyncedLyrics(text.lines().map { it to null }) }
            else -> null
        } ?: return null

        val sources = listOf(root, body).map { it.optStringAny("source", "Source") }
        val source = sources.firstOrNull {
            it.equals("spicy_lyrics", ignoreCase = true) || it.equals("spicylyrics", ignoreCase = true)
        } ?: sources.firstOrNull(String::isNotBlank) ?: "unknown"
        val rootAttribution = root.optJSONObjectAny("UploadAttribution", "uploadAttribution")
        val bodyAttribution = body.optJSONObjectAny("UploadAttribution", "uploadAttribution")
        val uploader = bodyAttribution?.optJSONObjectAny("Uploader", "uploader")
            ?: rootAttribution?.optJSONObjectAny("Uploader", "uploader")
        val maker = bodyAttribution?.optJSONObjectAny("Maker", "maker")
            ?: rootAttribution?.optJSONObjectAny("Maker", "maker")
        val writers = body.optJSONArray("SongWriters")
            ?: body.optJSONArray("songWriters")
            ?: JSONArray()
        return SpicyLyricsResult(
            lyrics,
            SpicyLyricsAttribution(
                source = source,
                songWriters = (0 until writers.length())
                    .mapNotNull { writers.optString(it, "").takeIf(String::isNotBlank) },
                uploaderName = uploader?.optStringAny("username", "Username", "name", "Name")?.takeIf(String::isNotBlank),
                uploaderUrl = uploader?.profileUrl(),
                makerName = maker?.optStringAny("username", "Username", "name", "Name")?.takeIf(String::isNotBlank),
                makerUrl = maker?.profileUrl()
            )
        )
    }

    private fun get(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status !in 200..299) {
                throw IOException("Spicy Lyrics returned HTTP $status")
            }
            val stream = connection.inputStream
            val bytes = stream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IOException("Spicy Lyrics response exceeded the size limit")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return String(bytes, StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSyllableContent(body: JSONObject, content: JSONArray): SemanticLyrics? {
        val sections = (0 until content.length()).mapNotNull(content::optJSONObject)
        val hasDuet = sections.any(::hasOppositeAlignment)
        val lines = mutableListOf<LyricLine>()
        sections.forEach { section ->
            val sectionOpposite = section.optBooleanAny("OppositeAligned", "oppositeAligned") ||
                section.optStringAny("VoiceRole", "voiceRole", "Role", "role").isOppositeVoice()
            val lead = section.optJSONObjectAny("Lead", "lead")
            val leadOpposite = sectionOpposite || (lead?.hasOppositeAlignmentFlag() == true)
            lead?.optJSONArrayAny("Syllables", "syllables")?.let { syllables ->
                lines += parseSyllableTrack(
                    syllables,
                    sectionSpeaker(section, lead, leadOpposite, background = false, hasDuet)
                )
            }

            val background = section.optArrayOrObject("Background", "background")
            background.forEach { track ->
                val syllables = track.optJSONArrayAny("Syllables", "syllables")
                    ?: track.optJSONArrayAny("Content", "content")
                    ?: return@forEach
                val opposite = leadOpposite || track.hasOppositeAlignmentFlag()
                lines += parseSyllableTrack(
                    syllables,
                    sectionSpeaker(section, track, opposite, background = true, hasDuet)
                )
            }
        }
        if (lines.isEmpty()) return null
        val ordered = lines.sortedWith(compareBy<LyricLine> { it.start }.thenBy { it.speaker?.isBackground == true })
        return SyncedLyrics(addInterludeLines(ordered, body))
    }

    private fun parseSyllableTrack(
        items: JSONArray,
        speaker: SpeakerEntity
    ): List<LyricLine> {
        val syllables = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val text = item.optStringAny("Text", "text")
            if (text.isBlank()) return@mapNotNull null
            val start = item.timeMs("StartTime", "startTime")
            val end = max(start, item.timeMs("EndTime", "endTime"))
            val background = item.optBooleanAny(
                "IsBackground", "isBackground", "BGLine", "bgLine", "Background", "background"
            ) ||
                item.optStringAny("VoiceRole", "voiceRole", "Role", "role").isBackgroundVoice()
            Syllable(
                text = text,
                startMs = start,
                endMs = end,
                isPartOfWord = item.optBooleanAny("IsPartOfWord", "isPartOfWord"),
                speaker = speaker.withFlags(item.hasOppositeAlignmentFlag(), background)
            )
        }
        if (syllables.isEmpty()) return emptyList()

        val words = mutableListOf<TimedWord>()
        var currentText = StringBuilder()
        var currentStart = 0L
        var currentEnd = 0L
        var previousContinuesWord = false
        var currentSpeaker = syllables.first().speaker
        fun flushWord() {
            if (currentText.isNotEmpty()) {
                words += TimedWord(currentText.toString(), currentStart, currentEnd, currentSpeaker)
                currentText = StringBuilder()
            }
        }
        syllables.forEach { syllable ->
            if (currentText.isEmpty() || !previousContinuesWord || currentSpeaker != syllable.speaker) {
                flushWord()
                currentStart = syllable.startMs
                currentSpeaker = syllable.speaker
            }
            currentText.append(syllable.text)
            currentEnd = syllable.endMs
            previousContinuesWord = syllable.isPartOfWord
        }
        flushWord()

        val lines = mutableListOf<LyricLine>()
        var lineWords = mutableListOf<TimedWord>()
        fun flushLine() {
            if (lineWords.isEmpty()) return
            val text = lineWords.joinToString(" ") { it.text }
            var charOffset = 0
            val timedWords = lineWords.map { word ->
                val start = charOffset
                charOffset += word.text.length
                val range = start..(charOffset - 1)
                charOffset++
                Word(word.startMs.toULong()..word.endMs.toULong(), range, isRtl = false)
            }.toMutableList()
            lines += LyricLine(
                text = text,
                start = lineWords.first().startMs.toULong(),
                end = lineWords.last().endMs.toULong(),
                endIsImplicit = false,
                words = timedWords,
                speaker = lineWords.first().speaker,
                isTranslated = false
            )
            lineWords = mutableListOf()
        }

        words.forEach { word ->
            val previous = lineWords.lastOrNull()
            if (previous != null && (
                    previous.speaker != word.speaker ||
                        word.startMs - previous.endMs > 1_000 ||
                        previous.text.lastOrNull() in setOf('.', '?', '!', ',', ';', ':') ||
                        lineWords.sumOf { it.text.length } + word.text.length > 64
                    )
            ) {
                flushLine()
            }
            lineWords += word
        }
        flushLine()
        return lines
    }

    private fun parseLineContent(body: JSONObject, content: JSONArray): SemanticLyrics? {
        val items = (0 until content.length()).mapNotNull(content::optJSONObject)
        val hasDuet = items.any(::hasOppositeAlignment)
        val lines = items.flatMap { item ->
            val parentOpposite = item.hasOppositeAlignmentFlag()
            fun parseLine(track: JSONObject, background: Boolean): LyricLine? {
                val text = track.optStringAny("Text", "text").trim()
                if (text.isEmpty()) return null
                val start = track.timeMs("StartTime", "startTime")
                val end = max(start, track.timeMs("EndTime", "endTime"))
                return LyricLine(
                    text = text,
                    start = start.toULong(),
                    end = end.toULong(),
                    endIsImplicit = false,
                    words = null,
                    speaker = sectionSpeaker(
                        item, track, parentOpposite || track.hasOppositeAlignmentFlag(),
                        background, hasDuet
                    ),
                    isTranslated = false
                )
            }
            listOfNotNull(parseLine(item, false)) +
                item.optArrayOrObject("Background", "background").mapNotNull { parseLine(it, true) }
        }
        if (lines.isEmpty()) return null
        return SyncedLyrics(addInterludeLines(lines.sortedBy { it.start }, body))
    }

    private fun parseStaticContent(content: JSONArray): SemanticLyrics? {
        val text = buildString {
            for (index in 0 until content.length()) {
                when (val item = content.opt(index)) {
                    is JSONObject -> {
                        val line = item.optString("Text", item.optString("text", ""))
                        if (line.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(line)
                        }
                    }
                    is String -> if (item.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(item)
                    }
                }
            }
        }.ifBlank { return null }
        return UnsyncedLyrics(text.lines().map { it to null })
    }

    private fun addInterludeLines(lines: List<LyricLine>, body: JSONObject): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val result = mutableListOf<LyricLine>()
        val firstStart = lines.first().start
        if (firstStart >= MIN_INTERLUDE_MS.toULong()) {
            result += interludeLine(0uL, firstStart - 1uL, lines.first().speaker)
        }
        var coveredUntil = lines.first().end
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                val gapStart = coveredUntil + 1uL
                if (line.start > coveredUntil &&
                    line.start - coveredUntil >= MIN_INTERLUDE_MS.toULong()
                ) {
                    result += interludeLine(gapStart, line.start - 1uL, line.speaker)
                }
            }
            result += line
            coveredUntil = maxOf(coveredUntil, line.end)
        }
        val bodyEnd = body.timeMs("EndTime", "endTime").toULong()
        val lastEnd = lines.maxOf { it.end }
        if (bodyEnd > lastEnd &&
            bodyEnd - lastEnd >= MIN_INTERLUDE_MS.toULong()
        ) {
            result += interludeLine(lastEnd + 1uL, bodyEnd, null)
        }
        return result.sortedBy { it.start }
    }

    private fun interludeLine(start: ULong, end: ULong, nextSpeaker: SpeakerEntity?): LyricLine {
        val text = "• • •"
        val duration = end - start
        val words = (0..2).map { index ->
            val wordStart = start + duration * index.toULong() / 3u
            val wordEnd = if (index == 2) end else
                start + duration * (index + 1).toULong() / 3u - 1u
            Word(wordStart..wordEnd, (index * 2)..(index * 2), isRtl = false)
        }.toMutableList()
        return LyricLine(
            text = text,
            start = start,
            end = end,
            endIsImplicit = false,
            words = words,
            speaker = if (nextSpeaker?.isVoice2 == true) SpeakerEntity.Voice2 else SpeakerEntity.Voice,
            isTranslated = false
        )
    }

    private fun sectionSpeaker(
        section: JSONObject,
        track: JSONObject,
        opposite: Boolean,
        background: Boolean,
        hasDuet: Boolean
    ): SpeakerEntity {
        val role = track.optStringAny("VoiceRole", "voiceRole", "Role", "role")
            .ifBlank {
                track.optStringAny("Type", "type").ifBlank {
                    section.optStringAny("VoiceRole", "voiceRole", "Role", "role", "Type", "type")
                }
            }
        val isGroup = role.contains("group", ignoreCase = true) ||
            role.contains("chorus", ignoreCase = true)
        val isBackground = background || role.isBackgroundVoice() ||
            track.optBooleanAny("IsBackground", "isBackground", "BGLine", "bgLine", "Background") ||
            section.optBooleanAny("IsBackground", "isBackground", "BGLine", "bgLine")
        return when {
            opposite && isBackground -> SpeakerEntity.Voice2Background
            opposite -> SpeakerEntity.Voice2
            isGroup && isBackground -> SpeakerEntity.GroupBackground
            isGroup -> SpeakerEntity.Group
            hasDuet && isBackground -> SpeakerEntity.Voice1Background
            hasDuet -> SpeakerEntity.Voice1
            isBackground -> SpeakerEntity.VoiceBackground
            else -> SpeakerEntity.Voice
        }
    }

    private fun hasOppositeAlignment(section: JSONObject): Boolean {
        if (section.hasOppositeAlignmentFlag()) return true
        val lead = section.optJSONObjectAny("Lead", "lead")
        val tracks = listOfNotNull(lead) + section.optArrayOrObject("Background", "background")
        return tracks.any { track ->
            track.hasOppositeAlignmentFlag() ||
                track.optJSONArrayAny("Syllables", "syllables", "Content", "content")?.let { items ->
                    (0 until items.length()).any { items.optJSONObject(it)?.hasOppositeAlignmentFlag() == true }
                } == true
        }
    }

    private fun JSONObject.hasOppositeAlignmentFlag(): Boolean =
        optBooleanAny("OppositeAligned", "oppositeAligned") ||
            optStringAny("VoiceRole", "voiceRole", "Role", "role").isOppositeVoice()

    private fun SpeakerEntity.withFlags(opposite: Boolean, background: Boolean): SpeakerEntity = when {
        opposite || isVoice2 -> if (background || isBackground) SpeakerEntity.Voice2Background else SpeakerEntity.Voice2
        background && isGroup -> SpeakerEntity.GroupBackground
        background && isWidthLimited -> SpeakerEntity.Voice1Background
        background -> SpeakerEntity.VoiceBackground
        else -> this
    }

    private fun JSONObject.profileUrl(): String? =
        optStringAny("url", "Url", "URL").takeIf(String::isNotBlank)
            ?: optStringAny("id", "Id", "ID").takeIf(String::isNotBlank)?.let {
                "https://spicylyrics.org/${it.trimStart('/')}"
            }

    private fun String.isOppositeVoice(): Boolean =
        contains("opposite", ignoreCase = true) ||
            contains("second", ignoreCase = true) ||
            contains("voice2", ignoreCase = true) ||
            equals("v2", ignoreCase = true)

    private fun String.isBackgroundVoice(): Boolean =
        contains("background", ignoreCase = true) ||
            contains("backing", ignoreCase = true) ||
            equals("bg", ignoreCase = true)

    private fun JSONObject.optJSONObjectAny(vararg keys: String): JSONObject? =
        keys.firstNotNullOfOrNull { key -> optJSONObject(key) }

    private fun JSONObject.optJSONArrayAny(vararg keys: String): JSONArray? =
        keys.firstNotNullOfOrNull { key -> optJSONArray(key) }

    private fun JSONObject.optArrayOrObject(vararg keys: String): List<JSONObject> {
        for (key in keys) {
            optJSONArray(key)?.let { array ->
                return (0 until array.length()).mapNotNull(array::optJSONObject)
            }
            optJSONObject(key)?.let { return listOf(it) }
        }
        return emptyList()
    }

    private fun JSONObject.optBooleanAny(vararg keys: String): Boolean =
        keys.any { key ->
            when (val value = opt(key)) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.equals("true", ignoreCase = true) || value == "1"
                else -> false
            }
        }

    private fun JSONObject.optStringAny(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
        }.orEmpty()

    private fun JSONObject.timeMs(vararg keys: String): Long {
        val seconds = keys.firstNotNullOfOrNull { key ->
            if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN)
                .takeIf(Double::isFinite)
        } ?: return 0L
        return secondsToMillis(seconds)
    }

    private fun secondsToMillis(seconds: Double): Long =
        if (seconds.isFinite() && seconds > 0) (seconds * 1_000).toLong() else 0L

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private data class Syllable(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val isPartOfWord: Boolean,
        val speaker: SpeakerEntity
    )

    private data class TimedWord(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val speaker: SpeakerEntity
    )

}
