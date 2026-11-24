/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import com.siw.plug.logic.utils.automix.EssentiaNative
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assume.assumeFalse

class EssentiaNativeTest {
    @Test(expected = IllegalStateException::class)
    fun missingOptionalLibraryIsReported() {
        assumeFalse(BuildConfig.HAS_ESSENTIA)
        EssentiaNative.analyze(FloatArray(44_100 * 5), 0)
    }

    @Test
    fun parsesKeyAndConvertsSecondsToAbsoluteMilliseconds() {
        val result = EssentiaNative.parse(
            doubleArrayOf(120.0, 3.5, 0.0, 0.0, 0.8, 0.25, 0.75),
            20_000,
            44_100,
        )
        assertEquals(120.0, result.bpm, 0.0)
        assertEquals(3.5, result.rawConfidence, 0.0)
        assertEquals("8B", result.camelotKey)
        assertEquals(0.8, result.keyStrength, 0.0)
        assertEquals(listOf(20_250.0, 20_750.0), result.beatPositionsMs)
    }

    @Test
    fun convertsMinorKeyToCamelotNotation() {
        val result = EssentiaNative.parse(
            doubleArrayOf(120.0, 1.0, 9.0, 1.0, 0.6),
            0,
            44_100,
        )
        assertEquals("8A", result.camelotKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfWindowBeat() {
        EssentiaNative.parse(doubleArrayOf(120.0, 1.0, 0.0, 0.0, 0.5, 1.1), 0, 44_100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonIncreasingBeats() {
        EssentiaNative.parse(
            doubleArrayOf(120.0, 1.0, 0.0, 0.0, 0.5, 0.5, 0.5),
            0,
            44_100,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidKey() {
        EssentiaNative.parse(doubleArrayOf(120.0, 1.0, 12.0, 0.0, 0.5), 0, 44_100)
    }
}
