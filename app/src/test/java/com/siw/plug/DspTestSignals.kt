/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Real-PCM helpers for measuring DSP output in unit tests. */
object DspTestSignals {
    fun sine(frequencyHz: Double, sampleRate: Int, frames: Int, amplitude: Float = 1f): FloatArray =
        FloatArray(frames) { (amplitude * sin(2.0 * PI * frequencyHz * it / sampleRate)).toFloat() }

    fun rms(signal: FloatArray, from: Int = 0, to: Int = signal.size): Double {
        require(to > from)
        var sum = 0.0
        for (i in from until to) sum += signal[i].toDouble() * signal[i]
        return sqrt(sum / (to - from))
    }

    fun peak(signal: FloatArray): Float = signal.maxOfOrNull { abs(it) } ?: 0f

    fun dbfs(linear: Double): Double = 20.0 * log10(linear.coerceAtLeast(1e-12))

    /** Largest sample-to-sample step: a proxy for audible clicks. */
    fun maxStep(signal: FloatArray): Float {
        var max = 0f
        for (i in 1 until signal.size) max = maxOf(max, abs(signal[i] - signal[i - 1]))
        return max
    }

    fun applyGain(signal: FloatArray, gain: (Int) -> Float): FloatArray =
        FloatArray(signal.size) { signal[it] * gain(it) }

    /** Single-bin DFT magnitude (amplitude-normalised) at the given frequency. */
    fun magnitudeAt(signal: FloatArray, frequencyHz: Double, sampleRate: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in signal.indices) {
            val w = 2.0 * PI * frequencyHz * i / sampleRate
            re += signal[i] * kotlin.math.cos(w)
            im -= signal[i] * sin(w)
        }
        return 2.0 * sqrt(re * re + im * im) / signal.size
    }
}
