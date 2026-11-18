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

import android.content.SharedPreferences
import androidx.media3.common.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.akanework.gramophone.logic.getBooleanStrict
import org.akanework.gramophone.logic.getIntStrict

data class AutomixSettings(
    val enabled: Boolean,
    val beatmatchEnabled: Boolean,
    val durationSeconds: Int,
)

class AutomixSettingsStore(private val preferences: SharedPreferences) {
    companion object {
        private const val TAG = "AutomixSettings"
        private const val ENABLED = "automix_enabled"
        private const val BEATMATCH_ENABLED = "automix_beatmatch_enabled"
        private const val DURATION = "automix_duration_sec"
    }

    private val mutableSettings = MutableStateFlow(readSettings())
    val settings = mutableSettings.asStateFlow()

    fun onPreferenceChanged(key: String?) {
        if (key == null || key == ENABLED || key == BEATMATCH_ENABLED || key == DURATION) {
            mutableSettings.value = readSettings()
        }
    }

    private fun readSettings(): AutomixSettings {
        val duration = preferences.getIntStrict(DURATION, 5)
        if (duration !in 1..15) {
            Log.w(TAG, "Crossfade duration $duration is outside 1..15 seconds; clamping")
        }
        return AutomixSettings(
            enabled = preferences.getBooleanStrict(ENABLED, false),
            beatmatchEnabled = preferences.getBooleanStrict(BEATMATCH_ENABLED, true),
            durationSeconds = duration.coerceIn(1, 15),
        )
    }
}
