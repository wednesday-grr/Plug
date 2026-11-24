/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** User-facing Bass Boost parameters with validated ranges. */
data class BassBoostSettings(
    val enabled: Boolean = false,
    val gainDb: Int = DEFAULT_GAIN_DB,
    val frequencyHz: Int = DEFAULT_FREQUENCY_HZ,
) {
    init {
        require(gainDb in GAIN_DB_RANGE)
        require(frequencyHz in FREQUENCY_HZ_RANGE)
    }

    companion object {
        const val DEFAULT_GAIN_DB = 4
        const val DEFAULT_FREQUENCY_HZ = 100
        val GAIN_DB_RANGE = 0..12
        val FREQUENCY_HZ_RANGE = 40..250
        val OFF = BassBoostSettings()

        fun coerced(enabled: Boolean, gainDb: Int, frequencyHz: Int) = BassBoostSettings(
            enabled, gainDb.coerceIn(GAIN_DB_RANGE), frequencyHz.coerceIn(FREQUENCY_HZ_RANGE)
        )
    }
}

data class BassBoostConfig(
    val frequencyHz: Float = BassBoostSettings.DEFAULT_FREQUENCY_HZ.toFloat(),
    val maxGainDb: Float = BassBoostSettings.DEFAULT_GAIN_DB.toFloat(),
    /** Shelf quality factor; 0.707 is maximally flat without resonance. */
    val q: Float = 0.707f,
    /** Linear sample-peak ceiling enforced after the boost. */
    val ceiling: Float = 0.98f,
    val mixSmoothingSeconds: Float = 0.02f,
    val limiterReleaseSeconds: Float = 0.15f,
) {
    init {
        require(frequencyHz in 20f..1000f)
        require(maxGainDb in 0f..18f)
        require(q in 0.3f..2f)
        require(ceiling in 0.1f..1f)
    }
}

/**
 * Low-shelf bass enhancement. The filter output is `x + (shelf(x) - x) * mix`, so a smoothed `mix`
 * in 0..1 interpolates the low-frequency gain smoothly between 0 dB and the configured maximum
 * without recomputing coefficients per sample. A peak limiter with instant attack enforces
 * [BassBoostConfig.ceiling] only when the boost would overshoot; otherwise it is transparent.
 * Not thread-safe: use from one (audio) thread.
 */
class BassBoostFilter(
    private val sampleRate: Int,
    private val channels: Int,
    val config: BassBoostConfig = BassBoostConfig(),
) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)
    private val mixCoefficient = 1.0 - exp(-1.0 / (config.mixSmoothingSeconds * sampleRate))
    private val releaseCoefficient = 1.0 - exp(-1.0 / (config.limiterReleaseSeconds * sampleRate))
    private var mix = 0.0
    private var targetMix = 0.0
    private var limiterGain = 1.0

    init {
        require(sampleRate > 0 && channels > 0)
        require(config.frequencyHz < sampleRate / 2f)
        // RBJ cookbook low shelf (slope derived from Q).
        val a = 10.0.pow(config.maxGainDb / 40.0)
        val w0 = 2.0 * PI * config.frequencyHz / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * config.q)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
        b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * cosW) / a0
        b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha) / a0
        a1 = -2 * ((a - 1) + (a + 1) * cosW) / a0
        a2 = ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha) / a0
    }

    /** Fraction (0..1) of the configured boost to apply; reached smoothly. */
    fun setTargetMix(value: Float) {
        require(value.isFinite())
        targetMix = value.toDouble().coerceIn(0.0, 1.0)
    }

    val currentMix: Float get() = mix.toFloat()

    /** True when the filter is fully off and would pass audio through unchanged. */
    val isIdle: Boolean get() = targetMix == 0.0 && mix < IDLE_MIX && limiterGain >= 0.9999

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
        mix = 0.0
        limiterGain = 1.0
    }

    /** Processes interleaved float samples in place. */
    fun process(samples: FloatArray, offset: Int, count: Int) {
        require(count % channels == 0)
        var i = offset
        val end = offset + count
        while (i < end) {
            mix += (targetMix - mix) * mixCoefficient
            if (targetMix == 0.0 && mix < IDLE_MIX) mix = 0.0
            var peak = 0.0
            for (c in 0 until channels) {
                val x = samples[i + c].toDouble()
                val y = b0 * x + z1[c]
                z1[c] = b1 * x - a1 * y + z2[c]
                z2[c] = b2 * x - a2 * y
                val out = x + (y - x) * mix
                samples[i + c] = out.toFloat()
                peak = maxOf(peak, abs(out))
            }
            val needed = if (peak > config.ceiling) config.ceiling / peak else 1.0
            limiterGain = minOf(needed, limiterGain + (1.0 - limiterGain) * releaseCoefficient)
            if (limiterGain < 1.0) {
                for (c in 0 until channels) samples[i + c] = (samples[i + c] * limiterGain).toFloat()
            }
            i += channels
        }
    }

    /** Linear low-frequency magnitude response at [frequencyHz] for the current [currentMix]. */
    fun magnitudeAt(frequencyHz: Double, mixValue: Double = mix): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cr = cos(w)
        val ci = -sin(w)
        val c2r = cos(2 * w)
        val c2i = -sin(2 * w)
        val nr = b0 + b1 * cr + b2 * c2r
        val ni = b1 * ci + b2 * c2i
        val dr = 1 + a1 * cr + a2 * c2r
        val di = a1 * ci + a2 * c2i
        val den = dr * dr + di * di
        val hr = (nr * dr + ni * di) / den
        val hi = (ni * dr - nr * di) / den
        val outR = 1.0 + (hr - 1.0) * mixValue
        val outI = hi * mixValue
        return sqrt(outR * outR + outI * outI)
    }

    private companion object {
        const val IDLE_MIX = 1e-4
    }
}
