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
