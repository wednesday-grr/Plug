/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 wrapper around [AutomaticGainController]. Passes audio through untouched while disabled.
 * [setEnabled] may be called from any thread; the audio thread picks it up at the next buffer, and the
 * controller state is reset on every flush and on every enable so stale gain never carries over.
 */
class AgcAudioProcessor(private val config: AgcConfig = AgcConfig()) : BaseAudioProcessor() {
    @Volatile
    private var enabled = false
    private var wasEnabled = false
    private var controller: AutomaticGainController? = null
    private var scratch = FloatArray(0)

    /** Current gain in dB while enabled and processing; null otherwise. */
    @Volatile
    var currentGainDb: Float? = null
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.sampleRate > 0 && inputAudioFormat.channelCount > 0 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        controller = if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) null
        else AutomaticGainController(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, config)
        wasEnabled = false
        currentGainDb = null
    }

    override fun onReset() {
        controller = null
        scratch = FloatArray(0)
        currentGainDb = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val agc = controller
        val active = enabled && agc != null
        if (!active) {
            wasEnabled = false
            currentGainDb = null
            output.put(inputBuffer).flip()
            return
        }
        if (!wasEnabled) {
            agc!!.reset()
            wasEnabled = true
        }
        val is16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val samples = remaining / (if (is16) 2 else 4)
        if (scratch.size < samples) scratch = FloatArray(samples)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        for (i in 0 until samples) scratch[i] = if (is16) input.short / 32768f else input.float
        agc!!.process(scratch, 0, samples)
        for (i in 0 until samples) {
            if (is16) {
                output.putShort((scratch[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            } else output.putFloat(scratch[i])
        }
        currentGainDb = agc.currentGainDb
        output.flip()
    }
}
