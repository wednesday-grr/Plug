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
 * Media3 wrapper around [BassBoostFilter]. Setters may be called from any thread; the audio thread
 * rebuilds the filter only when the gain or frequency changes. Passes audio through untouched when idle.
 */
class BassBoostAudioProcessor : BaseAudioProcessor() {
    private data class Request(val gainDb: Int, val frequencyHz: Int)

    @Volatile
    private var request = Request(BassBoostSettings.DEFAULT_GAIN_DB, BassBoostSettings.DEFAULT_FREQUENCY_HZ)
    @Volatile
    private var targetMix = 0f
    private var applied: Request? = null
    private var filter: BassBoostFilter? = null
    private var scratch = FloatArray(0)

    /** Smoothed boost fraction currently applied; null if the filter is not running. */
    @Volatile
    var currentMix: Float? = null
        private set

    fun configureBoost(gainDb: Int, frequencyHz: Int) {
        require(gainDb in BassBoostSettings.GAIN_DB_RANGE && frequencyHz in BassBoostSettings.FREQUENCY_HZ_RANGE)
        request = Request(gainDb, frequencyHz)
    }

    /** 0 disables the boost (smoothly); 1 applies the configured gain. */
    fun setMix(value: Float) {
        require(value.isFinite() && value in 0f..1f)
        targetMix = value
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.sampleRate > 0 && inputAudioFormat.channelCount in 1..2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        filter = null
        applied = null
        currentMix = null
    }

    override fun onReset() {
        filter = null
        applied = null
        scratch = FloatArray(0)
        currentMix = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val wanted = targetMix
        var f = filter
        if (f == null && wanted == 0f) {
            output.put(inputBuffer).flip()
            return
        }
        val req = request
        if (f == null || applied != req) {
            f = BassBoostFilter(
                inputAudioFormat.sampleRate, inputAudioFormat.channelCount,
                BassBoostConfig(frequencyHz = req.frequencyHz.toFloat(), maxGainDb = req.gainDb.toFloat())
            )
            filter = f
            applied = req
        }
        f.setTargetMix(wanted)
        if (f.isIdle) {
            filter = null
            currentMix = null
            output.put(inputBuffer).flip()
            return
        }
        val is16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val samples = remaining / (if (is16) 2 else 4)
        if (scratch.size < samples) scratch = FloatArray(samples)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        for (i in 0 until samples) scratch[i] = if (is16) input.short / 32768f else input.float
        f.process(scratch, 0, samples)
        for (i in 0 until samples) {
            if (is16) {
                output.putShort((scratch[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            } else output.putFloat(scratch[i])
        }
        currentMix = f.currentMix
        output.flip()
    }
}
