/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import com.siw.plug.logic.utils.automix.TrackDspAnalyzer
import com.siw.plug.logic.utils.automix.TrackEdgeAnalyzer
import com.siw.plug.logic.utils.automix.TrackEdges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackEdgeAnalyzerTest {
    @Test
    fun detectsSilenceAndLimitsBothEnds() {
        val rate = TrackDspAnalyzer.SAMPLE_RATE
        val start = FloatArray(rate * 10) { if (it < rate * 2) 0f else 0.1f }
        val end = FloatArray(rate * 10) { if (it >= rate * 7) 0f else 0.1f }
        val edges = checkNotNull(TrackEdgeAnalyzer.analyze(start, end, 100_000))
        assertEquals(2_000L, edges.leadingSilenceMs)
        assertEquals(3_000L, edges.trailingSilenceMs)
        assertEquals(2_000L, TrackEdgeAnalyzer.startPosition(edges, 15_000))
        assertEquals(97_000L, TrackEdgeAnalyzer.endPosition(edges, 100_000, 15_000))
        assertEquals(0L, TrackEdgeAnalyzer.startPosition(edges, 0))
        assertEquals(100_000L, TrackEdgeAnalyzer.endPosition(edges, 100_000, 0))
    }

    @Test
    fun candidatePointsRespectTimeAndTenPercentLimits() {
        val edges = TrackEdges(100_000, 1000, 1000, 9000, 12_000)
        assertEquals(9000L, TrackEdgeAnalyzer.startPosition(edges, 15_000))
        assertEquals(99_000L, TrackEdgeAnalyzer.endPosition(edges, 100_000, 15_000))
        assertEquals(1000L, TrackEdgeAnalyzer.startPosition(edges, 5000))
        assertEquals(90_000L, TrackEdgeAnalyzer.endPosition(
            edges.copy(outroSkipMs = 9000, trailingSilenceMs = 20_000), 100_000, 30_000))
        assertEquals(101_500L, TrackEdgeAnalyzer.endPosition(edges, 101_500, 15_000))
    }

    @Test
    fun silentOrInvalidWindowsHaveNoSafePoint() {
        val silence = FloatArray(TrackDspAnalyzer.SAMPLE_RATE)
        assertNull(TrackEdgeAnalyzer.analyze(silence, silence, 30_000))
        assertNull(TrackEdgeAnalyzer.analyze(floatArrayOf(Float.NaN), silence, 30_000))
        assertEquals(0L, TrackEdgeAnalyzer.startPosition(null, 15_000))
        assertEquals(30_000L, TrackEdgeAnalyzer.endPosition(null, 30_000, 15_000))
    }
}
