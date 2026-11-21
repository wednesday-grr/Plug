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
    val loopTransitionBeats: Int = AutomixSettingsStore.DEFAULT_LOOP_TRANSITION_BEATS,
    val fadeCurveIntensityPercent: Int = FadeCurveSettings.DEFAULT_INTENSITY_PERCENT,
    val loudnessMatchingEnabled: Boolean = false,
    val agcEnabled: Boolean = false,
    val bassBoost: BassBoostSettings = BassBoostSettings.OFF,
    val energyMatchingEnabled: Boolean = false,
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
        private const val LOOP_TRANSITION_BEATS = "automix_loop_transition_beats"
        private const val FADE_CURVE_INTENSITY = "automix_fade_curve_intensity"
        private const val LOUDNESS_MATCHING = "automix_loudness_matching"
        private const val AGC = "automix_agc"
        private const val BASS_BOOST = "automix_bass_boost"
        private const val ENERGY_MATCHING = "automix_energy_matching"
        private const val BASS_BOOST_GAIN = "automix_bass_boost_gain_db"
        private const val BASS_BOOST_FREQUENCY = "automix_bass_boost_frequency_hz"
        const val DEFAULT_LOOP_TRANSITION_BEATS = 4
        private val TRANSITION_STYLES = setOf(
            "auto", "smooth", "vinyl_brake", "bass_swap", "filter_sweep", "echo_out",
            "high_pass_intro", "reverb_tail", "tremolo_fade", "fade", "rise", "loop_transition",
            "exponential_fade", "s_curve_fade"
        )
    }

    private val mutableSettings = MutableStateFlow(readSettings())
    val settings = mutableSettings.asStateFlow()

    fun onPreferenceChanged(key: String?) {
        if (key == null || key == ENABLED || key == BEATMATCH_ENABLED || key == DURATION ||
            key == TRANSITION_STYLE || key == AUTOMATIC_DURATION || key == MAX_SKIP ||
            key == ADDITIONAL_EFFECTS || key == EFFECT_INTENSITY || key == ANALYZER ||
                        key == LOOP_TRANSITION_BEATS || key == FADE_CURVE_INTENSITY ||
                        key == LOUDNESS_MATCHING || key == AGC || key == BASS_BOOST ||
                        key == BASS_BOOST_GAIN || key == BASS_BOOST_FREQUENCY || key == ENERGY_MATCHING
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
        val rawLoopBeats = preferences.getString(LOOP_TRANSITION_BEATS, null)
        val loopBeats = rawLoopBeats?.toIntOrNull()?.takeIf { it in BeatLoopIntervals.supported }
        if (rawLoopBeats != null && loopBeats == null) {
            Log.w(TAG, "Unsupported loop transition length $rawLoopBeats; using $DEFAULT_LOOP_TRANSITION_BEATS")
        }
        val fadeIntensity = preferences.getIntStrict(FADE_CURVE_INTENSITY, FadeCurveSettings.DEFAULT_INTENSITY_PERCENT)
        if (fadeIntensity !in 0..100) Log.w(TAG, "Fade curve intensity $fadeIntensity is outside 0..100; clamping")
        val bassGain = preferences.getIntStrict(BASS_BOOST_GAIN, BassBoostSettings.DEFAULT_GAIN_DB)
        val bassFrequency = preferences.getIntStrict(BASS_BOOST_FREQUENCY, BassBoostSettings.DEFAULT_FREQUENCY_HZ)
        if (bassGain !in BassBoostSettings.GAIN_DB_RANGE || bassFrequency !in BassBoostSettings.FREQUENCY_HZ_RANGE) {
            Log.w(TAG, "Bass boost ${bassGain} dB / ${bassFrequency} Hz is outside its range; clamping")
        }
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
                        loopTransitionBeats = loopBeats ?: DEFAULT_LOOP_TRANSITION_BEATS,
                        fadeCurveIntensityPercent = fadeIntensity.coerceIn(0, 100),
                        loudnessMatchingEnabled = preferences.getBooleanStrict(LOUDNESS_MATCHING, false),
                        agcEnabled = preferences.getBooleanStrict(AGC, false),
                        bassBoost = BassBoostSettings.coerced(
                            preferences.getBooleanStrict(BASS_BOOST, false), bassGain, bassFrequency
                        ),
                        energyMatchingEnabled = preferences.getBooleanStrict(ENERGY_MATCHING, false),
                    )
    }
}
