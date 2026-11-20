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
    val transitionStyle: String = "auto",
    val automaticDuration: Boolean = false,
    val maxSkipSeconds: Int = 15,
    val additionalEffects: Set<String> = emptySet(),
    val effectIntensityPercent: Int = 100,
    val analyzer: String = "existing",
)

class AutomixSettingsStore(private val preferences: SharedPreferences) {
    companion object {
        private const val TAG = "AutomixSettings"
        private const val ENABLED = "automix_enabled"
        private const val BEATMATCH_ENABLED = "automix_beatmatch_enabled"
        private const val DURATION = "automix_duration_sec"
        private const val AUTOMATIC_DURATION = "automix_duration_auto"
        private const val MAX_SKIP = "automix_max_skip_sec"
        private const val TRANSITION_STYLE = "automix_transition_style"
        private const val ADDITIONAL_EFFECTS = "automix_additional_effects"
        private const val EFFECT_INTENSITY = "automix_effect_intensity"
        private const val ANALYZER = "automix_analyzer"
        private val TRANSITION_STYLES = setOf(
            "auto", "smooth", "vinyl_brake", "bass_swap", "filter_sweep", "echo_out",
            "high_pass_intro", "reverb_tail", "tremolo_fade", "fade", "rise", "loop_transition"
        )
    }

    private val mutableSettings = MutableStateFlow(readSettings())
    val settings = mutableSettings.asStateFlow()

    fun onPreferenceChanged(key: String?) {
        if (key == null || key == ENABLED || key == BEATMATCH_ENABLED || key == DURATION ||
            key == TRANSITION_STYLE || key == AUTOMATIC_DURATION || key == MAX_SKIP ||
            key == ADDITIONAL_EFFECTS || key == EFFECT_INTENSITY || key == ANALYZER
        ) {
            mutableSettings.value = readSettings()
        }
    }

    private fun readSettings(): AutomixSettings {
        val duration = preferences.getIntStrict(DURATION, 5)
        val maxSkip = preferences.getIntStrict(MAX_SKIP, 15)
        val effectIntensity = preferences.getIntStrict(EFFECT_INTENSITY, 100)
        if (effectIntensity !in 0..100) {
            Log.w(TAG, "Effect intensity $effectIntensity is outside 0..100; clamping")
        }
        if (maxSkip !in 0..30) {
            Log.w(TAG, "Maximum skip $maxSkip is outside 0..30 seconds; clamping")
        }
        if (duration !in 1..30) {
            Log.w(TAG, "Crossfade duration $duration is outside 1..30 seconds; clamping")
        }
        val transitionStyle = preferences.getString(TRANSITION_STYLE, "auto") ?: "auto"
        if (transitionStyle !in TRANSITION_STYLES) {
            Log.w(TAG, "Unknown transition style $transitionStyle; using auto")
        }
        val requestedEffects = preferences.getStringSet(ADDITIONAL_EFFECTS, emptySet())?.toSet() ?: emptySet()
        val unknownEffects = requestedEffects - TransitionEffectPlan.selectableIds
        if (unknownEffects.isNotEmpty()) Log.w(TAG, "Unknown additional effects: $unknownEffects")
        val analyzer = preferences.getString(ANALYZER, "existing") ?: "existing"
        if (analyzer !in setOf("existing", "essentia")) Log.w(TAG, "Unknown analyzer $analyzer; using existing")
        return AutomixSettings(
            enabled = preferences.getBooleanStrict(ENABLED, false),
            beatmatchEnabled = preferences.getBooleanStrict(BEATMATCH_ENABLED, true),
            durationSeconds = duration.coerceIn(1, 30),
            transitionStyle = transitionStyle.takeIf { it in TRANSITION_STYLES } ?: "auto",
            automaticDuration = preferences.getBooleanStrict(AUTOMATIC_DURATION, false),
            maxSkipSeconds = maxSkip.coerceIn(0, 30),
            additionalEffects = requestedEffects.intersect(TransitionEffectPlan.selectableIds),
            effectIntensityPercent = effectIntensity.coerceIn(0, 100),
            analyzer = analyzer.takeIf { it in setOf("existing", "essentia") } ?: "existing",
        )
    }
}
