/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Uncalibrated onset strength; not a probability that the event is a musical beat. */
data class OnsetCandidate(val sourceTimeUs: Long, val strength: Float) {
    init {
        require(sourceTimeUs >= 0 && strength.isFinite() && strength in 0f..1f)
    }
}

enum class BeatTempoInterpretation { PRIMARY, HALF_TIME, DOUBLE_TIME }

data class BeatTempoHypothesis(
    val interpretation: BeatTempoInterpretation,
    val bpm: Double,
    val support: Float,
)

/** Scores are relative evidence measures, not calibrated probabilities. */
data class BeatTrackingResult(
    val grid: DetectedBeatGridEvidence?,
    val hypotheses: List<BeatTempoHypothesis>,
    val selected: BeatTempoInterpretation?,
    val ambiguous: Boolean,
    val reason: String,
    val regions: List<BeatRegionEvidence> = emptyList(),
)

/**
 * Tracks observed spectral-flux peaks, never interpolated beats. It is intentionally conservative:
 * results are research evidence, not a validated beat detector or a playback synchronization input.
 */
object MusicalBeatTracker {
    const val ALGORITHM_VERSION = "spectral-onset-tracker-v3-local-interval"
    private const val MIN_ONSETS = 4
    private const val MIN_SEPARATION_US = 90_000L
    private const val MAX_LOG_INTERVAL_ERROR = 0.36
    private const val MAX_LOG_TEMPO_CHANGE = 0.25
    private const val AMBIGUITY_SUPPORT_MARGIN = 0.02

    suspend fun onsetCandidates(
        flux: DoubleArray,
        sampleRate: Int,
        hopFrames: Int,
        fftSize: Int,
        startUs: Long,
        window: AnalysisWindow,
    ): List<OnsetCandidate> {
        require(sampleRate > 0 && hopFrames > 0 && fftSize > 0 && startUs >= 0)
        currentCoroutineContext().ensureActive()
        require(flux.all { it.isFinite() && it >= 0 })
        if (flux.size < 3) return emptyList()
        val sorted = flux.filter { it > 1e-8 }.sorted()
        if (sorted.isEmpty()) return emptyList()
        val scale = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]
        if (scale <= 1e-8) return emptyList()
        val localRadius = (sampleRate.toDouble() / hopFrames * 0.5).toInt().coerceAtLeast(2)
        val peaks = mutableListOf<OnsetCandidate>()
        for (i in 1 until flux.lastIndex) {
            if (i % 256 == 0) currentCoroutineContext().ensureActive()
            if (flux[i] <= flux[i - 1] || flux[i] < flux[i + 1]) continue
            val local = flux.copyOfRange((i - localRadius).coerceAtLeast(0),
                (i + localRadius + 1).coerceAtMost(flux.size)).sorted()
            val baseline = local[local.size / 2]
            val prominence = flux[i] - baseline
            if (prominence <= scale * 0.08) continue
            val frames = i.toLong() * hopFrames + fftSize / 2
            val deltaUs = SourceTime.framesToMicros(frames, sampleRate)
            require(startUs <= Long.MAX_VALUE - deltaUs)
            val timestamp = startUs + deltaUs
            if (timestamp !in window.startUs..window.endUs) continue
            val candidate = OnsetCandidate(timestamp, (prominence / scale).coerceIn(0.0, 1.0).toFloat())
            if (peaks.isNotEmpty() && timestamp - peaks.last().sourceTimeUs < MIN_SEPARATION_US) {
                if (candidate.strength > peaks.last().strength) peaks[peaks.lastIndex] = candidate
            } else peaks.add(candidate)
        }
        return peaks
    }

    suspend fun track(
        onsets: List<OnsetCandidate>,
        bpmPrior: Float?,
        window: AnalysisWindow,
        timeline: SourceTimeline,
    ): BeatTrackingResult {
        currentCoroutineContext().ensureActive()
        require(onsets.all { it.sourceTimeUs in window.startUs..window.endUs })
        require(onsets.zipWithNext().all { (a, b) -> a.sourceTimeUs < b.sourceTimeUs })
        if (bpmPrior == null || !bpmPrior.isFinite() || bpmPrior <= 0f || onsets.size < MIN_ONSETS)
            return BeatTrackingResult(null, emptyList(), null, false, "Insufficient onset or tempo evidence",
                beatRegions(window, emptyList()))

        data class Path(
            val interpretation: BeatTempoInterpretation,
            val bpm: Double,
            val indexes: List<Int>,
            val beatSteps: IntArray,
            val localPeriods: DoubleArray,
            val support: Double,
        )
        val paths = listOf(
            BeatTempoInterpretation.PRIMARY to 1.0,
            BeatTempoInterpretation.HALF_TIME to 0.5,
            BeatTempoInterpretation.DOUBLE_TIME to 2.0,
        ).mapNotNull { (interpretation, multiplier) ->
            val bpm = bpmPrior * multiplier
            if (bpm !in 30.0..300.0) return@mapNotNull null
            val initialPeriod = 60_000_000.0 / bpm
            val scores = DoubleArray(onsets.size)
            val previous = IntArray(onsets.size) { -1 }
            val beatSteps = IntArray(onsets.size) { 1 }
            val localPeriods = DoubleArray(onsets.size) { initialPeriod }
            for (i in onsets.indices) {
                if (i % 128 == 0) currentCoroutineContext().ensureActive()
                scores[i] = onsets[i].strength.toDouble()
                var bestPrevious = -1
                var bestBeatSteps = 1
                var bestPeriod = initialPeriod
                for (j in i - 1 downTo 0) {
                    if ((i - j) % 256 == 0) currentCoroutineContext().ensureActive()
                    val delta = (onsets[i].sourceTimeUs - onsets[j].sourceTimeUs).toDouble()
                    if (delta > 4_500_000.0) break
                    val expectedPeriod = localPeriods[j]
                    val ratio = delta / expectedPeriod
                    val beats = if (ratio > 1.5) 2 else 1
                    val observedPeriod = delta / beats
                    if (observedPeriod !in 200_000.0..2_000_000.0) continue
                    val error = abs(ln(observedPeriod / expectedPeriod))
                    if (error > MAX_LOG_INTERVAL_ERROR || error > MAX_LOG_TEMPO_CHANGE) continue
                    val score = scores[j] + onsets[i].strength + 0.6 - error * 2.0 -
                        if (beats == 2) 0.6 else 0.0
                    if (score > scores[i]) {
                        scores[i] = score
                        previous[i] = j
                        beatSteps[i] = beats
                        bestPrevious = j
                        bestBeatSteps = beats
                        bestPeriod = kotlin.math.exp(
                            (ln(expectedPeriod) + ln(observedPeriod)) * 0.5,
                        )
                    }
                }
                if (bestPrevious >= 0) {
                    beatSteps[i] = bestBeatSteps
                    localPeriods[i] = bestPeriod
                }
            }
            val best = scores.indices.maxByOrNull { scores[it] } ?: return@mapNotNull null
            val indices = mutableListOf<Int>()
            var cursor = best
            while (cursor >= 0) {
                indices.add(cursor)
                cursor = previous[cursor]
            }
            indices.reverse()
            if (indices.size < MIN_ONSETS) return@mapNotNull null
            val selected = indices.map(onsets::get)
            val timing = indices.drop(1).map { index ->
                val parent = previous[index]
                val expectedPeriod = localPeriods[parent]
                val observedPeriod = (onsets[index].sourceTimeUs -
                    onsets[parent].sourceTimeUs).toDouble() / beatSteps[index]
                (1.0 - abs(ln(observedPeriod / expectedPeriod)) /
                    MAX_LOG_TEMPO_CHANGE).coerceIn(0.0, 1.0)
            }.average()
            val coverage = indices.size.toDouble() / onsets.size
            val meanStrength = selected.map { it.strength.toDouble() }.average()
            val observedStepFraction = indices.drop(1).map { 1.0 / beatSteps[it] }.average()
            val observedPeriods = indices.map { localPeriods[it] }.sorted()
            val medianPeriod = observedPeriods[observedPeriods.size / 2]
            Path(interpretation, 60_000_000.0 / medianPeriod, indices, beatSteps, localPeriods,
                (0.4 * timing + 0.25 * coverage + 0.2 * meanStrength +
                    0.15 * observedStepFraction).coerceIn(0.0, 1.0))
        }.sortedByDescending { it.support }
        val hypotheses = paths.map { BeatTempoHypothesis(it.interpretation, it.bpm, it.support.toFloat()) }
        val best = paths.firstOrNull() ?: return BeatTrackingResult(null, hypotheses, null, false,
            "No sufficiently supported observed onset sequence", beatRegions(window, emptyList()))
        val ambiguous = paths.size > 1 &&
            best.support - paths[1].support < AMBIGUITY_SUPPORT_MARGIN
        if (ambiguous) return BeatTrackingResult(null, hypotheses, null, true,
            "Tempo interpretations have similar uncalibrated support", beatRegions(window, emptyList()))
        var nextBeatIndex = 0L
        val events = best.indexes.mapIndexed { index, onsetIndex ->
            if (index > 0) nextBeatIndex += best.beatSteps[onsetIndex] - 1
            val onset = onsets[onsetIndex]
            val localIntervals = listOfNotNull(
                best.indexes.getOrNull(index - 1)?.let { previousIndex ->
                    val delta = onset.sourceTimeUs - onsets[previousIndex].sourceTimeUs
                    val observed = delta.toDouble() / best.beatSteps[onsetIndex]
                    abs(ln(observed / best.localPeriods[previousIndex]))
                },
                best.indexes.getOrNull(index + 1)?.let { nextIndex ->
                    val delta = onsets[nextIndex].sourceTimeUs - onset.sourceTimeUs
                    val observed = delta.toDouble() / best.beatSteps[nextIndex]
                    abs(ln(observed / best.localPeriods[onsetIndex]))
                },
            )
            val localSupport = localIntervals.map {
                (1.0 - it / MAX_LOG_TEMPO_CHANGE).coerceIn(0.0, 1.0)
            }.average()
            val score = sqrt(onset.strength * localSupport * best.support).toFloat()
            DetectedBeatEvent(nextBeatIndex++, onset.sourceTimeUs,
                musicalBeatConfidence = score, onsetStrength = onset.strength)
        }
        return BeatTrackingResult(
            DetectedBeatGridEvidence(events, "spectral-flux-onsets", ALGORITHM_VERSION, window, timeline,
                best.support.toFloat(), null),
            hypotheses, best.interpretation, false,
            "Observed onset sequence; musical-beat scores are uncalibrated",
            beatRegions(window, events),
        )
    }
}
