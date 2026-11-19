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
package org.akanework.gramophone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.akanework.gramophone.logic.utils.automix.DjTransitionAudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DjTransitionAudioProcessorTest {
    @Test
    fun bypassPreservesPcm16AndFloatStereo() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            val processor = configured(encoding)
            val input = if (encoding == C.ENCODING_PCM_16BIT) {
                ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).apply {
                    repeat(8) { putShort((it * 1000).toShort()) }
                }
            } else {
                ByteBuffer.allocate(32).order(ByteOrder.nativeOrder()).apply {
                    repeat(8) { putFloat(it / 8f) }
                }
            }
            val expected = input.array().copyOf()
            input.flip()
            processor.queueInput(input)
            val output = ByteArray(expected.size)
            processor.output.get(output)
            assertArrayEquals(expected, output)
        }
    }

    @Test
    fun lowPassAttenuatesHighFrequencyAndBypassResetsToExactPcm() {
        val processor = configured(C.ENCODING_PCM_FLOAT)
        processor.setFilter(DjTransitionAudioProcessor.Type.LOW_PASS, 1000f)
        val input = sineBuffer(10_000.0, 48_000, 4096)
        val original = input.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val outputSamples = FloatArray(output.remaining() / 4) { output.float }
        val inputSamples = FloatArray(original.remaining() / 4) { original.float }
        assertTrue(rms(outputSamples.drop(100).toFloatArray()) < rms(inputSamples.drop(100).toFloatArray()) * 0.3)

        processor.bypass()
        val bypassInput = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder()).apply {
            repeat(16) { putFloat((it - 8) / 8f) }
            flip()
        }
        val expected = bypassInput.asReadOnlyBuffer().apply { position(0) }.let {
            ByteArray(it.remaining()).also(it::get)
        }
        processor.queueInput(bypassInput)
        val bypassOutput = ByteArray(expected.size)
        processor.output.get(bypassOutput)
        assertArrayEquals(expected, bypassOutput)
    }

    @Test
    fun bassGainAttenuatesOnlyFrequenciesBelowCrossover() {
        val processor = configured(C.ENCODING_PCM_FLOAT)
        processor.setBassGain(0f)

        val lowInput = sineBuffer(80.0, 48_000, 8192)
        processor.queueInput(lowInput)
        val lowOutput = processor.output.order(ByteOrder.nativeOrder())
        val lowSamples = FloatArray(lowOutput.remaining() / 4) { lowOutput.float }
        assertTrue(rms(lowSamples.drop(1000).toFloatArray()) < 0.15)

        processor.bypass()
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.setBassGain(0f)
        val highInput = sineBuffer(10_000.0, 48_000, 8192)
        processor.queueInput(highInput)
        val highOutput = processor.output.order(ByteOrder.nativeOrder())
        val highSamples = FloatArray(highOutput.remaining() / 4) { highOutput.float }
        assertTrue(rms(highSamples.drop(1000).toFloatArray()) > 0.6)
    }

    @Test
    fun echoDelaysStereoSignalAndDampsFeedbackRepeats() {
        val processor = configured(C.ENCODING_PCM_FLOAT)
        processor.setEcho(delayMs = 100f, wetMix = 1f, dryGain = 0f)
        val input = ByteBuffer.allocate(12_000 * 2 * 4).order(ByteOrder.nativeOrder())
        repeat(12_000) { frame ->
            input.putFloat(if (frame == 0) 1f else 0f)
            input.putFloat(if (frame == 0) 0.5f else 0f)
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val left = FloatArray(12_000)
        val right = FloatArray(12_000)
        repeat(12_000) { frame ->
            left[frame] = output.float
            right[frame] = output.float
        }

        assertTrue(kotlin.math.abs(left[0]) < 0.001f)
        assertTrue(left[4_800] > 0.9f)
        assertTrue(right[4_800] in 0.45f..0.55f)
        assertTrue(left[9_600] in 0.05f..0.45f)

        processor.bypass()
        val dryInput = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).apply {
            repeat(4) { putFloat(0.25f) }
            flip()
        }
        val expected = ByteArray(dryInput.remaining()).also {
            dryInput.asReadOnlyBuffer().get(it)
        }
        processor.queueInput(dryInput)
        val dryOutput = ByteArray(expected.size)
        processor.output.get(dryOutput)
        assertArrayEquals(expected, dryOutput)
    }

    @Test
    fun vocalAttenuationKeepsBassAndStereoSideWhileRemovingMidrangeVocals() {
        val processor = configured(C.ENCODING_PCM_FLOAT)
        processor.setVocalAttenuation(1f)

        fun processTone(frequency: Double, oppositeChannels: Boolean): FloatArray {
            processor.bypass()
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.setVocalAttenuation(1f)
            val input = ByteBuffer.allocate(16_384 * 2 * 4).order(ByteOrder.nativeOrder())
            repeat(16_384) { frame ->
                val value = sin(2.0 * PI * frequency * frame / 48_000).toFloat()
                input.putFloat(value)
                input.putFloat(if (oppositeChannels) -value else value)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.nativeOrder())
            val samples = FloatArray(output.remaining() / 4) { output.float }
            return samples.drop(2000).toFloatArray()
        }

        assertTrue(rms(processTone(100.0, false)) > 0.6)
        assertTrue(rms(processTone(200.0, false)) > 0.35)
        val midRms = rms(processTone(1000.0, false))
        assertTrue("Unexpected vocal-band residual: $midRms", midRms < 0.2)
        assertTrue(rms(processTone(1000.0, true)) > 0.6)
    }

    private fun configured(encoding: Int): DjTransitionAudioProcessor {
        val processor = DjTransitionAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, encoding))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        return processor
    }

    private fun sineBuffer(frequencyHz: Double, sampleRate: Int, frames: Int): ByteBuffer =
        ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { frame ->
                val value = sin(2.0 * PI * frequencyHz * frame / sampleRate).toFloat()
                putFloat(value)
                putFloat(value)
            }
            flip()
        }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
