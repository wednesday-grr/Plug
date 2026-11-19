package org.akanework.gramophone

import android.app.Application
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SpeakerEntity
import org.akanework.gramophone.logic.utils.SpicyLyricsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SpicyLyricsClientTest {
    private val client = SpicyLyricsClient("sl_pk_test")

    @Test
    fun parsesSyllablesAndConditionalSpicyLyricsAttribution() {
        val result = client.parse(
            """
                {
                  "source": "spicy_lyrics",
                  "Body": {
                    "Type": "Syllable",
                    "SongWriters": ["Writer One"],
                    "UploadAttribution": {
                      "Uploader": {"username": "uploader", "url": "https://example.com/uploader"},
                      "Maker": {"username": "maker", "url": "https://example.com/maker"}
                    },
                    "Content": [{
                      "Type": "Vocal",
                      "Lead": {"Syllables": [
                        {"Text": "hel", "StartTime": 0.0, "EndTime": 0.3, "IsPartOfWord": true},
                        {"Text": "lo", "StartTime": 0.3, "EndTime": 0.6, "IsPartOfWord": false},
                        {"Text": "world", "StartTime": 0.7, "EndTime": 1.0, "IsPartOfWord": false}
                      ]}
                    }]
                  }
                }
            """.trimIndent()
        )

        assertNotNull(result)
        val lyrics = result!!.lyrics as SemanticLyrics.SyncedLyrics
        assertEquals(1, lyrics.text.size)
        assertEquals("hello world", lyrics.text.single().text)
        assertEquals(2, lyrics.text.single().words?.size)
        assertEquals(0uL, lyrics.text.single().words?.first()?.begin)
        assertEquals(600uL, lyrics.text.single().words?.first()?.endInclusive)
        assertEquals(0..4, lyrics.text.single().words?.first()?.charRange)
        assertEquals(6..10, lyrics.text.single().words?.last()?.charRange)
        assertEquals(listOf("Writer One"), result.attribution.songWriters)
        assertEquals("uploader", result.attribution.uploaderName)
        assertEquals("maker", result.attribution.makerName)
    }

    @Test
    fun preservesUploadCreditsForOtherSources() {
        val result = client.parse(
            """
                {
                  "source": "spotify",
                  "Body": {
                    "Type": "Line",
                    "SongWriters": [],
                    "UploadAttribution": {
                      "Uploader": {"username": "not shown", "url": "https://example.com"}
                    },
                    "Content": [
                      {"Text": "A lyric line", "StartTime": 1.25, "EndTime": 2.5}
                    ]
                  }
                }
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals("spotify", result!!.attribution.source)
        assertEquals("not shown", result.attribution.uploaderName)
        assertEquals("https://example.com", result.attribution.uploaderUrl)
        val line = (result.lyrics as SemanticLyrics.SyncedLyrics).text.single()
        assertEquals("A lyric line", line.text)
        assertEquals(1_250uL, line.start)
        assertEquals(2_500uL, line.end)
    }

    @Test
    fun parsesStaticContentAsUnsyncedLyrics() {
        val result = client.parse(
            """
                {
                  "Body": {
                    "Type": "Static",
                    "Content": [
                      {"Text": "First line"},
                      {"Text": "Second line"}
                    ]
                  }
                }
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals(
            listOf("First line", "Second line"),
            (result!!.lyrics as SemanticLyrics.UnsyncedLyrics).unsyncedText.map { it.first }
        )

        val plainTextResult = client.parse(
            """{"Body":{"Type":"Static","Content":"A static lyric"}}"""
        )
        assertEquals(
            listOf("A static lyric"),
            (plainTextResult!!.lyrics as SemanticLyrics.UnsyncedLyrics).unsyncedText.map { it.first }
        )
    }

    @Test
    fun mapsDuetAndBackgroundSyllableTracksAndAddsTimedInterludes() {
        val result = client.parse(
            """
                {
                  "Body": {
                    "Type": "Syllable",
                    "StartTime": 3.0,
                    "EndTime": 9.1,
                    "Content": [
                      {
                        "Type": "Vocal",
                        "Lead": {"Syllables": [
                          {"Text": "lead", "StartTime": 3.0, "EndTime": 3.5}
                        ]},
                        "Background": [
                          {"Syllables": [
                            {"Text": "back", "StartTime": 3.6, "EndTime": 4.0}
                          ]}
                        ]
                      },
                      {
                        "Type": "Vocal",
                        "OppositeAligned": true,
                        "Lead": {"Syllables": [
                          {"Text": "duet", "StartTime": 6.5, "EndTime": 7.0}
                        ]}
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        val lines = (result!!.lyrics as SemanticLyrics.SyncedLyrics).text
        assertEquals(SpeakerEntity.Voice, lines.first().speaker)
        assertEquals("• • •", lines.first().text)
        assertEquals(3, lines.first().words?.size)
        assertEquals(SpeakerEntity.Voice1, lines[1].speaker)
        assertEquals(SpeakerEntity.Voice1Background, lines[2].speaker)
        assertEquals(SpeakerEntity.Voice2, lines[3].speaker)
        assertEquals(SpeakerEntity.Voice2, lines[4].speaker)
        assertEquals(SpeakerEntity.Voice, lines.last().speaker)
        assertEquals("• • •", lines.last().text)
    }

    @Test
    fun mapsLineBackgroundAndOppositeAlignmentMetadata() {
        val result = client.parse(
            """
                {
                  "Body": {
                    "Type": "Line",
                    "EndTime": 8.0,
                    "Content": [
                      {"Text": "Lead line", "StartTime": 2.0, "EndTime": 3.0},
                      {
                        "Text": "Backing line",
                        "StartTime": 3.2,
                        "EndTime": 4.0,
                        "BGLine": true,
                        "OppositeAligned": true
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        val lines = (result!!.lyrics as SemanticLyrics.SyncedLyrics).text
        assertEquals("• • •", lines.first().text)
        assertEquals(SpeakerEntity.Voice1, lines[1].speaker)
        assertEquals(SpeakerEntity.Voice2Background, lines[2].speaker)
        assertEquals("• • •", lines.last().text)
    }

    @Test
    fun parsesAttributionFromBothLevelsWithNameAndIdFallbacks() {
        val result = client.parse(
            """
                {
                  "source": "other",
                  "UploadAttribution": {"Uploader": {"name": "Upload Name", "id": "/user/upload"}},
                  "Body": {
                    "source": "SpicyLyrics",
                    "UploadAttribution": {"Maker": {"name": "Maker Name", "id": "user/maker"}},
                    "Type": "Static",
                    "Content": "A lyric"
                  }
                }
            """.trimIndent()
        )!!
        assertEquals("SpicyLyrics", result.attribution.source)
        assertEquals("Upload Name", result.attribution.uploaderName)
        assertEquals("https://spicylyrics.org/user/upload", result.attribution.uploaderUrl)
        assertEquals("Maker Name", result.attribution.makerName)
        assertEquals("https://spicylyrics.org/user/maker", result.attribution.makerUrl)
    }

    @Test
    fun inheritsDuetAlignmentForBackgroundTracksAndSyllables() {
        val result = client.parse(
            """
                {"Body":{"Type":"Syllable","Content":[
                  {"Lead":{"OppositeAligned":true,"Syllables":[
                    {"Text":"lead","StartTime":3,"EndTime":4},
                    {"Text":"back span","StartTime":4,"EndTime":5,"IsBackground":true}
                  ]},"Background":[
                    {"Syllables":[{"Text":"back track","StartTime":3,"EndTime":4}]}
                  ]},
                  {"Lead":{"Syllables":[
                    {"Text":"other","StartTime":8,"EndTime":9,"Role":"second backing"}
                  ]}}
                ]}}
            """.trimIndent()
        )!!
        val lines = (result.lyrics as SemanticLyrics.SyncedLyrics).text
        assertEquals(SpeakerEntity.Voice2, lines.first().speaker)
        assertEquals(2_999uL, lines.first().end)
        assertEquals(SpeakerEntity.Voice2Background, lines.first { it.text == "back span" }.speaker)
        assertEquals(SpeakerEntity.Voice2Background, lines.first { it.text == "back track" }.speaker)
        assertEquals(SpeakerEntity.Voice2Background, lines.first { it.text == "other" }.speaker)
        val interlude = lines.first { it.text == "• • •" && it.start > 0uL }
        assertEquals(5_001uL, interlude.start)
        assertEquals(7_999uL, interlude.end)
        assertEquals(SpeakerEntity.Voice2, interlude.speaker)
    }

    @Test
    fun parsesNestedLineBackgroundAndRightAlignedIntro() {
        val result = client.parse(
            """
                {"Body":{"Type":"Line","Content":[
                  {"Text":"duet","StartTime":3,"EndTime":4,"OppositeAligned":true,
                   "Background":[{"Text":"back","StartTime":3.5,"EndTime":4.5}]},
                  {"Text":"lead","StartTime":7,"EndTime":8}
                ]}}
            """.trimIndent()
        )!!
        val lines = (result.lyrics as SemanticLyrics.SyncedLyrics).text
        assertEquals(SpeakerEntity.Voice2, lines.first().speaker)
        assertEquals(SpeakerEntity.Voice2Background, lines.first { it.text == "back" }.speaker)
        assertEquals(SpeakerEntity.Voice, lines.first { it.text == "• • •" && it.start > 0uL }.speaker)
    }

    @Test
    fun insertsDotsAtExactGapThresholdWithoutOverlappingLines() {
        val result = client.parse(
            """
                {"Body":{"Type":"Line","EndTime":8,"Content":[
                  {"Text":"one","StartTime":2,"EndTime":3},
                  {"Text":"two","StartTime":5,"EndTime":6}
                ]}}
            """.trimIndent()
        )!!
        val lines = (result.lyrics as SemanticLyrics.SyncedLyrics).text
        assertEquals(listOf(0uL, 2_000uL, 3_001uL, 5_000uL, 6_001uL), lines.map { it.start })
        assertEquals(listOf(1_999uL, 3_000uL, 4_999uL, 6_000uL, 8_000uL), lines.map { it.end })
    }
}
