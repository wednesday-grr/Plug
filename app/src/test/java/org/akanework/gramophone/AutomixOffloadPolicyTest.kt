/*
 *     Copyright (C) 2026 The Gramophone authors
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone

import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import org.akanework.gramophone.logic.utils.automix.AutomixOffloadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixOffloadPolicyTest {
    @Test
    fun automixDisablesEveryOffloadMode() {
        for (mode in listOf(null, 0, 1, 2)) {
            val preferences = AutomixOffloadPolicy.preferences(true, true, mode)
            assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED, preferences.audioOffloadMode)
            assertFalse(preferences.isGaplessSupportRequired)
        }
    }

    @Test
    fun restoresConfiguredOffloadModeAndGaplessRequirement() {
        assertEquals(
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            AutomixOffloadPolicy.preferences(false, false, 2).audioOffloadMode
        )
        assertEquals(
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            AutomixOffloadPolicy.preferences(false, true, 0).audioOffloadMode
        )
        val enabled = AutomixOffloadPolicy.preferences(false, true, 1)
        assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED, enabled.audioOffloadMode)
        assertFalse(enabled.isGaplessSupportRequired)
        val gapless = AutomixOffloadPolicy.preferences(false, true, 2)
        assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED, gapless.audioOffloadMode)
        assertTrue(gapless.isGaplessSupportRequired)
    }
}
