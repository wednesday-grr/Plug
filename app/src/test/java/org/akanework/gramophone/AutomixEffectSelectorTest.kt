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

import org.akanework.gramophone.logic.utils.automix.AutomixEffectSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomixEffectSelectorTest {
    @Test
    fun choosesEffectFromTempoPhaseAndVocalEnergy() {
        assertEquals(
            "vocal_swap",
            AutomixEffectSelector.select(120f, 124f, true, 0.8f, 0.7f)
        )
        assertEquals(
            "bass_swap",
            AutomixEffectSelector.select(120f, 124f, true, 0.8f, 0.2f)
        )
        assertEquals(
            "rise",
            AutomixEffectSelector.select(120f, 130f, true, 0.8f, 0.9f)
        )
        assertEquals(
            "fade",
            AutomixEffectSelector.select(120f, 140f, true, 0.8f, 0.9f)
        )
        assertEquals(
            "fade",
            AutomixEffectSelector.select(120f, 90f, true, 0.8f, 0.9f)
        )
        assertEquals(
            "fade",
            AutomixEffectSelector.select(120f, null, false, null, null)
        )
    }

    @Test
    fun closeTemposWithoutConfidentPhaseUseFilterSweep() {
        assertEquals(
            "filter_sweep",
            AutomixEffectSelector.select(120f, 122f, false, 0.9f, 0.9f)
        )
    }
}
