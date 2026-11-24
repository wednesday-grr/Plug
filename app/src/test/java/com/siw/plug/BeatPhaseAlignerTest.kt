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

package com.siw.plug

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.siw.plug.logic.utils.automix.BeatPhaseAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BeatPhaseAlignerTest {
    @Test
    fun detectsStereoEnergyTransient() {
        val samples = FloatArray(64)
        for (i in 32 until 40) samples[i] = 0.5f
        assertEquals(16, BeatPhaseAligner.findTransientFrame(samples, 2, 4))
    }

    @Test
    fun rejectsSilenceShortAndInvalidWindows() {
        assertNull(BeatPhaseAligner.findTransientFrame(FloatArray(64), 2, 4))
        assertNull(BeatPhaseAligner.findTransientFrame(FloatArray(8), 2, 4))
        val invalid = FloatArray(64)
        invalid[0] = Float.NaN
        assertNull(BeatPhaseAligner.findTransientFrame(invalid, 2, 4))
        assertThrows(IllegalArgumentException::class.java) {
            BeatPhaseAligner.findTransientFrame(FloatArray(3), 2, 1)
        }
    }

    @Test
    fun estimatesBeatPeriodAndPhaseFromTaggedBpm() {
        val metadata = Metadata(TextInformationFrame("TBPM", null, listOf("120")))
        val grid = BeatPhaseAligner.estimate(
            metadata, listOf(100_000, 600_000, 1_100_000, 1_600_000, 2_100_000)
        )!!
        assertEquals(500.0, grid.beatPeriodMs, 0.0001)
        assertEquals(100.0, grid.phaseOffsetMs, 0.0001)
        assertEquals(1f, grid.confidence, 0.0001f)
    }

    @Test
    fun computesDelayAtMatchedSpeed() {
        val outgoing = BeatPhaseAligner.estimate(
            120f, listOf(100_000, 600_000, 1_100_000, 1_600_000)
        )
        val incoming = BeatPhaseAligner.estimate(
            100f, listOf(60_000, 660_000, 1_260_000, 1_860_000)
        )
        assertNull(BeatPhaseAligner.align(120f, 100f, outgoing, incoming, 0.0, 0.0))
        val compatible = BeatPhaseAligner.estimate(
            120f, listOf(50_000, 550_000, 1_050_000, 1_550_000)
        )
        val alignment = BeatPhaseAligner.align(120f, 120f, outgoing, compatible, 200.0, 0.0)!!
        assertEquals(350.0, alignment.startDelayMs, 0.001)
        assertEquals(1f, alignment.incomingSpeed, 0.0001f)
    }

    @Test
    fun computesDelayWithDifferentTemposAndWrappedPhase() {
        val outgoing = BeatPhaseAligner.estimate(
            120f, listOf(490_000, 990_000, 1_490_000, 1_990_000)
        )
        val incoming = BeatPhaseAligner.estimate(
            100f * 1.1f, listOf(100_000, 645_455, 1_190_909, 1_736_364)
        )
        val alignment = BeatPhaseAligner.align(120f, 110f, outgoing, incoming, 480.0, 0.0)!!
        assertEquals(1.090909f, alignment.incomingSpeed, 0.00001f)
        assertEquals(418.33, alignment.startDelayMs, 0.05)
    }

    @Test
    fun rejectsWeakOrMissingPhaseAndInvalidTempos() {
        val confident = BeatPhaseAligner.estimate(
            120f, listOf(100_000, 600_000, 1_100_000, 1_600_000)
        )
        val scattered = BeatPhaseAligner.estimate(
            120f, listOf(0, 250_000, 500_000, 750_000, 1_000_000, 1_250_000)
        )!!
        assertNull(BeatPhaseAligner.estimate(null as Float?, listOf(0, 500_000, 1_000_000, 1_500_000)))
        assertNull(BeatPhaseAligner.estimate(120f, listOf(0, 500_000, 1_000_000)))
        assertNull(BeatPhaseAligner.estimate(Float.NaN, listOf(0, 500_000, 1_000_000, 1_500_000)))
        assertNull(BeatPhaseAligner.align(120f, 120f, confident, scattered, 0.0, 0.0))
        assertNull(BeatPhaseAligner.align(null, 120f, confident, confident, 0.0, 0.0))
    }
}
