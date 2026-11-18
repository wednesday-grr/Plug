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

package org.akanework.gramophone.logic.utils.automix

import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences

object AutomixOffloadPolicy {
    fun preferences(
        automixEnabled: Boolean,
        offloadAvailable: Boolean,
        configuredMode: Int?,
    ): AudioOffloadPreferences {
        val builder = AudioOffloadPreferences.Builder()
        if (!automixEnabled && offloadAvailable && configuredMode != null && configuredMode > 0) {
            builder.setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            builder.setIsGaplessSupportRequired(configuredMode == 2)
        }
        return builder.build()
    }
}
