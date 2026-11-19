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

import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.utils.automix.TrackDspAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TrackDspAnalyzerTest {
    @Test
    fun silenceDoesNotInventTempoOrKey() = runBlocking {
        val result = TrackDspAnalyzer.analyze(FloatArray(TrackDspAnalyzer.SAMPLE_RATE * 5), 10_000)
        assertNull(result.bpm)
        assertNull(result.camelotKey)
        assertNull(result.beatOffsetMs)
        assertEquals(10_000L, result.windowStartMs)
    }

    @Test
    fun findsPulseTrainTempoAndReportsAbsoluteWindowPhase() = runBlocking {
        val rate = TrackDspAnalyzer.SAMPLE_RATE
        val samples = FloatArray(rate * 20) { index ->
            val seconds = index.toDouble() / rate
            val phase = (seconds - 0.1) % 0.5
            if (phase >= 0 && phase < 0.025) (0.8 * sin(2 * PI * 1000 * seconds)).toFloat() else 0f
        }
        val result = TrackDspAnalyzer.analyze(samples, 30_000)
        assertEquals(120f, result.bpm!!, 4f)
        val error = ((result.beatOffsetMs!! - 30_100.0 + 250.0) % 500.0 + 500.0) % 500.0 - 250.0
        assertTrue("Phase error was $error ms", abs(error) < 70.0)
        assertTrue(result.confidence > 0f)
    }

    @Test
    fun detectsTemposAcrossRequestedRange() = runBlocking {
        val rate = TrackDspAnalyzer.SAMPLE_RATE
        for (bpm in listOf(80.0, 100.0, 140.0, 175.0)) {
            val period = 60.0 / bpm
            val samples = FloatArray(rate * 20) { index ->
                val seconds = index.toDouble() / rate
                val phase = seconds % period
                if (phase < 0.02) (0.8 * sin(2 * PI * 800 * seconds)).toFloat() else 0f
            }
            val result = TrackDspAnalyzer.analyze(samples, 0)
            assertEquals("Tempo $bpm", bpm.toFloat(), result.bpm!!, 5f)
        }
    }

    @Test
    fun detectsCMajorTriad() = runBlocking {
        val rate = TrackDspAnalyzer.SAMPLE_RATE
        val frequencies = doubleArrayOf(261.6256, 329.6276, 391.9954)
        val samples = FloatArray(rate * 10) { i ->
            frequencies.sumOf { sin(2 * PI * it * i / rate) }.toFloat() / 4f
        }
        assertEquals("8B", TrackDspAnalyzer.analyze(samples, 0).camelotKey)
    }

    @Test
    fun estimatesEnergyInVocalFrequencyBand() = runBlocking {
        val rate = TrackDspAnalyzer.SAMPLE_RATE
        val vocal = FloatArray(rate * 6) { index ->
            sin(2 * PI * 1000 * index / rate).toFloat()
        }
        val bass = FloatArray(rate * 6) { index ->
            sin(2 * PI * 100 * index / rate).toFloat()
        }

        assertTrue(TrackDspAnalyzer.analyze(vocal, 0).vocalEnergy!! > 0.9f)
        assertTrue(TrackDspAnalyzer.analyze(bass, 0).vocalEnergy!! < 0.1f)
    }
}
