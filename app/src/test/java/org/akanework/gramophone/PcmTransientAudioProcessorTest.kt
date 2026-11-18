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
import org.akanework.gramophone.logic.utils.automix.PcmTransientAudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTransientAudioProcessorTest {
    @Test
    fun preservesPcmAndReportsStreamRelativeTransientAcrossBuffers() {
        val events = mutableListOf<PcmTransientAudioProcessor.Transient>()
        val processor = PcmTransientAudioProcessor(events::add)
        val format = AudioProcessor.AudioFormat(1000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        val uid = Any()
        processor.flush(
            AudioProcessor.StreamMetadata.Builder()
                .setPeriodUid(uid)
                .setPositionOffsetUs(2_000_000)
                .build()
        )
        val input = ByteBuffer.allocate(250 * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(250) { frame ->
            repeat(2) { input.putShort(if (frame in 100..109) 16384 else 0) }
        }
        val original = input.array().copyOf()
        input.flip()
        val output = ByteArray(original.size)
        input.limit(150 * 4)
        processor.queueInput(input)
        processor.output.get(output, 0, 150 * 4)
        assertEquals(0, events.size)
        input.limit(original.size)
        processor.queueInput(input)
        processor.output.get(output, 150 * 4, 100 * 4)

        assertArrayEquals(original, output)
        assertEquals(listOf(PcmTransientAudioProcessor.Transient(uid, 2_100_000)), events)
    }

    @Test
    fun flushDiscardsPartialWindowsAndHandlesFloatPcm() {
        val events = mutableListOf<PcmTransientAudioProcessor.Transient>()
        val processor = PcmTransientAudioProcessor(events::add)
        processor.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_FLOAT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val partial = ByteBuffer.allocate(100 * 4).order(ByteOrder.nativeOrder())
        repeat(100) { partial.putFloat(if (it in 50..59) 0.5f else 0f) }
        partial.flip()
        processor.queueInput(partial)
        processor.output

        processor.flush(
            AudioProcessor.StreamMetadata.Builder().setPositionOffsetUs(5_000_000).build()
        )
        val floatPcm = ByteBuffer.allocate(250 * 4).order(ByteOrder.nativeOrder())
        repeat(250) { floatPcm.putFloat(if (it in 200..209) 0.5f else 0f) }
        floatPcm.flip()
        processor.queueInput(floatPcm)
        assertEquals(listOf(PcmTransientAudioProcessor.Transient(null, 5_200_000)), events)
    }

    @Test
    fun unsupportedEncodingBypassesCapture() {
        val processor = PcmTransientAudioProcessor { error("unexpected transient") }
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_24BIT))
        )
        assertFalse(processor.isActive)
    }
}
