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

package com.siw.plug.logic.utils.automix

/** Role and fade timing only; the owning player must perform the corresponding deck operations. */
class DeckTransition {
    enum class Deck { A, B }
    enum class Phase { IDLE, PREPARING, READY, FADING }

    var activeDeck: Deck = Deck.A
        private set
    val standbyDeck: Deck get() = if (activeDeck == Deck.A) Deck.B else Deck.A
    var phase: Phase = Phase.IDLE
        private set
    private var durationMs = 0L
    private var elapsedMs = 0L
    private var runningSinceMs: Long? = null

    fun beginPreparation() {
        check(phase == Phase.IDLE)
        phase = Phase.PREPARING
    }

    fun markReady() {
        check(phase == Phase.PREPARING)
        phase = Phase.READY
    }

    fun start(durationMs: Long, nowMs: Long) {
        check(phase == Phase.READY)
        require(durationMs > 0)
        require(nowMs >= 0) { "Fade time must be non-negative" }
        this.durationMs = durationMs
        elapsedMs = 0
        runningSinceMs = nowMs
        phase = Phase.FADING
    }

    fun pause(nowMs: Long) {
        check(phase == Phase.FADING)
        runningSinceMs?.let {
            elapsedMs = elapsedAt(nowMs)
            runningSinceMs = null
        }
    }

    fun resume(nowMs: Long) {
        check(phase == Phase.FADING)
        require(nowMs >= 0) { "Fade time must be non-negative" }
        if (runningSinceMs == null) runningSinceMs = nowMs
    }

    fun progress(nowMs: Long): Float {
        check(phase == Phase.FADING)
        return (elapsedAt(nowMs).toDouble() / durationMs).toFloat()
    }

    fun complete(nowMs: Long) {
        check(phase == Phase.FADING && elapsedAt(nowMs) == durationMs)
        activeDeck = standbyDeck
        cancel()
    }

    fun cancel() {
        phase = Phase.IDLE
        durationMs = 0
        elapsedMs = 0
        runningSinceMs = null
    }

    private fun elapsedAt(nowMs: Long): Long {
        require(nowMs >= 0) { "Fade time must be non-negative" }
        val start = runningSinceMs ?: return elapsedMs
        require(nowMs >= start) { "Fade time must be monotonic" }
        return elapsedMs + (nowMs - start).coerceAtMost(durationMs - elapsedMs)
    }
}
