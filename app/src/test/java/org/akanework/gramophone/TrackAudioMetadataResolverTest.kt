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
package org.akanework.gramophone

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import org.akanework.gramophone.logic.utils.automix.TrackAnalysis
import org.akanework.gramophone.logic.utils.automix.TrackAudioMetadataResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackAudioMetadataResolverTest {
    private val analysis = TrackAnalysis(120f, "8A", 10_100.0, 0.9f, 10_000, 70_000)

    @Test
    fun tagsWinIndependentlyAndDifferentTempoInvalidatesGrid() {
        val tags = Metadata(
            TextInformationFrame("TBPM", null, listOf("130")),
            TextInformationFrame("TKEY", null, listOf("C"))
        )
        val result = TrackAudioMetadataResolver.resolve(listOf(tags), analysis, 20_000)
        assertEquals(130f, result.bpm!!, 0f)
        assertEquals("8B", result.camelotKey)
        assertNull(result.beatGrid)
    }

    @Test
    fun fallsBackToCacheButDoesNotExtrapolateGridOutsideWindow() {
        val inside = TrackAudioMetadataResolver.resolve(emptyList(), analysis, 30_000)
        assertEquals(120f, inside.bpm!!, 0f)
        assertEquals("8A", inside.camelotKey)
        assertNotNull(inside.beatGrid)
        assertNull(TrackAudioMetadataResolver.resolve(emptyList(), analysis, 1000).beatGrid)
    }
}
