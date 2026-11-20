/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.automix.PcmTimestampContinuity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTimestampContinuityTest {
    @Test
    fun acceptsSeekTrimmedInitialAacBuffer() {
        assertTrue(PcmTimestampContinuity.canDiscardInitialBuffer(
            35_760_589, 35_781_950, 44_100, 2112, 82, 35_712_471, true))
    }

    @Test
    fun rejectsOrdinaryGapsAndInvalidStartupConditions() {
        fun allowed(actual: Long = 1_021_361, delay: Int = 2112, size: Int = 82,
                    seek: Long = 5_000_000, first: Boolean = true) =
            PcmTimestampContinuity.canDiscardInitialBuffer(1_000_000, actual, 44_100, delay, size, seek, first)
        assertFalse(allowed(first = false))
        assertFalse(allowed(delay = 0))
        assertFalse(allowed(seek = 0))
        assertFalse(allowed(size = 2113))
        assertFalse(allowed(actual = 1_100_000))
        assertFalse(allowed(actual = 978_000))
        assertFalse(allowed(actual = 1_020_000))
    }
}
