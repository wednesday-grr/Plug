/*
 *     Copyright (C) 2026 Gramophone contributors
 *
 *     This file is part of Gramophone.
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

data class AgcConfig(
    val targetDbfs: Float = -20f,
    val maxGainDb: Float = 9f,
    val maxAttenuationDb: Float = 9f,
    /** Time for gain to close ~63% toward a lower target (reducing level). */
    val attackMs: Float = 50f,
    /** Time for gain to open ~63% toward a higher target (restoring level); slow to avoid pumping. */
    val releaseMs: Float = 800f,
    /** Signals below this detector level are treated as noise/silence and are never boosted. */
    val noiseThresholdDbfs: Float = -55f,
    /** Instantaneous sample-peak ceiling (linear) enforced regardless of the smoothed gain. */
    val peakCeiling: Float = 0.98f,
) {
    init {
        require(targetDbfs in -40f..-6f)
        require(maxGainDb in 0f..24f && maxAttenuationDb in 0f..24f)
        require(attackMs in 1f..1000f && releaseMs in 10f..10000f && releaseMs >= attackMs)
        require(noiseThresholdDbfs in -90f..-30f && noiseThresholdDbfs < targetDbfs)
        require(peakCeiling in 0.1f..1f)
    }
}

/**
 * Real-time-safe adaptive gain controller: no allocation or locking in [process]. It works on interleaved
 * float samples, one gain per frame. It does not own ReplayGain or loudness matching gain; see
 * [AutoMixEffectCompatibilityEvaluator] for the exclusion rules.
 */
class AutomaticGainController(private val sampleRate: Int, private val channels: Int, config: AgcConfig = AgcConfig()) {
    init {
        require(sampleRate > 0 && channels > 0)
    }

    @Volatile
    var config: AgcConfig = config
        set(value) {
            field = value
            updateCoefficients()
        }

    private var detectorCoef = 0.0
    private var attackCoef = 0.0
    private var releaseCoef = 0.0
    private var meanSquare = 0.0
    private var gainDb = 0.0

    /** Current applied gain in dB, for telemetry. */
    val currentGainDb: Float get() = gainDb.toFloat()

    init {
        updateCoefficients()
    }

    private fun coefficient(ms: Float) = 1.0 - exp(-1.0 / (ms / 1000.0 * sampleRate))

    private fun updateCoefficients() {
        val c = config
        detectorCoef = coefficient(DETECTOR_MS)
        attackCoef = coefficient(c.attackMs)
        releaseCoef = coefficient(c.releaseMs)
    }

    fun reset() {
        meanSquare = 0.0
        gainDb = 0.0
    }

    fun process(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        val c = config
        val frames = length / channels
        for (frame in 0 until frames) {
            val base = offset + frame * channels
            var sum = 0.0
            var peak = 0f
            for (ch in 0 until channels) {
                val s = samples[base + ch]
                sum += s.toDouble() * s
                peak = max(peak, abs(s))
            }
            meanSquare += detectorCoef * (sum / channels - meanSquare)
            val levelDb = if (meanSquare > MIN_POWER) 10.0 * log10(meanSquare) else -120.0
            val desiredDb = if (levelDb < c.noiseThresholdDbfs) 0.0 else {
                (c.targetDbfs - levelDb).coerceIn(-c.maxAttenuationDb.toDouble(), c.maxGainDb.toDouble())
            }
            val coef = if (desiredDb < gainDb) attackCoef else releaseCoef
            gainDb += coef * (desiredDb - gainDb)
            var gain = 10.0.pow(gainDb / 20.0)
            if (peak * gain > c.peakCeiling) gain = (c.peakCeiling / peak).toDouble()
            for (ch in 0 until channels) samples[base + ch] = (samples[base + ch] * gain).toFloat()
        }
    }

    private companion object {
        const val DETECTOR_MS = 100f
        const val MIN_POWER = 1e-12
    }
}
