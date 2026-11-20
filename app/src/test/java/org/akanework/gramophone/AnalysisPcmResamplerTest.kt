/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.utils.automix.AnalysisPcmResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AnalysisPcmResamplerTest {
    @Test
    fun preservesDurationAndDcAcrossRates() = runBlocking {
        for (rate in listOf(32_000, 44_100, 48_000, 96_000)) {
            val input = FloatArray(rate) { 0.5f }
            val output = AnalysisPcmResampler.convert(input, input.size, rate, 44_100)
            assertEquals(44_100, output.size)
            assertTrue(output.all { kotlin.math.abs(it - 0.5f) < 0.0001f })
        }
    }

    @Test
    fun attenuatesFrequenciesAboveTargetNyquist() = runBlocking {
        val rate = 96_000
        val input = FloatArray(rate) { sin(2 * PI * 30_000 * it / rate).toFloat() }
        val output = AnalysisPcmResampler.convert(input, input.size, rate, 44_100)
        val rms = sqrt(output.drop(100).dropLast(100).map { it * it }.average())
        assertTrue("out-of-band RMS was $rms", rms < 0.01)
    }
}
