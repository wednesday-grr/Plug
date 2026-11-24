/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

import kotlin.math.roundToLong

/** Source time is expressed in microseconds; mapping to a player's position is not implied. */
object SourceTime {
    fun millisecondsToMicros(milliseconds: Long): Long {
        require(milliseconds >= 0 && milliseconds <= Long.MAX_VALUE / 1_000)
        return milliseconds * 1_000
    }

    fun millisecondsToMicros(milliseconds: Double): Long {
        require(milliseconds.isFinite() && milliseconds >= 0 &&
            milliseconds < Long.MAX_VALUE.toDouble() / 1_000)
        return (milliseconds * 1_000).roundToLong()
    }

    fun microsToMilliseconds(microseconds: Long): Double {
        require(microseconds >= 0)
        return microseconds / 1_000.0
    }

    fun framesToMicros(frames: Long, sampleRate: Int): Long {
        require(frames >= 0 && sampleRate > 0)
        val seconds = frames / sampleRate
        val remainder = frames % sampleRate
        require(seconds <= (Long.MAX_VALUE - remainder * 1_000_000 / sampleRate) / 1_000_000)
        return seconds * 1_000_000 + remainder * 1_000_000 / sampleRate
    }
}

enum class TimelineOrigin { DECODER_PRESENTATION, MEDIA3_STREAM_OFFSET, UNKNOWN }
enum class TimelineMapping { UNKNOWN, VERIFIED }
enum class TimingFact { YES, NO, UNKNOWN }

/**
 * Identity is supplied by the caller, not inferred from a Media3 period UID. A decoder timestamp
 * and a player position are not interchangeable unless their mapping has been verified.
 */
data class SourceTimeline(
    val identity: String?,
    val origin: TimelineOrigin,
    val mappingToMedia3: TimelineMapping,
    val relativeToOriginalSource: TimingFact,
    val leadingSilenceIncluded: TimingFact,
    val encoderPrimingCorrected: TimingFact,
    val edgeTrimApplied: TimingFact,
    val edgeTrimOffsetUs: Long? = null,
) {
    init {
        require(identity == null || identity.isNotBlank())
        require(edgeTrimOffsetUs == null || edgeTrimOffsetUs >= 0)
        require(edgeTrimOffsetUs == null || edgeTrimApplied == TimingFact.YES)
        require(origin != TimelineOrigin.UNKNOWN || mappingToMedia3 == TimelineMapping.UNKNOWN)
    }
}

data class AnalysisWindow(val startUs: Long, val endUs: Long) {
    init { require(startUs >= 0 && endUs >= startUs) }
}

enum class EvidenceSource { SPECTRAL_FLUX, RMS_TRANSIENT, LEGACY_CACHE }
enum class ConfidenceMeaning {
    /** Minimum of normalized onset autocorrelation and onset phase concentration. */
    ONSET_PERIODICITY_AND_PHASE_CONCENTRATION,
    /** Circular concentration of the selected RMS transient timestamps at the supplied BPM. */
    TRANSIENT_PHASE_CONCENTRATION,
    /** Legacy scalar; estimator and calibration cannot be established from the old row. */
    LEGACY_UNSPECIFIED,
    /** The recorded algorithm version is not one understood by this adapter. */
    UNKNOWN_ALGORITHM,
}

data class EvidenceConfidence(val value: Float, val meaning: ConfidenceMeaning) {
    init { require(value.isFinite() && value in 0f..1f) }
}

/** Stored independently of the legacy BPM/phase fields; null metadata denotes a legacy row. */
data class PulseProvenance(
    val source: EvidenceSource,
    val algorithmVersion: String?,
    val origin: TimelineOrigin,
    val mappingToMedia3: TimelineMapping,
) {
    init {
        require(algorithmVersion == null || algorithmVersion.isNotBlank())
        require(origin != TimelineOrigin.UNKNOWN || mappingToMedia3 == TimelineMapping.UNKNOWN)
    }
}

/** Phase is an equivalent periodic reference in the stated timeline, not a detected event. */
data class PeriodicPulseEvidence(
    val periodUs: Long,
    val phaseSourceTimeUs: Long,
    val provenance: PulseProvenance,
    val confidence: EvidenceConfidence?,
    val window: AnalysisWindow,
    val timeline: SourceTimeline,
    val limitations: List<String>,
) {
    init {
        require(periodUs > 0 && phaseSourceTimeUs >= 0)
        require(provenance.origin == timeline.origin && provenance.mappingToMedia3 == timeline.mappingToMedia3)
    }
    val bpm: Double get() = 60_000_000.0 / periodUs
    val algorithmRecognized: Boolean get() = when (provenance.source) {
        EvidenceSource.SPECTRAL_FLUX -> BeatEvidenceAdapter.isAlgorithmVersion(provenance, BeatEvidenceAdapter.SPECTRAL_FLUX_VERSION)
        EvidenceSource.RMS_TRANSIENT -> BeatEvidenceAdapter.isAlgorithmVersion(provenance, BeatEvidenceAdapter.RMS_TRANSIENT_VERSION)
        EvidenceSource.LEGACY_CACHE -> false
    }
    val musicalBeatConfidence: Float? get() = null
    val downbeatConfidence: Float? get() = null
}

data class DetectedBeatEvent(
    val index: Long,
    val sourceTimeUs: Long,
    val musicalBeatConfidence: Float? = null,
    val downbeat: Boolean? = null,
    val barIndex: Long? = null,
    val downbeatConfidence: Float? = null,
    val onsetStrength: Float? = null,
) {
    init {
        require(sourceTimeUs >= 0)
        require(musicalBeatConfidence == null || musicalBeatConfidence.isFinite() && musicalBeatConfidence in 0f..1f)
        require(downbeatConfidence == null || downbeatConfidence.isFinite() && downbeatConfidence in 0f..1f)
        require(onsetStrength == null || onsetStrength.isFinite() && onsetStrength in 0f..1f)
        require(barIndex == null || downbeat != null)
    }
}

enum class BeatRegionObservation { CONSECUTIVE_OBSERVED_EVENTS, NO_CONSECUTIVE_EVENTS }

/**
 * Describes observed event spacing only. It is not a reliability classification, a probability
 * of a beat match, or an estimate of reference-beat coverage.
 */
data class BeatRegionEvidence(
    val window: AnalysisWindow,
    val observation: BeatRegionObservation,
)

/** Partitions the window without interpolating events or assigning reliability to gaps. */
fun beatRegions(window: AnalysisWindow, events: List<DetectedBeatEvent>): List<BeatRegionEvidence> {
    require(events.all { it.sourceTimeUs in window.startUs..window.endUs })
    require(events.zipWithNext().all { (a, b) ->
        a.index < b.index && a.sourceTimeUs < b.sourceTimeUs
    })
    val regions = mutableListOf<BeatRegionEvidence>()
    fun add(start: Long, end: Long, observation: BeatRegionObservation) {
        if (start == end && window.startUs != window.endUs) return
        val last = regions.lastOrNull()
        if (last != null && last.observation == observation && last.window.endUs == start) {
            regions[regions.lastIndex] = last.copy(window = AnalysisWindow(last.window.startUs, end))
        } else {
            regions.add(BeatRegionEvidence(AnalysisWindow(start, end), observation))
        }
    }
    if (events.isEmpty()) {
        add(window.startUs, window.endUs, BeatRegionObservation.NO_CONSECUTIVE_EVENTS)
    } else {
        add(window.startUs, events.first().sourceTimeUs, BeatRegionObservation.NO_CONSECUTIVE_EVENTS)
        events.zipWithNext().forEach { (first, second) ->
            add(first.sourceTimeUs, second.sourceTimeUs,
                if (first.index != Long.MAX_VALUE && second.index == first.index + 1)
                    BeatRegionObservation.CONSECUTIVE_OBSERVED_EVENTS
                else BeatRegionObservation.NO_CONSECUTIVE_EVENTS)
        }
        add(events.last().sourceTimeUs, window.endUs, BeatRegionObservation.NO_CONSECUTIVE_EVENTS)
    }
    if (regions.isEmpty()) add(window.startUs, window.endUs, BeatRegionObservation.NO_CONSECUTIVE_EVENTS)
    return regions
}

/** An empty event list is a valid result and differs from a null (unavailable) grid. */
data class DetectedBeatGridEvidence(
    val events: List<DetectedBeatEvent>,
    val source: String,
    val algorithmVersion: String,
    val window: AnalysisWindow,
    val timeline: SourceTimeline,
    val musicalBeatConfidence: Float?,
    val downbeatConfidence: Float?,
) {
    init {
        require(source.isNotBlank() && algorithmVersion.isNotBlank())
        require(musicalBeatConfidence == null || musicalBeatConfidence.isFinite() && musicalBeatConfidence in 0f..1f)
        require(downbeatConfidence == null || downbeatConfidence.isFinite() && downbeatConfidence in 0f..1f)
        require(events.all { it.sourceTimeUs in window.startUs..window.endUs })
        require(events.zipWithNext().all { (a, b) -> a.index < b.index && a.sourceTimeUs < b.sourceTimeUs })
    }
}

data class BeatGridCacheRecord(
    val sourceIdentity: String,
    val configurationVersion: String,
    val result: BeatTrackingResult,
    val window: AnalysisWindow,
    val timeline: SourceTimeline,
    val algorithmVersion: String = MusicalBeatTracker.ALGORITHM_VERSION,
) {
    init {
        require(sourceIdentity.isNotBlank() && configurationVersion.isNotBlank() && algorithmVersion.isNotBlank())
        require(timeline.identity == sourceIdentity)
        require(result.regions.isEmpty() || result.regions == beatRegions(window, result.grid?.events.orEmpty()))
        result.grid?.let {
            require(it.window == window && it.timeline == timeline)
            require(it.algorithmVersion == algorithmVersion)
            require(it.downbeatConfidence == null && it.events.all { event ->
                event.downbeat == null && event.barIndex == null && event.downbeatConfidence == null
            })
        }
    }

    val state: BeatGridCacheState
        get() = when {
            result.grid == null -> BeatGridCacheState.UNAVAILABLE
            result.grid.events.isEmpty() -> BeatGridCacheState.EMPTY
            else -> BeatGridCacheState.DETECTED
        }
}

enum class BeatGridCacheState { UNAVAILABLE, EMPTY, DETECTED, INVALIDATED }

sealed interface BeatGridCacheRead {
    data object Missing : BeatGridCacheRead
    data object Invalidated : BeatGridCacheRead
    data class Available(val record: BeatGridCacheRecord) : BeatGridCacheRead
}

data class EvidenceReference(val timelineIdentity: String?, val source: String, val algorithmVersion: String?)
enum class AlignmentEligibility { ELIGIBLE, INELIGIBLE, NOT_EVALUATED }
data class AlignmentEvidence(
    val outgoing: EvidenceReference,
    val incoming: EvidenceReference,
    val tempoInterpretation: BpmInterpretation?,
    val phaseOffsetUs: Long?,
    val delayUs: Long?,
    val alignmentConfidence: Float?,
    val eligibility: AlignmentEligibility,
    val reason: String?,
) {
    init {
        require(delayUs == null || delayUs >= 0)
        require(alignmentConfidence == null || alignmentConfidence.isFinite() && alignmentConfidence in 0f..1f)
    }
}

object BeatEvidenceAdapter {
    const val SPECTRAL_FLUX_VERSION = "spectral-flux-v1"
    const val RMS_TRANSIENT_VERSION = "rms-transient-v1"

    val spectralFluxProvenance = PulseProvenance(
        EvidenceSource.SPECTRAL_FLUX, SPECTRAL_FLUX_VERSION,
        TimelineOrigin.DECODER_PRESENTATION, TimelineMapping.UNKNOWN,
    )
    private val legacy = PulseProvenance(
        EvidenceSource.LEGACY_CACHE, null, TimelineOrigin.UNKNOWN, TimelineMapping.UNKNOWN,
    )

    /** Version mismatch affects evidence interpretation, never legacy playback/cache retrieval. */
    fun isAlgorithmVersion(provenance: PulseProvenance, expected: String): Boolean =
        provenance.algorithmVersion != null && provenance.algorithmVersion == expected

    /** Use when the caller knows the analysis was produced by the current spectral-flux analyzer. */
    fun fromSpectralFlux(
        analysis: TrackAnalysis, timelineIdentity: String?, origin: TimelineOrigin,
    ): PeriodicPulseEvidence? = fromAnalysis(
        analysis.copy(pulseProvenance = spectralFluxProvenance.copy(origin = origin)),
        timelineIdentity,
    )

    fun fromAnalysis(analysis: TrackAnalysis, timelineIdentity: String?): PeriodicPulseEvidence? {
        val bpm = analysis.bpm ?: return null
        val phase = analysis.beatOffsetMs ?: return null
        if (!bpm.isFinite() || bpm <= 0f || !phase.isFinite() || phase < 0 ||
            analysis.windowStartMs < 0 || analysis.windowEndMs < analysis.windowStartMs
        ) return null
        val provenance = analysis.pulseProvenance ?: legacy
        val meaning = when (provenance.source) {
            EvidenceSource.SPECTRAL_FLUX -> if (isAlgorithmVersion(provenance, SPECTRAL_FLUX_VERSION))
                ConfidenceMeaning.ONSET_PERIODICITY_AND_PHASE_CONCENTRATION else ConfidenceMeaning.UNKNOWN_ALGORITHM
            EvidenceSource.RMS_TRANSIENT -> if (isAlgorithmVersion(provenance, RMS_TRANSIENT_VERSION))
                ConfidenceMeaning.TRANSIENT_PHASE_CONCENTRATION else ConfidenceMeaning.UNKNOWN_ALGORITHM
            EvidenceSource.LEGACY_CACHE -> ConfidenceMeaning.LEGACY_UNSPECIFIED
        }
        return pulse(60_000.0 / bpm, phase, analysis.confidence, meaning, provenance,
            analysis.windowStartMs, analysis.windowEndMs, timelineIdentity)
    }

    /** Called off the audio callback on an already computed transient grid, not per PCM sample. */
    fun fromTransientGrid(
        grid: BeatPhaseAligner.Grid,
        windowStartMs: Long,
        windowEndMs: Long,
        timelineIdentity: String?,
    ): PeriodicPulseEvidence? {
        val provenance = PulseProvenance(EvidenceSource.RMS_TRANSIENT, RMS_TRANSIENT_VERSION,
            TimelineOrigin.MEDIA3_STREAM_OFFSET, TimelineMapping.UNKNOWN)
        return pulse(grid.beatPeriodMs, grid.phaseOffsetMs, grid.confidence,
            ConfidenceMeaning.TRANSIENT_PHASE_CONCENTRATION, provenance,
            windowStartMs, windowEndMs, timelineIdentity)
    }

    private fun pulse(
        periodMs: Double, phaseMs: Double, confidence: Float?, meaning: ConfidenceMeaning,
        provenance: PulseProvenance, startMs: Long, endMs: Long, identity: String?,
    ): PeriodicPulseEvidence? {
        if (!periodMs.isFinite() || periodMs <= 0 || periodMs >= Long.MAX_VALUE.toDouble() / 1_000 ||
            !phaseMs.isFinite() || phaseMs < 0 || phaseMs >= Long.MAX_VALUE.toDouble() / 1_000 ||
            startMs < 0 || endMs < startMs || endMs > Long.MAX_VALUE / 1_000 ||
            confidence != null && (!confidence.isFinite() || confidence !in 0f..1f)
        ) return null
        val periodUs = SourceTime.millisecondsToMicros(periodMs)
        if (periodUs == 0L) return null
        return PeriodicPulseEvidence(
            periodUs, SourceTime.millisecondsToMicros(phaseMs), provenance,
            confidence?.let { EvidenceConfidence(it, meaning) },
            AnalysisWindow(SourceTime.millisecondsToMicros(startMs), SourceTime.millisecondsToMicros(endMs)),
            SourceTimeline(identity, provenance.origin, provenance.mappingToMedia3,
                TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN),
            listOf("Periodic transients are not verified musical beats; downbeat and Media3 timeline mapping are unknown."),
        )
    }
}
