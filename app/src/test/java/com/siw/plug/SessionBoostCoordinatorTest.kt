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

import com.siw.plug.logic.utils.SessionBoostCoordinator
import com.siw.plug.logic.utils.automix.AutomixMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionBoostCoordinatorTest {
    private fun state(gain: Int, active: Boolean, weight: Float) =
        SessionBoostCoordinator.DeckBoostState(gain, active, weight)

    @Test
    fun singleActiveDeckUsesItsTargetRegardlessOfInactiveDeck() {
        assertEquals(300, SessionBoostCoordinator.effectiveGainMb(listOf(
            state(300, true, 1f), state(900, false, 1f)
        )))
        assertEquals(0, SessionBoostCoordinator.effectiveGainMb(listOf(
            state(300, false, 1f), state(900, false, 0f)
        )))
    }

    @Test
    fun equalPowerWeightsAreNormalizedAndNeverDoubleTheGain() {
        val fade = AutomixMath.equalPower(0.5f)
        assertEquals(300, SessionBoostCoordinator.effectiveGainMb(listOf(
            state(300, true, fade.outgoing), state(300, true, fade.incoming)
        )))
        assertEquals(400, SessionBoostCoordinator.effectiveGainMb(listOf(
            state(200, true, fade.outgoing), state(600, true, fade.incoming)
        )))
    }

    @Test
    fun crossfadeEndpointsAndZeroWeightsAreHandled() {
        assertEquals(200, SessionBoostCoordinator.effectiveGainMb(listOf(
            state(200, true, 1f), state(600, true, 0f)
        )))
        assertEquals(600, SessionBoostCoordinator.effectiveGainMb(listOf(
            state(200, true, 0f), state(600, true, 1f)
        )))
        assertEquals(0, SessionBoostCoordinator.effectiveGainMb(listOf(
            state(200, true, 0f), state(600, true, 0f)
        )))
    }

    @Test
    fun volumeFallbackIsDisabledForTheEntireOverlapIncludingEndpoints() {
        val first = state(200, true, 1f)
        val standby = state(600, false, 0f)
        assertEquals(first, SessionBoostCoordinator.volumeFallbackDeck(listOf(first, standby)))
        assertNull(SessionBoostCoordinator.volumeFallbackDeck(listOf(first, standby.copy(isActive = true))))
        assertNull(SessionBoostCoordinator.volumeFallbackDeck(listOf(
            first.copy(fadeWeight = 0f), standby.copy(isActive = true, fadeWeight = 1f)
        )))
    }
}
