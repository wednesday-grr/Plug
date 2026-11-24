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

import androidx.media3.common.Metadata
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detects a prominent transient in an interleaved PCM window. A transient is not necessarily a
 * downbeat; callers must fall back when it cannot be identified reliably.
 */
object BeatPhaseAligner {
    const val DEFAULT_MINIMUM_CONFIDENCE = 0.8f
    data class Grid(val beatPeriodMs: Double, val phaseOffsetMs: Double, val confidence: Float)
    data class Alignment(val startDelayMs: Double, val incomingSpeed: Float)

    /**
     * Estimates a recurring beat phase, not a bar downbeat: BPM and transients do not contain
     * enough information to determine which beat starts a bar.
     */
    fun estimate(metadata: Metadata?, transientPositionsUs: List<Long>): Grid? =
        estimate(BpmTags.read(metadata), transientPositionsUs)

    fun estimate(bpm: Float?, transientPositionsUs: List<Long>): Grid? {
        if (bpm == null || !bpm.isFinite() || bpm !in 30f..240f) return null
        val timestamps = transientPositionsUs.asSequence()
            .filter { it >= 0L }.distinct().sorted().toList().takeLast(32)
        if (timestamps.size < 4) return null
        val periodMs = 60_000.0 / bpm
        if ((timestamps.last() - timestamps.first()).toDouble() / 1000.0 < 2 * periodMs) return null
        var x = 0.0
        var y = 0.0
        for (timestamp in timestamps) {
            val angle = 2 * PI * ((timestamp / 1000.0) % periodMs) / periodMs
            x += cos(angle)
            y += sin(angle)
        }
        val confidence = (hypot(x, y) / timestamps.size).coerceIn(0.0, 1.0).toFloat()
        val angle = (atan2(y, x) + 2 * PI) % (2 * PI)
        return Grid(periodMs, angle * periodMs / (2 * PI), confidence)
    }

    /**
     * Calculates a nonnegative delay from the current positions at unit outgoing speed.
     * The incoming deck must start at [incomingPositionMs] with the returned speed.
     */
    fun align(
        outgoingBpm: Float?,
        incomingBpm: Float?,
        outgoingGrid: Grid?,
        incomingGrid: Grid?,
        outgoingPositionMs: Double,
        incomingPositionMs: Double,
        minimumConfidence: Float = DEFAULT_MINIMUM_CONFIDENCE,
    ): Alignment? {
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f)
        require(outgoingPositionMs.isFinite() && outgoingPositionMs >= 0)
        require(incomingPositionMs.isFinite() && incomingPositionMs >= 0)
        val ratio = AutomixMath.tempoRatio(outgoingBpm, incomingBpm) ?: return null
        if (outgoingGrid == null || incomingGrid == null ||
            outgoingGrid.confidence !in minimumConfidence..1f ||
            incomingGrid.confidence !in minimumConfidence..1f ||
            !outgoingGrid.beatPeriodMs.isFinite() || !incomingGrid.beatPeriodMs.isFinite() ||
            !outgoingGrid.phaseOffsetMs.isFinite() || !incomingGrid.phaseOffsetMs.isFinite() ||
            outgoingGrid.beatPeriodMs <= 0 || incomingGrid.beatPeriodMs <= 0 ||
            kotlin.math.abs(outgoingGrid.beatPeriodMs - 60_000.0 / outgoingBpm!!) > 0.001 ||
            kotlin.math.abs(incomingGrid.beatPeriodMs - 60_000.0 / incomingBpm!!) > 0.001
        ) return null
        val period = outgoingGrid.beatPeriodMs
        fun modulo(value: Double, divisor: Double) = ((value % divisor) + divisor) % divisor
        val outgoingUntilBeat = modulo(outgoingGrid.phaseOffsetMs - outgoingPositionMs, period)
        val incomingUntilBeat =
            modulo(incomingGrid.phaseOffsetMs - incomingPositionMs, incomingGrid.beatPeriodMs) / ratio
        return Alignment(modulo(outgoingUntilBeat - incomingUntilBeat, period), ratio)
    }

    fun findTransientFrame(samples: FloatArray, channels: Int, windowFrames: Int): Int? {
        require(channels > 0 && windowFrames > 0)
        require(samples.size % channels == 0)
        val frames = samples.size / channels
        if (frames / windowFrames < 3) return null
        var previousRms = 0.0
        var bestIncrease = 0.0
        var bestFrame: Int? = null
        var startFrame = 0
        while (startFrame + windowFrames <= frames) {
            var energy = 0.0
            for (sample in startFrame * channels until (startFrame + windowFrames) * channels) {
                if (!samples[sample].isFinite()) return null
                energy += samples[sample].toDouble() * samples[sample]
            }
            val rms = sqrt(energy / (windowFrames.toDouble() * channels))
            val increase = rms - previousRms
            if (startFrame > 0 && rms >= 0.01 && increase > bestIncrease && increase >= 0.01) {
                bestIncrease = increase
                bestFrame = startFrame
            }
            previousRms = rms
            startFrame += windowFrames
        }
        return bestFrame
    }
}
