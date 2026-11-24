/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.tan

/**
 * Short-term energy over time for one analysed window. Levels are unweighted RMS in dBFS of the band-limited,
 * mono-downmixed analysis audio, so they describe relative physical energy and are NOT perceived loudness.
 * Frames that were digital silence hold [SILENT_DB].
 */
class EnergyProfile(
    val hopMs: Int,
    /** Source time of the first frame's start. */
    val startMs: Long,
    val rmsDb: FloatArray,
    val lowDb: FloatArray,
    val midDb: FloatArray,
    val highDb: FloatArray,
    /** sampleRate / 2 of the analysed audio. */
    val bandwidthHz: Float,
) {
    init {
        require(hopMs > 0)
        require(rmsDb.size == lowDb.size && rmsDb.size == midDb.size && rmsDb.size == highDb.size)
    }

    val frameCount: Int get() = rmsDb.size
    val endMs: Long get() = startMs + frameCount.toLong() * hopMs

    /** Aggregates frames whose start lies in [fromMs, toMs); silent frames are excluded from levels. */
    fun summarize(fromMs: Long, toMs: Long, silenceFloorDb: Float = DEFAULT_SILENCE_FLOOR_DB): EnergyRegionSummary? {
        val indices = (0 until frameCount).filter { startMs + it.toLong() * hopMs in fromMs until toMs }
        if (indices.isEmpty()) return null
        val active = indices.filter { rmsDb[it] > silenceFloorDb }
        val coverage = active.size.toFloat() / indices.size
        if (active.isEmpty()) return EnergyRegionSummary(null, null, null, null, null, 0f, indices.size, bandwidthHz)
        fun meanDb(values: FloatArray): Float {
            val power = active.sumOf { Math.pow(10.0, values[it] / 10.0) } / active.size
            return (10.0 * log10(power.coerceAtLeast(1e-12))).toFloat()
        }
        var trend: Float? = null
        if (active.size >= 3) {
            val xs = active.map { (startMs + it.toLong() * hopMs) / 1000.0 }
            val ys = active.map { rmsDb[it].toDouble() }
            val mx = xs.average()
            val my = ys.average()
            val den = xs.sumOf { (it - mx) * (it - mx) }
            if (den > 0) trend = (xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den).toFloat()
        }
        return EnergyRegionSummary(
            meanDb(rmsDb), meanDb(lowDb), meanDb(midDb), meanDb(highDb), trend, coverage,
            indices.size, bandwidthHz,
        )
    }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + frameCount * 16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(hopMs).putLong(startMs).putFloat(bandwidthHz).putInt(frameCount)
        for (i in 0 until frameCount) {
            buffer.putFloat(rmsDb[i]).putFloat(lowDb[i]).putFloat(midDb[i]).putFloat(highDb[i])
        }
        return buffer.array()
    }

    companion object {
        const val SILENT_DB = -120f
        const val DEFAULT_SILENCE_FLOOR_DB = -60f
        private const val HEADER_BYTES = 4 + 8 + 4 + 4

        /** Returns null for corrupt data so callers treat the profile as unavailable rather than zero. */
        fun fromBytes(bytes: ByteArray): EnergyProfile? {
            if (bytes.size < HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val hop = buffer.int
            val start = buffer.long
            val bandwidth = buffer.float
            val count = buffer.int
            if (hop <= 0 || count < 0 || bytes.size != HEADER_BYTES + count * 16) return null
            val rms = FloatArray(count); val low = FloatArray(count)
            val mid = FloatArray(count); val high = FloatArray(count)
            for (i in 0 until count) {
                rms[i] = buffer.float; low[i] = buffer.float; mid[i] = buffer.float; high[i] = buffer.float
            }
            if ((rms + low + mid + high).any { it.isNaN() }) return null
            return EnergyProfile(hop, start, rms, low, mid, high, bandwidth)
        }
    }
}

/** Levels are null when no non-silent frame existed in the region. */
data class EnergyRegionSummary(
    val rmsDb: Float?,
    val lowDb: Float?,
    val midDb: Float?,
    val highDb: Float?,
    /** Least-squares slope of the RMS level; null with fewer than 3 non-silent frames. */
    val trendDbPerSecond: Float?,
    /** Fraction of frames in the region that were not silent. */
    val coverage: Float,
    val frames: Int,
    val bandwidthHz: Float,
)

/** Start and end windows of a track; either may be missing when it could not be analysed. */
data class TrackEnergy(val start: EnergyProfile?, val end: EnergyProfile?, val durationMs: Long)

object EnergyAnalyzer {
    const val ALGORITHM_VERSION = "energy-bands-v1"
    const val LOW_CROSSOVER_HZ = 250.0
    const val HIGH_CROSSOVER_HZ = 4000.0
    private const val FRAME_MS = 1000
    const val HOP_MS = 500

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    // Bilinear-transform 2nd-order Butterworth.
    private fun lowPass(fc: Double, fs: Double): Biquad {
        val k = tan(PI * fc / fs)
        val q = 1.0 / Math.sqrt(2.0)
        val norm = 1.0 / (1.0 + k / q + k * k)
        return Biquad(k * k * norm, 2 * k * k * norm, k * k * norm,
            2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
    }

    private fun highPass(fc: Double, fs: Double): Biquad {
        val k = tan(PI * fc / fs)
        val q = 1.0 / Math.sqrt(2.0)
        val norm = 1.0 / (1.0 + k / q + k * k)
        return Biquad(norm, -2 * norm, norm, 2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
    }

    /** Mono PCM in -1..1. Returns null when the window is shorter than one frame. */
    fun analyze(samples: FloatArray, sampleRate: Int, startMs: Long): EnergyProfile? {
        require(sampleRate > 2 * HIGH_CROSSOVER_HZ)
        val frameSamples = sampleRate * FRAME_MS / 1000
        val hopSamples = sampleRate * HOP_MS / 1000
        if (samples.size < frameSamples) return null
        val frames = (samples.size - frameSamples) / hopSamples + 1
        val fs = sampleRate.toDouble()
        val low = lowPass(LOW_CROSSOVER_HZ, fs)
        val midHigh = highPass(LOW_CROSSOVER_HZ, fs)
        val midLow = lowPass(HIGH_CROSSOVER_HZ, fs)
        val high = highPass(HIGH_CROSSOVER_HZ, fs)
        val energy = Array(4) { DoubleArray(samples.size) }
        for (i in samples.indices) {
            val x = samples[i].toDouble()
            energy[0][i] = x * x
            val l = low.process(x)
            energy[1][i] = l * l
            val m = midLow.process(midHigh.process(x))
            energy[2][i] = m * m
            val h = high.process(x)
            energy[3][i] = h * h
        }
        val out = Array(4) { FloatArray(frames) }
        for (f in 0 until frames) {
            val from = f * hopSamples
            for (band in 0 until 4) {
                var sum = 0.0
                for (i in from until from + frameSamples) sum += energy[band][i]
                val ms = sum / frameSamples
                out[band][f] = if (ms <= 1e-12) EnergyProfile.SILENT_DB else (10.0 * log10(ms)).toFloat()
            }
        }
        return EnergyProfile(HOP_MS, startMs, out[0], out[1], out[2], out[3], sampleRate / 2f)
    }
}

/** Reasons an energy comparison did or did not change the transition. */
enum class EnergyMatchReason {
    OUTGOING_UNAVAILABLE, INCOMING_UNAVAILABLE, LOW_CONFIDENCE, SOURCES_NOT_COMPARABLE,
    WITHIN_THRESHOLD, EXTENDED_FOR_RISE, EXTENDED_FOR_DROP
}

data class EnergyMatchConfig(
    /** Incoming energy above outgoing by more than this is a jump worth smoothing. */
    val riseThresholdDb: Float = 6f,
    /** Drops are a natural part of an outro, so they tolerate more before being smoothed. */
    val dropThresholdDb: Float = 9f,
    val maxDurationScale: Float = 1.5f,
    val minCoverage: Float = 0.6f,
    val minFrames: Int = 4,
    val regionMs: Long = 10_000,
) {
    init {
        require(riseThresholdDb > 0f && dropThresholdDb > 0f)
        require(maxDurationScale >= 1f)
        require(minCoverage in 0f..1f && minFrames >= 1 && regionMs > 0)
    }
}

data class EnergyMatchPlan(
    val reason: EnergyMatchReason,
    val durationScale: Float,
    val differenceDb: Float?,
    val lowDifferenceDb: Float?,
    val midDifferenceDb: Float?,
    val highDifferenceDb: Float?,
    val outgoing: EnergyRegionSummary?,
    val incoming: EnergyRegionSummary?,
) {
    val applied: Boolean get() = durationScale > 1f
}

/**
 * Energy Matching never normalises tracks to the same energy. It only lengthens the overlap when the
 * incoming region differs substantially from the outgoing one, so the progression is gradual instead of
 * abrupt, and it reports "unavailable" instead of guessing whenever data is missing or mostly silent.
 */
object EnergyMatcher {
    fun plan(outgoing: EnergyRegionSummary?, incoming: EnergyRegionSummary?, config: EnergyMatchConfig = EnergyMatchConfig()): EnergyMatchPlan {
        fun unavailable(reason: EnergyMatchReason) =
            EnergyMatchPlan(reason, 1f, null, null, null, null, outgoing, incoming)
        if (outgoing == null || outgoing.rmsDb == null) return unavailable(EnergyMatchReason.OUTGOING_UNAVAILABLE)
        if (incoming == null || incoming.rmsDb == null) return unavailable(EnergyMatchReason.INCOMING_UNAVAILABLE)
        if (outgoing.coverage < config.minCoverage || incoming.coverage < config.minCoverage ||
            outgoing.frames < config.minFrames || incoming.frames < config.minFrames
        ) return unavailable(EnergyMatchReason.LOW_CONFIDENCE)
        if (abs(outgoing.bandwidthHz - incoming.bandwidthHz) > 1f) {
            return unavailable(EnergyMatchReason.SOURCES_NOT_COMPARABLE)
        }
        val diff = incoming.rmsDb - outgoing.rmsDb
        fun band(a: Float?, b: Float?) = if (a != null && b != null) b - a else null
        val threshold = if (diff >= 0) config.riseThresholdDb else config.dropThresholdDb
        val excess = abs(diff) - threshold
        val scale = if (excess <= 0f) 1f
        else 1f + (excess / threshold).coerceAtMost(1f) * (config.maxDurationScale - 1f)
        return EnergyMatchPlan(
            if (scale <= 1f) EnergyMatchReason.WITHIN_THRESHOLD
            else if (diff > 0) EnergyMatchReason.EXTENDED_FOR_RISE else EnergyMatchReason.EXTENDED_FOR_DROP,
            scale, diff,
            band(outgoing.lowDb, incoming.lowDb), band(outgoing.midDb, incoming.midDb),
            band(outgoing.highDb, incoming.highDb), outgoing, incoming,
        )
    }

    /** Outgoing = last [EnergyMatchConfig.regionMs] of the end window; incoming = first of the start window. */
    fun plan(outgoing: TrackEnergy?, incoming: TrackEnergy?, config: EnergyMatchConfig = EnergyMatchConfig()): EnergyMatchPlan {
        val out = outgoing?.end?.let { it.summarize(it.endMs - config.regionMs, it.endMs) }
        val inc = incoming?.start?.let { it.summarize(it.startMs, it.startMs + config.regionMs) }
        return plan(out, inc, config)
    }
}
