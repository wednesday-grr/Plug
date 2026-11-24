/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Windowed-sinc conversion with a low-pass cutoff below the lower Nyquist frequency. */
internal object AnalysisPcmResampler {
    private const val TAPS = 32
    private const val PHASES = 1024

    suspend fun convert(samples: FloatArray, size: Int, sourceRate: Int, targetRate: Int): FloatArray {
        require(size in 1..samples.size && sourceRate in 8_000..192_000 && targetRate in 8_000..192_000)
        if (sourceRate == targetRate) return samples.copyOf(size)
        val outputSize = (size.toLong() * targetRate / sourceRate).toInt()
        require(outputSize > 0) { "PCM window is too short to resample" }
        val cutoff = 0.94 * min(1.0, targetRate.toDouble() / sourceRate)
        val coefficients = Array(PHASES) { phase ->
            val fraction = phase.toDouble() / PHASES
            val weights = DoubleArray(TAPS) { index ->
                val distance = index - (TAPS / 2 - 1) - fraction
                val x = PI * distance * cutoff
                val sinc = if (x == 0.0) 1.0 else sin(x) / x
                val window = 0.5 + 0.5 * cos(PI * distance / (TAPS / 2))
                if (kotlin.math.abs(distance) >= TAPS / 2) 0.0 else cutoff * sinc * window
            }
            val sum = weights.sum()
            DoubleArray(TAPS) { weights[it] / sum }
        }
        val result = FloatArray(outputSize)
        for (i in result.indices) {
            if (i % 8192 == 0) currentCoroutineContext().ensureActive()
            val position = i.toDouble() * sourceRate / targetRate
            val base = floor(position).toInt()
            val phase = ((position - base) * PHASES).toInt().coerceIn(0, PHASES - 1)
            val weights = coefficients[phase]
            var value = 0.0
            for (tap in 0 until TAPS) {
                val sourceIndex = (base + tap - (TAPS / 2 - 1)).coerceIn(0, size - 1)
                value += samples[sourceIndex] * weights[tap]
            }
            result[i] = value.toFloat()
        }
        return result
    }
}
