/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import kotlinx.coroutines.runBlocking
import com.siw.plug.logic.utils.automix.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BeatEvidenceTest {
    private val unknownTimeline = SourceTimeline(null, TimelineOrigin.UNKNOWN, TimelineMapping.UNKNOWN,
        TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN)

    @Test
    fun spectralFluxProducesPeriodicEvidenceNotDetectedBeats() = runBlocking {
        val rate = TrackDspAnalyzer.SAMPLE_RATE
        val samples = FloatArray(rate * 20) { index ->
            val seconds = index.toDouble() / rate
            if (seconds % 0.5 < 0.025) (0.8 * sin(2 * PI * 1000 * seconds)).toFloat() else 0f
        }
        val analysis = TrackDspAnalyzer.analyze(samples, 30_000)
        val pulse = BeatEvidenceAdapter.fromSpectralFlux(analysis, "track", TimelineOrigin.DECODER_PRESENTATION)!!
        assertEquals(EvidenceSource.SPECTRAL_FLUX, pulse.provenance.source)
        assertEquals(ConfidenceMeaning.ONSET_PERIODICITY_AND_PHASE_CONCENTRATION, pulse.confidence?.meaning)
        assertEquals(TimelineOrigin.DECODER_PRESENTATION, pulse.timeline.origin)
        assertEquals(TimelineMapping.UNKNOWN, pulse.timeline.mappingToMedia3)
        assertEquals(30_000_000L, pulse.window.startUs)
        assertNull(pulse.musicalBeatConfidence)
        assertNull(pulse.downbeatConfidence)
    }

    @Test
    fun transientGridIsNotMusicalBeatEvidence() {
        val grid = BeatPhaseAligner.estimate(120f,
            listOf(100_000, 600_000, 1_100_000, 1_600_000))!!
        val pulse = BeatEvidenceAdapter.fromTransientGrid(grid, 0, 2000, "track")!!
        assertEquals(EvidenceSource.RMS_TRANSIENT, pulse.provenance.source)
        assertEquals(ConfidenceMeaning.TRANSIENT_PHASE_CONCENTRATION, pulse.confidence?.meaning)
        assertEquals(500_000L, pulse.periodUs)
        assertEquals(100_000L, pulse.phaseSourceTimeUs)
        assertEquals(120.0, pulse.bpm, 0.001)
        assertNull(pulse.musicalBeatConfidence)
        assertNull(pulse.downbeatConfidence)
    }

    @Test
    fun legacyAndMissingPhaseRemainDistinct() {
        val old = TrackAnalysis(120f, "8A", 125.0, 0.9f, 0, 5000)
        val pulse = BeatEvidenceAdapter.fromAnalysis(old, null)!!
        assertEquals(EvidenceSource.LEGACY_CACHE, pulse.provenance.source)
        assertEquals(ConfidenceMeaning.LEGACY_UNSPECIFIED, pulse.confidence?.meaning)
        assertNull(pulse.provenance.algorithmVersion)
        assertFalse(pulse.algorithmRecognized)
        assertEquals(TimelineOrigin.UNKNOWN, pulse.timeline.origin)
        assertFalse(BeatEvidenceAdapter.isAlgorithmVersion(pulse.provenance, BeatEvidenceAdapter.SPECTRAL_FLUX_VERSION))
        assertNull(BeatEvidenceAdapter.fromAnalysis(old.copy(beatOffsetMs = null), null))
        assertNull(BeatEvidenceAdapter.fromAnalysis(old.copy(bpm = null), null))
    }

    @Test
    fun unknownAlgorithmVersionRetainsButDoesNotReinterpretEvidence() {
        val metadata = BeatEvidenceAdapter.spectralFluxProvenance.copy(algorithmVersion = "future-v2")
        val pulse = BeatEvidenceAdapter.fromAnalysis(
            TrackAnalysis(120f, null, 100.0, 0.9f, 0, 1000, pulseProvenance = metadata), null)!!
        assertFalse(pulse.algorithmRecognized)
        assertEquals("future-v2", pulse.provenance.algorithmVersion)
        assertEquals(ConfidenceMeaning.UNKNOWN_ALGORITHM, pulse.confidence?.meaning)
    }

    @Test
    fun emptyDetectedGridIsDifferentFromUnavailable() {
        val unavailable: DetectedBeatGridEvidence? = null
        val empty = DetectedBeatGridEvidence(emptyList(), "future", "v1",
            AnalysisWindow(0, 1_000_000), unknownTimeline, null, null)
        assertNull(unavailable)
        assertTrue(empty.events.isEmpty())
        assertNull(empty.musicalBeatConfidence)
        assertNull(empty.downbeatConfidence)
        val measured = empty.copy(events = listOf(
            DetectedBeatEvent(0, 100_000), DetectedBeatEvent(1, 510_000),
            DetectedBeatEvent(2, 990_000)))
        assertEquals(410_000L, measured.events[1].sourceTimeUs - measured.events[0].sourceTimeUs)
        assertEquals(480_000L, measured.events[2].sourceTimeUs - measured.events[1].sourceTimeUs)
    }

    @Test
    fun confidenceValidationAndUnavailableConfidence() {
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceConfidence(Float.NaN, ConfidenceMeaning.LEGACY_UNSPECIFIED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceConfidence(1.01f, ConfidenceMeaning.TRANSIENT_PHASE_CONCENTRATION)
        }
        val event = DetectedBeatEvent(0, 1000)
        assertNull(event.musicalBeatConfidence)
        assertNull(event.downbeatConfidence)
        val unavailable = PeriodicPulseEvidence(500_000, 0,
            PulseProvenance(EvidenceSource.LEGACY_CACHE, null, TimelineOrigin.UNKNOWN, TimelineMapping.UNKNOWN),
            null, AnalysisWindow(0, 1000), unknownTimeline, emptyList())
        assertNull(unavailable.confidence)
        val reference = EvidenceReference(null, "unavailable", null)
        val alignment = AlignmentEvidence(reference, reference, null, null, null, null,
            AlignmentEligibility.NOT_EVALUATED, null)
        assertNull(alignment.alignmentConfidence)
        assertNull(BeatEvidenceAdapter.fromAnalysis(
            TrackAnalysis(120f, null, 0.0, Float.NaN, 0, 1000), null))
    }

    @Test
    fun sourceTimeConversionsAndBounds() {
        assertEquals(1_234_000L, SourceTime.millisecondsToMicros(1234L))
        assertEquals(1_234_500L, SourceTime.millisecondsToMicros(1234.5))
        assertEquals(1234.5, SourceTime.microsToMilliseconds(1_234_500), 0.0)
        assertEquals(1_500_000L, SourceTime.framesToMicros(72_000, 48_000))
        assertEquals(22L, SourceTime.framesToMicros(1, 44_100))
        assertThrows(IllegalArgumentException::class.java) { SourceTime.millisecondsToMicros(-1L) }
        assertThrows(IllegalArgumentException::class.java) { SourceTime.microsToMilliseconds(-1) }
        assertThrows(IllegalArgumentException::class.java) { SourceTime.framesToMicros(-1, 44_100) }
        assertThrows(IllegalArgumentException::class.java) { SourceTime.framesToMicros(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SourceTime.millisecondsToMicros(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { AnalysisWindow(100, 99) }
    }

    @Test
    fun timelineRecordsUnknownAndKnownTrimWithoutInventingPrimingCorrection() {
        assertEquals(TimingFact.UNKNOWN, unknownTimeline.leadingSilenceIncluded)
        assertEquals(TimingFact.UNKNOWN, unknownTimeline.encoderPrimingCorrected)
        val trimmed = SourceTimeline("track", TimelineOrigin.DECODER_PRESENTATION, TimelineMapping.UNKNOWN,
            TimingFact.UNKNOWN, TimingFact.YES, TimingFact.UNKNOWN, TimingFact.YES, 250_000)
        assertEquals(250_000L, trimmed.edgeTrimOffsetUs)
        assertEquals(TimingFact.YES, trimmed.leadingSilenceIncluded)
        assertThrows(IllegalArgumentException::class.java) {
            trimmed.copy(edgeTrimApplied = TimingFact.NO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            unknownTimeline.copy(mappingToMedia3 = TimelineMapping.VERIFIED)
        }
    }
}
