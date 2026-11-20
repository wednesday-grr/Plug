/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.utils.automix.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MusicalBeatTrackerTest {
    private val window = AnalysisWindow(0, 10_000_000)
    private val timeline = SourceTimeline(null, TimelineOrigin.UNKNOWN, TimelineMapping.UNKNOWN,
        TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN)

    @Test
    fun localPeaksUseOffsetsAndSuppressNearbyEvents() = runBlocking {
        val flux = DoubleArray(100)
        flux[10] = 4.0
        flux[11] = 0.1
        flux[12] = 2.0
        flux[50] = 3.0
        val peaks = MusicalBeatTracker.onsetCandidates(flux, 1000, 10, 20, 1_000_000,
            AnalysisWindow(1_000_000, 2_000_000))
        assertEquals(listOf(1_110_000L, 1_510_000L), peaks.map { it.sourceTimeUs })
        assertTrue(peaks.all { it.strength in 0f..1f })
    }

    @Test
    fun silenceAndConstantEnergyHaveNoCandidates() = runBlocking {
        assertTrue(MusicalBeatTracker.onsetCandidates(DoubleArray(100), 1000, 10, 20, 0, window).isEmpty())
        assertTrue(MusicalBeatTracker.onsetCandidates(DoubleArray(100) { 1.0 }, 1000, 10, 20, 0, window).isEmpty())
    }

    @Test
    fun trackedEventsComeOnlyFromObservedOnsets() = runBlocking {
        val times = listOf(100_000L, 600_000L, 1_100_000L, 1_600_000L, 2_100_000L,
            2_600_000L, 3_120_000L, 3_620_000L)
        val candidates = times.map { OnsetCandidate(it, 1f) }
        val result = MusicalBeatTracker.track(candidates, 120f, window, timeline)
        val grid = checkNotNull(result.grid)
        assertTrue(grid.events.size >= 4)
        assertTrue(grid.events.all { it.sourceTimeUs in times })
        assertTrue(grid.events.all { it.onsetStrength == 1f })
        assertTrue(grid.events.all { it.downbeat == null && it.downbeatConfidence == null })
        assertEquals(BeatTempoInterpretation.PRIMARY, result.selected)
    }

    @Test
    fun windowBoundsAndLeadingSilenceDoNotInventEvents() = runBlocking {
        val flux = DoubleArray(100)
        flux[10] = 4.0
        flux[50] = 3.0
        val peaks = MusicalBeatTracker.onsetCandidates(flux, 1000, 10, 20, 1_000_000,
            AnalysisWindow(1_200_000, 1_600_000))
        assertEquals(listOf(1_510_000L), peaks.map { it.sourceTimeUs })
    }

    @Test
    fun nearbyEqualStrengthEventsCanMakeTempoAmbiguous() = runBlocking {
        val onsets = (0..15).map { OnsetCandidate(100_000L + it * 250_000L, 1f) }
        val result = MusicalBeatTracker.track(onsets, 120f, window, timeline)
        assertTrue(result.hypotheses.size >= 2)
        if (result.ambiguous) {
            assertNull(result.grid)
            assertNull(result.selected)
        } else {
            assertTrue(result.grid!!.events.all { beat -> onsets.any { it.sourceTimeUs == beat.sourceTimeUs } })
        }
    }

    @Test
    fun audioAnalyzerReusesFluxAndPreservesLegacyAnalysis() = runBlocking {
        val rate = TrackDspAnalyzer.SAMPLE_RATE
        val samples = FloatArray(rate * 20) { index ->
            val seconds = index.toDouble() / rate
            if (seconds % 0.5 < 0.025) (0.8 * sin(2 * PI * 1000 * seconds)).toFloat() else 0f
        }
        val legacy = TrackDspAnalyzer.analyze(samples, 30_000)
        val result = TrackDspAnalyzer.analyzeWithBeatTracking(samples, 30_000, timeline)
        assertEquals(legacy, result.analysis)
        assertTrue(result.tracking.hypotheses.isNotEmpty())
        assertTrue(result.tracking.grid?.events?.all { it.sourceTimeUs in 30_000_000L..50_000_000L } != false)
        assertEquals(TimelineMapping.UNKNOWN, timeline.mappingToMedia3)
    }

    @Test
    fun invalidCandidatesAndSparseEvidenceAreRejected() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { OnsetCandidate(-1, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { OnsetCandidate(10, Float.NaN) }
        val sparse = listOf(OnsetCandidate(100_000, 1f), OnsetCandidate(600_000, 1f))
        assertNull(MusicalBeatTracker.track(sparse, 120f, window, timeline).grid)
        assertNull(MusicalBeatTracker.track(sparse, null, window, timeline).grid)
    }

    @Test
    fun analysisWindowPreservesSubMillisecondSampleDuration() = runBlocking {
        val rate = TrackDspAnalyzer.SAMPLE_RATE
        val samples = FloatArray(rate * 20 + 1) { index ->
            val seconds = index.toDouble() / rate
            if (seconds % 0.5 < 0.025) (0.8 * sin(2 * PI * 1000 * seconds)).toFloat() else 0f
        }
        val result = TrackDspAnalyzer.analyzeWithBeatTracking(samples, 30_000, timeline)
        assertEquals(50_000L, result.analysis.windowEndMs)
        assertEquals(50_000_090L, checkNotNull(result.tracking.grid).window.endUs)
    }
}
