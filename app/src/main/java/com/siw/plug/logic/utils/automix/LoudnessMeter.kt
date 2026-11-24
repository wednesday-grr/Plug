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
package com.siw.plug.logic.utils.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/** Result of a loudness measurement; every field is explicit about what was and was not measured. */
data class MeasuredLoudness(
    /** Gated integrated loudness over the measured window, or null when everything was gated out. */
    val integratedLufs: Float?,
    /** Loudest 3 s window (short-term), or null when the window was shorter than 3 s. */
    val maxShortTermLufs: Float?,
    /** Sample peak, not an inter-sample true peak. */
    val samplePeakDbfs: Float?,
    val measuredSeconds: Float,
    /** Highest frequency the source material could contain: sampleRate / 2. */
    val bandwidthHz: Float,
) {
    /** Loudness measured on band-limited low-rate audio understates the K-weighted high shelf. */
    val bandLimited: Boolean get() = bandwidthHz < FULL_BAND_HZ

    companion object {
        const val FULL_BAND_HZ = 16000f
    }
}

/**
 * ITU-R BS.1770-4 integrated loudness (K-weighting, 400 ms blocks with 75% overlap, -70 LUFS absolute
 * gate and -10 LU relative gate). Channels are weighted 1.0, i.e. mono and stereo only.
 */
object LoudnessMeter {
    const val ALGORITHM_VERSION = "bs1770-4-window-v1"
    private const val OFFSET = -0.691
    private const val BLOCK_SECONDS = 0.4
    private const val ABSOLUTE_GATE = -70.0
    private const val RELATIVE_GATE_LU = -10.0
    private const val SHORT_TERM_BLOCKS = 28 // 28 blocks at a 100 ms hop span ~3.1 s

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

    private fun highShelf(fs: Double): Biquad {
        val f0 = 1681.974450955533
        val gain = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / fs)
        val vh = 10.0.pow(gain / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            (vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0
        )
    }

    private fun highPass(fs: Double): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(PI * f0 / fs)
        val a0 = 1.0 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private fun lufs(meanSquareSum: Double) = OFFSET + 10.0 * log10(meanSquareSum)

    /**
     * @param interleaved samples in [-1, 1]; non-finite samples are rejected rather than treated as zero.
     * @return null when the input is shorter than one 400 ms block or channel count is unsupported.
     */
    fun measure(interleaved: FloatArray, sampleRate: Int, channels: Int): MeasuredLoudness? {
        require(sampleRate > 0)
        if (channels !in 1..2) return null
        require(interleaved.all { it.isFinite() })
        val frames = interleaved.size / channels
        val block = (BLOCK_SECONDS * sampleRate).toInt()
        val step = block / 4
        if (frames < block || step == 0) return null
        val shelf = Array(channels) { highShelf(sampleRate.toDouble()) }
        val hp = Array(channels) { highPass(sampleRate.toDouble()) }
        val power = DoubleArray(frames)
        var peak = 0f
        for (frame in 0 until frames) {
            var sum = 0.0
            for (ch in 0 until channels) {
                val sample = interleaved[frame * channels + ch]
                peak = max(peak, abs(sample))
                val weighted = hp[ch].process(shelf[ch].process(sample.toDouble()))
                sum += weighted * weighted
            }
            power[frame] = sum
        }
        val prefix = DoubleArray(frames + 1)
        for (i in 0 until frames) prefix[i + 1] = prefix[i] + power[i]
        val blockPower = ArrayList<Double>()
        var start = 0
        while (start + block <= frames) {
            blockPower += (prefix[start + block] - prefix[start]) / block
            start += step
        }
        val absolute = blockPower.filter { it > 0.0 && lufs(it) > ABSOLUTE_GATE }
        val integrated = if (absolute.isEmpty()) null else {
            val relativeThreshold = lufs(absolute.average()) + RELATIVE_GATE_LU
            val gated = absolute.filter { lufs(it) > relativeThreshold }
            if (gated.isEmpty()) null else lufs(gated.average()).toFloat()
        }
        val shortTerm = if (blockPower.size >= SHORT_TERM_BLOCKS) {
            var best = Double.NEGATIVE_INFINITY
            var window = blockPower.take(SHORT_TERM_BLOCKS).sum()
            for (i in 0..blockPower.size - SHORT_TERM_BLOCKS) {
                if (i > 0) window += blockPower[i + SHORT_TERM_BLOCKS - 1] - blockPower[i - 1]
                best = max(best, window / SHORT_TERM_BLOCKS)
            }
            if (best > 0.0) lufs(best).toFloat() else null
        } else null
        return MeasuredLoudness(
            integrated, shortTerm,
            if (peak > 0f) (20.0 * log10(peak.toDouble())).toFloat() else null,
            frames.toFloat() / sampleRate, sampleRate / 2f
        )
    }
}
