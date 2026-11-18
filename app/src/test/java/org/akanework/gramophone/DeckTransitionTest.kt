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

import org.akanework.gramophone.logic.utils.automix.DeckTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeckTransitionTest {
    @Test
    fun pausesAndSwapsExactlyOnceAtCompletion() {
        val transition = DeckTransition()
        transition.beginPreparation()
        transition.markReady()
        transition.start(1000, 100)
        transition.pause(350)
        assertEquals(0.25f, transition.progress(5000), 0f)
        transition.resume(5000)
        assertEquals(0.75f, transition.progress(5500), 0f)
        assertThrows(IllegalStateException::class.java) { transition.complete(5500) }
        transition.complete(5750)
        assertEquals(DeckTransition.Deck.B, transition.activeDeck)
        assertEquals(DeckTransition.Deck.A, transition.standbyDeck)
        assertEquals(DeckTransition.Phase.IDLE, transition.phase)
        assertThrows(IllegalStateException::class.java) { transition.complete(5750) }
    }

    @Test
    fun cancellationKeepsActiveDeckAndRejectsStaleOperations() {
        val transition = DeckTransition()
        transition.beginPreparation()
        transition.markReady()
        transition.start(100, 0)
        transition.cancel()
        assertEquals(DeckTransition.Deck.A, transition.activeDeck)
        assertThrows(IllegalStateException::class.java) { transition.markReady() }
        assertThrows(IllegalStateException::class.java) { transition.resume(100) }
        transition.beginPreparation()
        assertEquals(DeckTransition.Phase.PREPARING, transition.phase)
    }

    @Test
    fun swapsBackOnNextTransitionAndRejectsInvalidTimes() {
        val transition = DeckTransition()
        transition.beginPreparation()
        transition.markReady()
        assertThrows(IllegalArgumentException::class.java) { transition.start(1, -1) }
        transition.start(1, 0)
        transition.complete(1)
        transition.beginPreparation()
        transition.markReady()
        transition.start(1, 1)
        assertThrows(IllegalArgumentException::class.java) { transition.progress(0) }
        transition.complete(2)
        assertEquals(DeckTransition.Deck.A, transition.activeDeck)
    }
}
