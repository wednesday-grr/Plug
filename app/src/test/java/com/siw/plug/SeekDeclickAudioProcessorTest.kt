/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.siw.plug.logic.utils.automix.SeekDeclickAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SeekDeclickAudioProcessorTest {
    private val sampleRate = 48_000

    private fun processor(): SeekDeclickAudioProcessor {
        val p = SeekDeclickAudioProcessor(fadeMs = 5)
        p.configure(AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_FLOAT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        return p
    }

    private fun run(p: SeekDeclickAudioProcessor, signal: FloatArray): FloatArray {
        val input = ByteBuffer.allocateDirect(signal.size * 4).order(ByteOrder.nativeOrder())
        signal.forEach(input::putFloat)
        input.flip()
        p.queueInput(input)
        val out = p.output
        return FloatArray(out.remaining() / 4) { out.order(ByteOrder.nativeOrder()).float }
    }

    @Test
    fun rampsFirstFramesAfterFlushThenPassesThroughUnchanged() {
        val p = processor()
        val frames = 1000
        val out = run(p, FloatArray(frames * 2) { 1f })
        val fade = sampleRate * 5 / 1000
        assertEquals(1f / fade, out[0], 1e-6f)
        assertEquals(1f / fade, out[1], 1e-6f)
        for (i in 1 until fade) assertTrue(out[i * 2] > out[(i - 1) * 2])
        assertEquals(1f, out[(fade) * 2], 0f)
        assertEquals(1f, out[frames * 2 - 1], 0f)
    }

    @Test
    fun rampSpansBufferBoundariesAndRearmsOnNextFlush() {
        val p = processor()
        val first = run(p, FloatArray(100 * 2) { 1f })
        val second = run(p, FloatArray(100 * 2) { 1f })
        assertEquals(100f / 240, first[198], 1e-5f)
        assertEquals(101f / 240, second[0], 1e-5f)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(1f / 240, run(p, FloatArray(4) { 1f })[0], 1e-6f)
    }

    @Test
    fun removesOnsetStepOfFullScaleSignal() {
        val sig = DspTestSignals.sine(1000.0, sampleRate, 2400, 0.9f)
        val stereo = FloatArray(sig.size * 2) { sig[it / 2] }
        val shifted = FloatArray(stereo.size) { stereo[it] }
        val out = run(processor(), shifted)
        val left = FloatArray(out.size / 2) { out[it * 2] }
        assertTrue(DspTestSignals.maxStep(left) <= DspTestSignals.maxStep(sig) * 1.01f)
    }
}
