/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.siw.plug

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.siw.plug.logic.utils.automix.MusicalKey
import com.siw.plug.logic.utils.automix.MusicalKeyTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicalKeyTagsTest {
    @Test
    fun convertsNotesAndEnharmonicEquivalents() {
        assertEquals("8A", MusicalKey.parse("Am")?.camelot)
        assertEquals("8B", MusicalKey.parse("C major")?.camelot)
        assertEquals("3B", MusicalKey.parse("D♭")?.camelot)
        assertEquals(MusicalKey.parse("C#"), MusicalKey.parse("Db"))
        assertEquals("2A", MusicalKey.parse("D# minor")?.camelot)
        assertEquals("8B", MusicalKey.parse("B#")?.camelot)
        assertEquals("1B", MusicalKey.parse("Cb")?.camelot)
        assertEquals("11B", MusicalKey.parse("AM")?.camelot)
    }

    @Test
    fun convertsBothWheelNotations() {
        assertEquals("1m", MusicalKey.parse("8A")?.openKey)
        assertEquals("1d", MusicalKey.parse("8B")?.openKey)
        assertEquals("8A", MusicalKey.parse("1m")?.camelot)
        assertEquals("8B", MusicalKey.parse("1d")?.camelot)
        for (number in 1..12) {
            for (suffix in listOf("A", "B")) {
                val original = MusicalKey.parse("$number$suffix")!!
                assertEquals(original, MusicalKey.parse(original.openKey))
            }
        }
    }

    @Test
    fun readsId3AndVorbisAndSkipsInvalidValues() {
        val metadata = Metadata(
            TextInformationFrame("TKEY", null, listOf("off", "NaN")),
            TextInformationFrame("TXXX", "initialkey", listOf("F#m")),
            VorbisComment("INITIALKEY", "Am")
        )
        assertEquals("11A", MusicalKeyTags.read(metadata)?.camelot)
        assertEquals("8B", MusicalKeyTags.read(Metadata(VorbisComment("KEY", "C")))?.camelot)
        assertEquals("8A", MusicalKeyTags.read(Metadata(
            TextInformationFrame("TKE", null, listOf("Am"))
        ))?.camelot)
        assertNull(MusicalKeyTags.read(null))
        assertNull(MusicalKeyTags.read(Metadata(VorbisComment("BPM", "120"))))
        assertNull(MusicalKey.parse("13A"))
        assertNull(MusicalKey.parse("C# dorian"))
        assertNull(MusicalKey.parse("A major extra"))
    }
}
