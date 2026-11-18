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

import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.akanework.gramophone.logic.utils.ReplayGainUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReplayGainSettingsTest {
    @Test
    fun copiesSettingsWithoutSharingPerDeckState() {
        val first = ReplayGainAudioProcessor()
        val second = ReplayGainAudioProcessor()
        val settings = ReplayGainAudioProcessor.Settings(
            ReplayGainUtil.Mode.Track, 4, -3, 5, true
        )
        first.applySettings(settings)
        second.applySettings(first.snapshotSettings())
        assertEquals(settings, second.snapshotSettings())

        first.setRgGain(8)
        first.setOffloadEnabled(true)
        assertEquals(settings, second.snapshotSettings())
        assertFalse(second.offloadEnabled)
    }
}
