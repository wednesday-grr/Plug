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

import com.siw.plug.logic.utils.automix.AutomixMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.sqrt

class AutomixMathTest {
    @Test
    fun automaticDurationUsesEightBarsAndCapsAtThirtySeconds() {
        assertEquals(16_000L, AutomixMath.automaticDurationMs(120f))
        assertEquals(30_000L, AutomixMath.automaticDurationMs(30f))
        assertEquals(6_400L, AutomixMath.automaticDurationMs(300f))
        assertEquals(5_000L, AutomixMath.automaticDurationMs(null))
        assertEquals(5_000L, AutomixMath.automaticDurationMs(Float.NaN))
    }
    @Test
    fun equalPowerPreservesSquaredAmplitude() {
        for (i in 0..100) {
            val volumes = AutomixMath.equalPower(i / 100f)
            assertEquals(1f, volumes.outgoing * volumes.outgoing + volumes.incoming * volumes.incoming, 0.00001f)
        }
        assertEquals(1f, AutomixMath.equalPower(-1f).outgoing, 0f)
        assertEquals(0f, AutomixMath.equalPower(1f).outgoing, 0.000001f)
        assertEquals(sqrt(0.5f), AutomixMath.equalPower(0.5f).incoming, 0.000001f)
        assertThrows(IllegalArgumentException::class.java) { AutomixMath.equalPower(Float.NaN) }
    }

    @Test
    fun overlapHeadroomIsSmoothAndReturnsToUnityAtBothEnds() {
        assertEquals(1f, AutomixMath.overlapHeadroom(0f), 0f)
        assertEquals(0.85f, AutomixMath.overlapHeadroom(0.5f), 0.000001f)
        assertEquals(1f, AutomixMath.overlapHeadroom(1f), 0.000001f)
        assertEquals(1f, AutomixMath.overlapHeadroom(-1f), 0f)
        assertEquals(1f, AutomixMath.overlapHeadroom(2f), 0.000001f)
        assertThrows(IllegalArgumentException::class.java) {
            AutomixMath.overlapHeadroom(Float.NaN)
        }
    }

    @Test
    fun matchesOnlyValidTemposWithinThreshold() {
        assertEquals(1.1f, AutomixMath.tempoRatio(110f, 100f)!!, 0.000001f)
        assertEquals(0.9f, AutomixMath.tempoRatio(90f, 100f)!!, 0.000001f)
        assertEquals(1.15f, AutomixMath.tempoRatio(115f, 100f)!!, 0.000001f)
        assertEquals(0.85f, AutomixMath.tempoRatio(85f, 100f)!!, 0.000001f)
        assertNull(AutomixMath.tempoRatio(null, 100f))
        assertNull(AutomixMath.tempoRatio(Float.NaN, 100f))
        assertNull(AutomixMath.tempoRatio(100f, 0f))
        assertNull(AutomixMath.tempoRatio(116f, 100f))
        assertNull(AutomixMath.tempoRatio(84f, 100f))
        assertThrows(IllegalArgumentException::class.java) {
            AutomixMath.tempoRatio(100f, 100f, Float.NaN)
        }
    }

    @Test
    fun rampsBackDuringSecondHalf() {
        assertEquals(1.1f, AutomixMath.rampSpeed(1.1f, 0.5f), 0.000001f)
        assertEquals(1.05f, AutomixMath.rampSpeed(1.1f, 0.75f), 0.000001f)
        assertEquals(1f, AutomixMath.rampSpeed(1.1f, 1f), 0.000001f)
    }
}
