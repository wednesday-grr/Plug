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

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.siw.plug.logic.utils.SessionBoostCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class SessionBoostCoordinatorStateTest {
    @Test
    fun deckReportsAndTransitionsAreAtomic() {
        val coordinator = SessionBoostCoordinator(Handler(Looper.getMainLooper()), 123)
        coordinator.registerDeck("deckA")
        coordinator.registerDeck("deckB")
        try {
            coordinator.updateRequirement("deckA", 200, -4f, 1f)
            coordinator.updateRequirement("deckB", 600, -8f, 1f)
            coordinator.updateFadeWeights("deckA", 1f, "deckB", 0f, false)
            var states = coordinator.snapshotDecks()
            assertEquals(200, SessionBoostCoordinator.effectiveGainMb(states.values))
            assertFalse(states.getValue("deckB").isActive)

            coordinator.updateFadeWeights("deckA", 0.6f, "deckB", 0.8f, true)
            states = coordinator.snapshotDecks()
            assertTrue(states.getValue("deckA").isActive)
            assertTrue(states.getValue("deckB").isActive)
            assertEquals(429, SessionBoostCoordinator.effectiveGainMb(states.values))

            coordinator.updateFadeWeights("deckB", 1f, "deckA", 0f, false)
            states = coordinator.snapshotDecks()
            assertEquals(600, SessionBoostCoordinator.effectiveGainMb(states.values))
            assertFalse(states.getValue("deckA").isActive)
            assertThrows(IllegalStateException::class.java) { coordinator.requireSessionId(124) }
        } finally {
            coordinator.release()
        }
        assertTrue(coordinator.snapshotDecks().isEmpty())
    }
}
