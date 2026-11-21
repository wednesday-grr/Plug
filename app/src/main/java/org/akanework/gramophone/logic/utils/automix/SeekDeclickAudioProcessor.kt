/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a short linear fade-in to the first frames after every flush (seek, loop restart,
 * stream start). This softens the onset discontinuity only; the abrupt end of audio that was
 * already queued before the flush is not affected.
 */
class SeekDeclickAudioProcessor(private val fadeMs: Int = DEFAULT_FADE_MS) : BaseAudioProcessor() {
    private var fadeFrames = 0
    private var framesDone = 0

    init {
        require(fadeMs in 1..50)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.sampleRate > 0 && inputAudioFormat.channelCount > 0 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        fadeFrames = if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) 0
        else (inputAudioFormat.sampleRate * fadeMs / 1000).coerceAtLeast(1)
        framesDone = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        if (framesDone >= fadeFrames) {
            output.put(inputBuffer).flip()
            return
        }
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        require(remaining % bytesPerFrame == 0) { "PCM input must contain complete frames" }
        val channels = inputAudioFormat.channelCount
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        while (input.hasRemaining()) {
            val gain = if (framesDone < fadeFrames) (framesDone + 1).toFloat() / fadeFrames else 1f
            repeat(channels) {
                if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
                    output.putShort((input.short * gain).toInt().toShort())
                } else {
                    output.putFloat(input.float * gain)
                }
            }
            framesDone++
        }
        output.flip()
    }

    companion object {
        const val DEFAULT_FADE_MS = 5
    }
}
