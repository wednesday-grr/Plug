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
)

/**
 * Tracks observed spectral-flux peaks, never interpolated beats. It is intentionally conservative:
 * results are research evidence, not a validated beat detector or a playback synchronization input.
 */
object MusicalBeatTracker {
    const val ALGORITHM_VERSION = "spectral-onset-tracker-v2"
    private const val MIN_ONSETS = 4
    private const val MIN_SEPARATION_US = 90_000L
    private const val MAX_LOG_INTERVAL_ERROR = 0.36

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
            return BeatTrackingResult(null, emptyList(), null, false, "Insufficient onset or tempo evidence")

        data class Path(val interpretation: BeatTempoInterpretation, val bpm: Double,
            val indexes: List<Int>, val support: Double)
        val paths = listOf(
            BeatTempoInterpretation.PRIMARY to 1.0,
            BeatTempoInterpretation.HALF_TIME to 0.5,
            BeatTempoInterpretation.DOUBLE_TIME to 2.0,
        ).mapNotNull { (interpretation, multiplier) ->
            val bpm = bpmPrior * multiplier
            if (bpm !in 30.0..300.0) return@mapNotNull null
            val period = 60_000_000.0 / bpm
            val scores = DoubleArray(onsets.size)
            val previous = IntArray(onsets.size) { -1 }
            for (i in onsets.indices) {
                if (i % 128 == 0) currentCoroutineContext().ensureActive()
                scores[i] = onsets[i].strength.toDouble()
                for (j in i - 1 downTo 0) {
                    if ((i - j) % 256 == 0) currentCoroutineContext().ensureActive()
                    val delta = (onsets[i].sourceTimeUs - onsets[j].sourceTimeUs).toDouble()
                    if (delta > period * 2.25) break
                    val beats = if (delta > period * 1.5) 2 else 1
                    val error = abs(ln(delta / (period * beats)))
                    if (error > MAX_LOG_INTERVAL_ERROR) continue
                    val score = scores[j] + onsets[i].strength + 0.6 - error * 2.0 -
                        if (beats == 2) 0.6 else 0.0
                    if (score > scores[i]) {
                        scores[i] = score
                        previous[i] = j
                    }
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
            val timing = selected.zipWithNext().map { (a, b) ->
                val ratio = (b.sourceTimeUs - a.sourceTimeUs) / period
                val count = if (ratio > 1.5) 2 else 1
                (1.0 - abs(ln(ratio / count)) / MAX_LOG_INTERVAL_ERROR).coerceIn(0.0, 1.0) / count
            }.average()
            val coverage = indices.size.toDouble() / onsets.size
            val meanStrength = selected.map { it.strength.toDouble() }.average()
            Path(interpretation, bpm, indices,
                (0.45 * timing + 0.3 * coverage + 0.25 * meanStrength).coerceIn(0.0, 1.0))
        }.sortedByDescending { it.support }
        val hypotheses = paths.map { BeatTempoHypothesis(it.interpretation, it.bpm, it.support.toFloat()) }
        val best = paths.firstOrNull() ?: return BeatTrackingResult(null, hypotheses, null, false,
            "No sufficiently supported observed onset sequence")
        val ambiguous = paths.size > 1 && best.support - paths[1].support < 0.08
        if (ambiguous) return BeatTrackingResult(null, hypotheses, null, true,
            "Tempo interpretations have similar uncalibrated support")
        val period = 60_000_000.0 / best.bpm
        var beatIndex = 0L
        val events = best.indexes.mapIndexed { index, onsetIndex ->
            val onset = onsets[onsetIndex]
            val previousTime = if (index > 0) onsets[best.indexes[index - 1]].sourceTimeUs else null
            if (previousTime != null && onset.sourceTimeUs - previousTime > period * 1.5) beatIndex++
            val localIntervals = listOfNotNull(
                previousTime?.let { onset.sourceTimeUs - it },
                best.indexes.getOrNull(index + 1)?.let { onsets[it].sourceTimeUs - onset.sourceTimeUs },
            )
            val localSupport = localIntervals.map { delta ->
                val count = if (delta > period * 1.5) 2 else 1
                (1.0 - abs(ln(delta / (period * count))) / MAX_LOG_INTERVAL_ERROR)
                    .coerceIn(0.0, 1.0) / count
            }.average()
            val score = sqrt(onset.strength * localSupport * best.support).toFloat()
            DetectedBeatEvent(beatIndex++, onset.sourceTimeUs,
                musicalBeatConfidence = score, onsetStrength = onset.strength)
        }
        return BeatTrackingResult(
            DetectedBeatGridEvidence(events, "spectral-flux-onsets", ALGORITHM_VERSION, window, timeline,
                best.support.toFloat(), null),
            hypotheses, best.interpretation, false,
            "Observed onset sequence; musical-beat scores are uncalibrated",
        )
    }
}
