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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DjTransitionAudioProcessorTest {
    @Test
    fun transitionEqIndependentlyAttenuatesAllThreeBands() {
        fun measure(frequency: Double, low: Float, mid: Float, high: Float): Double {
            val processor = configured(C.ENCODING_PCM_FLOAT)
            processor.setTransitionEq(low, mid, high)
            processor.queueInput(sineBuffer(frequency, 48_000, 16_384))
            val output = processor.output.order(ByteOrder.nativeOrder())
            val samples = FloatArray(output.remaining() / 4) { output.float }
            return rms(samples.drop(4000).toFloatArray())
        }
        assertTrue(measure(80.0, 0f, 1f, 1f) < 0.35)
        assertTrue(measure(1000.0, 1f, 0f, 1f) < 0.3)
        assertTrue(measure(10_000.0, 1f, 1f, 0f) < 0.35)
        assertTrue(measure(10_000.0, 0f, 1f, 1f) > 0.6)
        assertTrue(measure(80.0, 1f, 1f, 0f) > 0.6)
    }

    @Test
    fun eqChangesAreSmoothedAndCoexistWithOtherEffects() {
        val processor = configured(C.ENCODING_PCM_FLOAT)
        processor.setTransitionEq(0f, 0f, 0f)
        processor.setBassGain(0.7f)
        processor.setFilter(DjTransitionAudioProcessor.Type.LOW_PASS, 8000f)
        processor.setEcho(100f, wetMix = 0.2f)
        processor.setReverb(0.2f)
        processor.queueInput(sineBuffer(1000.0, 48_000, 16_384))
        val output = processor.output.order(ByteOrder.nativeOrder())
        while (output.hasRemaining()) assertTrue(output.float in -1f..1f)
        processor.bypass()
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val dry = sineBuffer(1000.0, 48_000, 1000)
        val expected = ByteArray(dry.remaining()).also { dry.asReadOnlyBuffer().get(it) }
        processor.queueInput(dry)
        val actual = ByteArray(expected.size)
        processor.output.get(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun filterBassEchoAndReverbComposeAndBypassClearsThem() {
        val processor = configured(C.ENCODING_PCM_FLOAT)
        processor.setFilter(DjTransitionAudioProcessor.Type.LOW_PASS, 3000f)
        processor.setBassGain(0.5f)
        processor.setVocalAttenuation(0.3f)
        processor.setEcho(delayMs = 100f, feedback = 0.4f, wetMix = 0.4f)
        processor.setReverb(0.4f)
        processor.setOutputGain(0.8f)
        val input = sineBuffer(1000.0, 48_000, 12_000)
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var energy = 0.0
        while (output.hasRemaining()) {
            val sample = output.float
            assertTrue(sample.isFinite() && sample in -1f..1f)
            energy += kotlin.math.abs(sample)
        }
        assertTrue(energy > 0.1)
        processor.bypass()
        val dry = sineBuffer(1000.0, 48_000, 1000)
        val expected = ByteArray(dry.remaining()).also { dry.asReadOnlyBuffer().get(it) }
        processor.queueInput(dry)
        val actual = ByteArray(expected.size)
        processor.output.get(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun reverbProducesDecayingStereoTailAndBypassClearsIt() {
        val processor = configured(C.ENCODING_PCM_FLOAT)
        processor.setReverb(1f)
        val input = ByteBuffer.allocate(12_000 * 8).order(ByteOrder.nativeOrder())
        repeat(12_000) { frame ->
            input.putFloat(if (frame == 0) 1f else 0f)
            input.putFloat(if (frame == 0) 0.5f else 0f)
        }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var tailEnergy = 0.0
        repeat(12_000) {
            tailEnergy += kotlin.math.abs(output.float)
            tailEnergy += kotlin.math.abs(output.float)
        }
        assertTrue(tailEnergy > 1.0)
        processor.bypass()
        val silence = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder())
        processor.queueInput(silence)
        val bypassed = processor.output
        while (bypassed.hasRemaining()) assertEquals(0, bypassed.get().toInt())
    }

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
    fun pcmMixingModeConvertsFloatStereoToPcm16AndAppliesOverlapHeadroom() {
        val processor = DjTransitionAudioProcessor(forcePcm16Output = true)
        val inputFormat = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(
            AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT),
            processor.configure(inputFormat)
        )
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.setOutputGain(0.5f)
        val input = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).apply {
            putFloat(0.8f)
            putFloat(-0.8f)
            putFloat(0.4f)
            putFloat(-0.4f)
            flip()
        }

        processor.queueInput(input)
        assertEquals(input.limit(), input.position())
        val output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(4, output.remaining() / Short.SIZE_BYTES)
        assertTrue(kotlin.math.abs(output.short / 32768f - 0.4f) < 0.001f)
        assertTrue(kotlin.math.abs(output.short / 32768f + 0.4f) < 0.001f)
        assertTrue(kotlin.math.abs(output.short / 32768f - 0.2f) < 0.001f)
        assertTrue(kotlin.math.abs(output.short / 32768f + 0.2f) < 0.001f)
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
    fun flushClearsEchoDelayLineBeforeNewTrackAudio() {
        val processor = configured(C.ENCODING_PCM_FLOAT)
        processor.setEcho(delayMs = 100f, wetMix = 1f, dryGain = 0f)
        val impulse = ByteBuffer.allocate(1000 * 2 * 4).order(ByteOrder.nativeOrder()).apply {
            putFloat(1f)
            putFloat(1f)
            repeat(1998) { putFloat(0f) }
            flip()
        }
        processor.queueInput(impulse)
        processor.output.position(processor.output.limit())

        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val silence = ByteBuffer.allocate(5000 * 2 * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(10_000) { putFloat(0f) }
            flip()
        }
        processor.queueInput(silence)
        val output = processor.output.order(ByteOrder.nativeOrder())
        while (output.hasRemaining()) assertTrue(kotlin.math.abs(output.float) < 0.0001f)
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
