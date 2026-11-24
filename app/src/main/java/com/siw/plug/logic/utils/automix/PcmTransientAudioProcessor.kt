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

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An opt-in PCM tap. Notifications run on the audio playback thread and must not block it.
 * A transient is not necessarily a beat; consumers must establish phase confidence separately.
 */
class PcmTransientAudioProcessor(
    private val onTransient: (Transient) -> Unit,
) : BaseAudioProcessor() {
    data class Transient(val periodUid: Any?, val positionUs: Long)

    private var periodUid: Any? = null
    private var startPositionUs = 0L
    private var blockStartFrame = 0L
    private var windowFrames = 0
    private var samples = FloatArray(0)
    private var sampleCount = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.sampleRate > 0 && inputAudioFormat.channelCount > 0 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        samples.fill(0f)
        periodUid = streamMetadata.periodUid
        startPositionUs = streamMetadata.positionOffsetUs
        blockStartFrame = 0
        sampleCount = 0
        if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) {
            windowFrames = (inputAudioFormat.sampleRate / 100).coerceAtLeast(1)
            samples = FloatArray(windowFrames * 25 * inputAudioFormat.channelCount)
        } else {
            samples = FloatArray(0)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val source = inputBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        require(remaining % bytesPerFrame == 0) { "PCM input must contain complete frames" }
        val channels = inputAudioFormat.channelCount
        while (source.hasRemaining()) {
            samples[sampleCount++] = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> source.short / 32768f
                C.ENCODING_PCM_FLOAT -> source.float
                else -> error("Unsupported PCM encoding")
            }
            if (sampleCount == samples.size) {
                val frame = BeatPhaseAligner.findTransientFrame(samples, channels, windowFrames)
                if (frame != null) {
                    val positionUs = startPositionUs +
                        (blockStartFrame + frame) * C.MICROS_PER_SECOND / inputAudioFormat.sampleRate
                    onTransient(Transient(periodUid, positionUs))
                }
                blockStartFrame += samples.size / channels
                sampleCount = 0
            }
        }
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onReset() {
        periodUid = null
        samples.fill(0f)
        samples = FloatArray(0)
        sampleCount = 0
        blockStartFrame = 0
    }
}
