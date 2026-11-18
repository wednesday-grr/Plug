package org.akanework.gramophone.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjInfoDisplayStateTest {
    @Test
    fun alternatesEveryTenSecondsAndPausesClock() {
        val state = DjInfoDisplayState()
        assertFalse(state.showDj(0, true, 120_000, 0))
        assertFalse(state.showDj(9_999, true, 120_000, 9_999))
        assertTrue(state.showDj(10_000, true, 120_000, 10_000))
        assertTrue(state.showDj(11_000, false, 120_000, 11_000))
        assertTrue(state.showDj(80_000, false, 120_000, 11_000))
        assertTrue(state.showDj(90_000, true, 120_000, 11_000))
        assertFalse(state.showDj(99_000, true, 120_000, 20_000))
    }

    @Test
    fun finalSectionLocksAndSeekingBackRestoresAlternation() {
        val state = DjInfoDisplayState()
        assertFalse(state.showDj(0, true, 60_000, 44_999))
        assertTrue(state.showDj(1, true, 60_000, 45_000))
        assertTrue(state.showDj(2, false, 60_000, 60_500))
        assertFalse(state.showDj(3, false, 60_000, 20_000))
    }

    @Test
    fun shortAndUnknownDurationsDoNotLockAndNewTracksReset() {
        val state = DjInfoDisplayState()
        assertFalse(state.showDj(0, true, 15_000, 14_999))
        assertFalse(state.showDj(5_000, true, -9223372036854775807L, 0))
        assertTrue(state.showDj(10_000, true, 120_000, 10_000))
        state.reset()
        assertFalse(state.showDj(10_001, true, 120_000, 0))
    }
}
