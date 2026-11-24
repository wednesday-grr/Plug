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
package com.siw.plug.logic.utils.automix

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackAnalysis(
    val bpm: Float?,
    val camelotKey: String?,
    val beatOffsetMs: Double?,
    val confidence: Float,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val vocalEnergy: Float? = null,
    val edgeAnalysis: TrackEdges? = null,
    val pulseProvenance: PulseProvenance? = null,
)

data class TrackBeatAnalysis(val analysis: TrackAnalysis, val tracking: BeatTrackingResult)

/** Offline analysis; beat grids describe only the analyzed window, not a bar or a whole song. */
object TrackDspAnalyzer {
    const val SAMPLE_RATE = 11025
    const val BEAT_ANALYSIS_CONFIG_VERSION = "track-dsp-beat-config-v3-observation-regions"
    private const val FFT_SIZE = 2048
    private const val HOP = 256
    private val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val notes = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    suspend fun analyze(samples: FloatArray, startMs: Long): TrackAnalysis =
        analyzeInternal(samples, startMs, null).first

    /** Offline opt-in research output; it is not connected to playback or the analysis cache. */
    suspend fun analyzeWithBeatTracking(
        samples: FloatArray, startMs: Long, timeline: SourceTimeline,
    ): TrackBeatAnalysis {
        val (analysis, tracking) = analyzeInternal(samples, startMs, timeline)
        return TrackBeatAnalysis(analysis, checkNotNull(tracking))
    }

    private suspend fun analyzeInternal(
        samples: FloatArray, startMs: Long, timeline: SourceTimeline?,
    ): Pair<TrackAnalysis, BeatTrackingResult?> {
        require(startMs >= 0)
        require(samples.all { it.isFinite() })
        val endMs = startMs + samples.size.toLong() * 1000 / SAMPLE_RATE
        if (samples.size < FFT_SIZE * 4) return TrackAnalysis(null, null, null, 0f, startMs, endMs) to
            timeline?.let {
                val startUs = SourceTime.millisecondsToMicros(startMs)
                val durationUs = SourceTime.framesToMicros(samples.size.toLong(), SAMPLE_RATE)
                require(startUs <= Long.MAX_VALUE - durationUs)
                BeatTrackingResult(null, emptyList(), null, false, "Analysis window is too short",
                    beatRegions(AnalysisWindow(startUs, startUs + durationUs), emptyList()))
            }
        val count = 1 + (samples.size - FFT_SIZE) / HOP
        val flux = DoubleArray(count)
        val chroma = DoubleArray(12)
        var vocalBandEnergy = 0.0
        var analyzedBandEnergy = 0.0
        val previous = DoubleArray(FFT_SIZE / 2)
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        for (frame in 0 until count) {
            if (frame % 64 == 0) currentCoroutineContext().ensureActive()
            val base = frame * HOP
            for (i in real.indices) {
                real[i] = samples[base + i] * window[i]
                imag[i] = 0.0
            }
            fft(real, imag)
            for (bin in 8 until FFT_SIZE / 2) {
                val magnitude = hypot(real[bin], imag[bin])
                val compressed = ln(1.0 + magnitude)
                flux[frame] += (compressed - previous[bin]).coerceAtLeast(0.0)
                previous[bin] = compressed
                val hz = bin.toDouble() * SAMPLE_RATE / FFT_SIZE
                if (hz in 80.0..8000.0) {
                    val energy = magnitude * magnitude
                    analyzedBandEnergy += energy
                    if (hz in VOCAL_LOW_HZ..VOCAL_HIGH_HZ) vocalBandEnergy += energy
                }
                if (frame % 4 == 0) {
                    if (hz in 65.0..2100.0) {
                        val midi = (69 + 12 * ln(hz / 440.0) / ln(2.0)).roundToInt()
                        chroma[((midi % 12) + 12) % 12] += magnitude
                    }
                }
            }
        }
        val tempo = tempo(flux, startMs)
        val key = detectKey(chroma)
        val vocalEnergy = if (analyzedBandEnergy > 0.0) {
            (vocalBandEnergy / analyzedBandEnergy).toFloat().coerceIn(0f, 1f)
        } else null
        val analysis = TrackAnalysis(
            tempo?.first, key, tempo?.second, tempo?.third ?: 0f, startMs, endMs, vocalEnergy
        )
        val tracking = timeline?.let {
            val startUs = SourceTime.millisecondsToMicros(startMs)
            val durationUs = SourceTime.framesToMicros(samples.size.toLong(), SAMPLE_RATE)
            require(startUs <= Long.MAX_VALUE - durationUs)
            val bounds = AnalysisWindow(startUs, startUs + durationUs)
            val onsets = MusicalBeatTracker.onsetCandidates(flux, SAMPLE_RATE, HOP, FFT_SIZE,
                bounds.startUs, bounds)
            MusicalBeatTracker.track(onsets, analysis.bpm, bounds, it)
        }
        return analysis to tracking
    }

    private fun tempo(envelope: DoubleArray, startMs: Long): Triple<Float, Double, Float>? {
        // Remove the slowly varying background so sustained notes don't dominate autocorrelation.
        val onset = DoubleArray(envelope.size)
        var mean = 0.0
        for (i in envelope.indices) {
            mean = mean * 0.95 + envelope[i] * 0.05
            onset[i] = (envelope[i] - mean).coerceAtLeast(0.0)
        }
        if (onset.sum() < 1e-6) return null
        val framesPerSecond = SAMPLE_RATE.toDouble() / HOP
        val minLag = (framesPerSecond * 60 / 185).toInt()
        val maxLag = (framesPerSecond * 60 / 65).toInt()
        val correlations = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var dot = 0.0
            var left = 0.0
            var right = 0.0
            for (i in lag until onset.size) {
                dot += onset[i] * onset[i - lag]
                left += onset[i] * onset[i]
                right += onset[i - lag] * onset[i - lag]
            }
            if (left > 0 && right > 0) correlations[lag] = dot / sqrt(left * right)
        }
        val lag = (minLag..maxLag).maxByOrNull { candidate ->
            correlations[candidate] +
                (if (candidate * 2 <= maxLag) 0.35 * correlations[candidate * 2] else 0.0) +
                (if (candidate / 2 >= minLag) 0.15 * correlations[candidate / 2] else 0.0)
        } ?: return null
        val confidence = correlations[lag].toFloat().coerceIn(0f, 1f)
        if (confidence < 0.12f) return null
        val refinedLag = if (lag > minLag && lag < maxLag) {
            val before = correlations[lag - 1]
            val center = correlations[lag]
            val after = correlations[lag + 1]
            val curvature = before - 2 * center + after
            if (curvature < -1e-12)
                lag + (0.5 * (before - after) / curvature).coerceIn(-0.5, 0.5)
            else lag.toDouble()
        } else lag.toDouble()
        val bpm = (60 * framesPerSecond / refinedLag).toFloat().coerceIn(65f, 185f)
        var x = 0.0
        var y = 0.0
        var total = 0.0
        for (i in onset.indices) {
            val angle = 2 * PI * i / refinedLag
            x += onset[i] * cos(angle)
            y += onset[i] * sin(angle)
            total += onset[i]
        }
        val phase = (atan2(y, x) + 2 * PI) % (2 * PI)
        val phaseMs = startMs + (phase / (2 * PI) * refinedLag * HOP + FFT_SIZE / 2) *
            1000.0 / SAMPLE_RATE
        val phaseConfidence = (hypot(x, y) / total).toFloat().coerceIn(0f, 1f)
        return Triple(bpm, phaseMs, minOf(confidence, phaseConfidence))
    }

    private fun detectKey(chroma: DoubleArray): String? {
        if (chroma.sum() < 1e-6) return null
        var best = Double.NEGATIVE_INFINITY
        var second = Double.NEGATIVE_INFINITY
        var bestNote = 0
        var bestMinor = false
        for (root in 0..11) {
            for (isMinor in listOf(false, true)) {
                val profile = if (isMinor) minor else major
                val meanChroma = chroma.average()
                val meanProfile = profile.average()
                var numerator = 0.0
                var a = 0.0
                var b = 0.0
                for (i in 0..11) {
                    val c = chroma[(i + root) % 12] - meanChroma
                    val p = profile[i] - meanProfile
                    numerator += c * p
                    a += c * c
                    b += p * p
                }
                val score = if (a > 0) numerator / sqrt(a * b) else 0.0
                if (score > best) {
                    second = best
                    best = score
                    bestNote = root
                    bestMinor = isMinor
                } else if (score > second) second = score
            }
        }
        if (best < 0.4 || best - second < 0.02) return null
        return MusicalKey.parse(notes[bestNote] + if (bestMinor) "m" else " major")?.camelot
    }

    private const val VOCAL_LOW_HZ = 250.0
    private const val VOCAL_HIGH_HZ = 4000.0

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val size = real.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tmp = real[i]; real[i] = real[j]; real[j] = tmp
            }

        }
        var length = 2
        while (length <= size) {
            val angle = -2 * PI / length
            val wrStep = cos(angle)
            val wiStep = sin(angle)
            for (start in 0 until size step length) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val tr = wr * real[odd] - wi * imag[odd]
                    val ti = wr * imag[odd] + wi * real[odd]
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - ti
                    real[even] += tr
                    imag[even] += ti
                    val next = wr * wrStep - wi * wiStep
                    wi = wr * wiStep + wi * wrStep
                    wr = next
                }
            }
            length *= 2
        }
    }
}
