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
    fun variableTempoPreservesObservedIntervalsWithoutExtrapolation() = runBlocking {
        val times = listOf(
            100_000L, 600_000L, 1_100_000L, 1_600_000L,
            2_200_000L, 2_800_000L, 3_400_000L, 4_000_000L,
        )
        val strengths = List(times.size) { 1f }
        val result = MusicalBeatTracker.track(
            times.zip(strengths).map { (time, strength) -> OnsetCandidate(time, strength) },
            110f,
            window,
            timeline,
        )
        val grid = result.grid ?: error("${result.reason}; hypotheses=${result.hypotheses}")

        assertEquals(times, grid.events.map { it.sourceTimeUs })
        assertEquals(times.zipWithNext().map { (a, b) -> b - a },
            grid.events.zipWithNext().map { (a, b) -> b.sourceTimeUs - a.sourceTimeUs })
        assertEquals(BeatTempoInterpretation.PRIMARY, result.selected)
    }

    @Test
    fun gradualTempoChangeFollowsLocalIntervalsWithoutInventingBeats() = runBlocking {
        val intervals = listOf(500_000L, 480_000L, 460_000L, 440_000L, 420_000L, 400_000L)
        val times = intervals.scan(100_000L) { previous, interval -> previous + interval }
        val result = MusicalBeatTracker.track(
            times.map { OnsetCandidate(it, 1f) },
            120f,
            window,
            timeline,
        )
        val grid = result.grid ?: error("${result.reason}; hypotheses=${result.hypotheses}")

        assertEquals(times, grid.events.map { it.sourceTimeUs })
        assertEquals(intervals, grid.events.zipWithNext().map { (a, b) ->
            b.sourceTimeUs - a.sourceTimeUs
        })
        assertEquals(BeatTempoInterpretation.PRIMARY, result.selected)
        assertTrue(grid.events.zipWithNext().all { (a, b) -> b.index == a.index + 1 })
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
        val result = MusicalBeatTracker.track(sparse, 120f, window, timeline)
        assertNull(result.grid)
        assertEquals(listOf(BeatRegionEvidence(window, BeatRegionObservation.NO_CONSECUTIVE_EVENTS)),
            result.regions)
        assertNull(MusicalBeatTracker.track(sparse, null, window, timeline).grid)
    }

    @Test
    fun regionsDescribeObservedIntervalsAndGapsWithoutClaimingReliability() {
        val events = listOf(
            DetectedBeatEvent(0, 1_000_000),
            DetectedBeatEvent(1, 1_500_000),
            DetectedBeatEvent(3, 2_500_000),
            DetectedBeatEvent(4, 3_000_000),
        )
        assertEquals(listOf(
            BeatRegionEvidence(AnalysisWindow(0, 1_000_000), BeatRegionObservation.NO_CONSECUTIVE_EVENTS),
            BeatRegionEvidence(AnalysisWindow(1_000_000, 1_500_000), BeatRegionObservation.CONSECUTIVE_OBSERVED_EVENTS),
            BeatRegionEvidence(AnalysisWindow(1_500_000, 2_500_000), BeatRegionObservation.NO_CONSECUTIVE_EVENTS),
            BeatRegionEvidence(AnalysisWindow(2_500_000, 3_000_000), BeatRegionObservation.CONSECUTIVE_OBSERVED_EVENTS),
            BeatRegionEvidence(AnalysisWindow(3_000_000, 10_000_000), BeatRegionObservation.NO_CONSECUTIVE_EVENTS),
        ), beatRegions(window, events))
        assertEquals(listOf(BeatRegionEvidence(window, BeatRegionObservation.NO_CONSECUTIVE_EVENTS)),
            beatRegions(window, emptyList()))
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
