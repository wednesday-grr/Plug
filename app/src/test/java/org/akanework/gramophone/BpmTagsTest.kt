/*
 *     Copyright (C) 2026 The Gramophone authors
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

package org.akanework.gramophone

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.akanework.gramophone.logic.utils.automix.BpmTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BpmTagsTest {
    @Test
    fun readsId3TempoFrames() {
        assertEquals(128f, BpmTags.read(Metadata(
            TextInformationFrame("TBPM", null, listOf("128"))
        ))!!, 0f)
        assertEquals(123.5f, BpmTags.read(Metadata(
            TextInformationFrame("TXX", "bpm", listOf(" 123.5 "))
        ))!!, 0f)
        assertEquals(90f, BpmTags.read(Metadata(
            TextInformationFrame("TBP", null, listOf("90"))
        ))!!, 0f)
    }

    @Test
    fun readsVorbisCommentsAndSkipsInvalidValues() {
        val metadata = Metadata(
            VorbisComment("BPM", "NaN"),
            VorbisComment("BPM", "-1"),
            VorbisComment("tempo", "127.25")
        )
        assertEquals(127.25f, BpmTags.read(metadata)!!, 0f)
    }

    @Test
    fun ignoresUnrelatedAndInvalidMetadata() {
        assertNull(BpmTags.read(null))
        assertNull(BpmTags.read(Metadata(
            TextInformationFrame("TXXX", "not BPM", listOf("120")),
            VorbisComment("BPM", "Infinity"),
            VorbisComment("BPM", "0"),
            VorbisComment("BPM", "not a number")
        )))
    }
}
