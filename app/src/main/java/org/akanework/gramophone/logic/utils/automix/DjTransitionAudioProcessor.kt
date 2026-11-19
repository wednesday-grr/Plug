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
package org.akanework.gramophone.logic.utils.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Per-deck stereo PCM biquad filter. Configuration may be changed from the playback thread. */
class DjTransitionAudioProcessor : BaseAudioProcessor() {
    enum class Type { HIGH_PASS, LOW_PASS }

    private data class Request(
        val type: Type?,
        val cutoffHz: Float,
        val intensity: Float,
        val version: Long,
        val stateVersion: Long,
    )

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    @Volatile
    private var request = Request(null, 0f, 0f, 0L, 0L)
    private var appliedVersion = -1L
    private var appliedStateVersion = -1L
    private var coefficients: Coefficients? = null
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)

    fun setFilter(type: Type, cutoffHz: Float, intensity: Float = 1f) {
        require(cutoffHz.isFinite() && cutoffHz > 0f)
        require(intensity.isFinite() && intensity in 0f..1f)
        val previous = request
        if (previous.type == type && previous.cutoffHz == cutoffHz &&
            previous.intensity == intensity
        ) return
        val stateVersion = if (previous.type != type || previous.intensity == 0f || intensity == 0f)
            previous.stateVersion + 1 else previous.stateVersion
        request = Request(type, cutoffHz, intensity, previous.version + 1, stateVersion)
    }

    fun bypass() {
        val previous = request
        if (previous.type == null && previous.intensity == 0f) return
        request = Request(null, 0f, 0f, previous.version + 1, previous.stateVersion + 1)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.sampleRate > 0 && inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        clearState()
        appliedVersion = -1L
        appliedStateVersion = -1L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val current = request
        if (current.version != appliedVersion) {
            if (current.stateVersion != appliedStateVersion) clearState()
            coefficients = if (current.type != null && current.intensity > 0f) {
                design(current.type, current.cutoffHz, inputAudioFormat.sampleRate)
            } else null
            appliedVersion = current.version
            appliedStateVersion = current.stateVersion
        }

        val output = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val filter = coefficients
        if (filter == null || current.intensity == 0f) {
            output.put(inputBuffer).flip()
            return
        }

        val input = inputBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        var channel = 0
        while (input.hasRemaining()) {
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val sample = input.short / 32768.0
                    val filtered = process(sample, channel, filter)
                    val mixed = sample + (filtered - sample) * current.intensity
                    val encoded = (mixed.coerceIn(-1.0, 1.0) * 32768.0)
                        .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    output.putShort(encoded.toShort())
                }
                C.ENCODING_PCM_FLOAT -> {
                    val sample = input.float.toDouble()
                    val filtered = process(sample, channel, filter)
                    output.putFloat((sample + (filtered - sample) * current.intensity).toFloat())
                }
                else -> error("Unsupported PCM encoding")
            }
            channel = channel xor 1
        }
        output.flip()
    }

    override fun onReset() {
        clearState()
        coefficients = null
        appliedVersion = -1L
        appliedStateVersion = -1L
    }

    private fun process(input: Double, channel: Int, filter: Coefficients): Double {
        val output = filter.b0 * input + z1[channel]
        z1[channel] = filter.b1 * input - filter.a1 * output + z2[channel]
        z2[channel] = filter.b2 * input - filter.a2 * output
        return output
    }

    private fun clearState() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    private fun design(type: Type, cutoffHz: Float, sampleRate: Int): Coefficients {
        val cutoff = cutoffHz.toDouble().coerceIn(1.0, sampleRate / 2.0 * 0.99)
        val omega = 2.0 * PI * cutoff / sampleRate
        val cosine = cos(omega)
        val alpha = sin(omega) / (2.0 / sqrt(2.0))
        val a0 = 1.0 + alpha
        val (b0, b1, b2) = when (type) {
            Type.LOW_PASS -> Triple((1.0 - cosine) / 2.0, 1.0 - cosine, (1.0 - cosine) / 2.0)
            Type.HIGH_PASS -> Triple((1.0 + cosine) / 2.0, -(1.0 + cosine), (1.0 + cosine) / 2.0)
        }
        return Coefficients(
            b0 / a0,
            b1 / a0,
            b2 / a0,
            -2.0 * cosine / a0,
            (1.0 - alpha) / a0,
        )
    }
}
