/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import androidx.media3.common.Metadata as EmbeddedMetadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.siw.plug.logic.utils.automix.SmartQueueSorter
import com.siw.plug.logic.utils.automix.SmartQueueSorter.Metadata
import com.siw.plug.logic.utils.automix.TrackAnalysis
import com.siw.plug.logic.utils.automix.TrackAudioMetadataResolver
import org.junit.Assert.*
import org.junit.Test

class SmartQueueSorterTest {
    @Test
    fun scoresTempoAndOctaves() {
        assertEquals(1.0, SmartQueueSorter.bpmScore(70f, 140f)!!, 0.0001)
        assertEquals(1.0, SmartQueueSorter.bpmScore(140f, 70f)!!, 0.0001)
        assertTrue(SmartQueueSorter.bpmScore(100f, 106f)!! >
            SmartQueueSorter.bpmScore(100f, 115f)!!)
        assertNull(SmartQueueSorter.bpmScore(Float.NaN, 100f))
        assertNull(SmartQueueSorter.bpmScore(null, 100f))
    }

    @Test
    fun scoresCamelotWheelWithWraparound() {
        val exact = SmartQueueSorter.keyScore("8A", "8A")!!
        val adjacent = SmartQueueSorter.keyScore("8A", "9A")!!
        val relative = SmartQueueSorter.keyScore("8A", "8B")!!
        assertTrue(exact > adjacent && adjacent > relative)
        assertEquals(adjacent, SmartQueueSorter.keyScore("1A", "12A")!!, 0.0)
        assertTrue(SmartQueueSorter.keyScore("8A", "10A")!! >
            SmartQueueSorter.keyScore("8A", "12A")!!)
        assertNull(SmartQueueSorter.keyScore("8A", "not a key"))
    }

    @Test
    fun preservesPrefixAndDoesNotLetUnknownTracksBreakChain() {
        val data = listOf(
            Metadata(80f, "1A"), Metadata(120f, "8A"),
            Metadata(null, null), Metadata(160f, "2B"),
            Metadata(121f, "8A"), Metadata(122f, "9A")
        )
        assertEquals(listOf(0, 1, 4, 5, 3, 2), SmartQueueSorter.order(data, 1))
    }

    @Test
    fun stableForMissingDataAndEqualScores() {
        val data = List(4) { Metadata(null, null) }
        assertEquals(listOf(0, 1, 2, 3), SmartQueueSorter.order(data, 0))
    }

    @Test
    fun embeddedTagsTakePrecedenceWhenChoosingNextTrack() {
        val cached = TrackAnalysis(160f, "2A", null, 0.9f, 0, 1000)
        val tags = EmbeddedMetadata(
            TextInformationFrame("TBPM", null, listOf("120")),
            TextInformationFrame("TKEY", null, listOf("8A"))
        )
        val resolved = TrackAudioMetadataResolver.resolve(listOf(tags), cached, 0)
        val tracks = listOf(
            Metadata(120f, "8A"), Metadata(160f, "2A"),
            Metadata(resolved.bpm, resolved.camelotKey)
        )
        assertEquals(listOf(0, 2, 1), SmartQueueSorter.order(tracks, 0))
    }
}
