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

package com.siw.plug

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.siw.plug.logic.utils.automix.AutomixSettings
import com.siw.plug.logic.utils.automix.AutomixSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class AutomixSettingsTest {
    @Test
    fun persistsAnalyzerSelectionWithExistingAsDefault() {
        val prefs = preferences()
        val store = AutomixSettingsStore(prefs)
        assertEquals("existing", store.settings.value.analyzer)
        prefs.edit().putString("automix_analyzer", "essentia").commit()
        store.onPreferenceChanged("automix_analyzer")
        assertEquals("essentia", store.settings.value.analyzer)
        assertEquals("essentia", AutomixSettingsStore(prefs).settings.value.analyzer)
    }

    @Test
    fun readsDefaultsAndPersistedValues() {
        val prefs = preferences()
        assertEquals(AutomixSettings(false, true, 5), AutomixSettingsStore(prefs).settings.value)

        prefs.edit().putBoolean("automix_enabled", true)
            .putBoolean("automix_beatmatch_enabled", false)
            .putInt("automix_duration_sec", 12)
            .putString("automix_transition_style", "vinyl_brake").commit()
        assertEquals(
            AutomixSettings(true, false, 12, "vinyl_brake"),
            AutomixSettingsStore(prefs).settings.value
        )
    }

    @Test
    fun changesAreObservedAndDurationIsBounded() {
        val prefs = preferences()
        val store = AutomixSettingsStore(prefs)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            store.onPreferenceChanged(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            prefs.edit().putBoolean("automix_enabled", true).commit()
            assertEquals(AutomixSettings(true, true, 5), store.settings.value)
            prefs.edit().putBoolean("automix_beatmatch_enabled", false).commit()
            assertEquals(AutomixSettings(true, false, 5), store.settings.value)
            prefs.edit().putInt("automix_duration_sec", 0).commit()
            assertEquals(1, store.settings.value.durationSeconds)
            prefs.edit().putInt("automix_duration_sec", 31).commit()
            assertEquals(30, store.settings.value.durationSeconds)
            prefs.edit().putBoolean("automix_duration_auto", true).commit()
            assertEquals(true, store.settings.value.automaticDuration)
            prefs.edit().putBoolean("automix_duration_auto", false).commit()
            prefs.edit().putInt("automix_max_skip_sec", 40).commit()
            assertEquals(30, store.settings.value.maxSkipSeconds)
            prefs.edit().putInt("automix_max_skip_sec", 0).commit()
            assertEquals(0, store.settings.value.maxSkipSeconds)
            prefs.edit().putInt("automix_max_skip_sec", 15).commit()
            prefs.edit().putInt("automix_duration_sec", 7).commit()
            assertEquals(7, store.settings.value.durationSeconds)
            prefs.edit().putString("automix_transition_style", "vinyl_brake").commit()
            assertEquals("vinyl_brake", store.settings.value.transitionStyle)
            prefs.edit().putBoolean("automix_enabled", false).commit()
            assertEquals(AutomixSettings(false, false, 7, "vinyl_brake"), store.settings.value)
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    @Test
    fun acceptsAllNewTransitionStyles() {
        val prefs = preferences()
        val store = AutomixSettingsStore(prefs)
        for (style in listOf("high_pass_intro", "reverb_tail", "tremolo_fade", "fade", "rise")) {
            prefs.edit().putString("automix_transition_style", style).commit()
            store.onPreferenceChanged("automix_transition_style")
            assertEquals(style, store.settings.value.transitionStyle)
        }
    }

    @Test
    fun persistsIndependentEffectsAndIgnoresUnknownIds() {
        val prefs = preferences()
        val store = AutomixSettingsStore(prefs)
        prefs.edit().putStringSet("automix_additional_effects",
            setOf("bass_swap", "echo_out", "reverb_tail", "invalid")).commit()
        store.onPreferenceChanged("automix_additional_effects")
        assertEquals(setOf("bass_swap", "echo_out", "reverb_tail"),
            store.settings.value.additionalEffects)
        assertEquals(store.settings.value, AutomixSettingsStore(prefs).settings.value)
    }

    @Test
    fun persistsAndClampsEffectIntensity() {
        val prefs = preferences()
        val store = AutomixSettingsStore(prefs)
        prefs.edit().putInt("automix_effect_intensity", 40).commit()
        store.onPreferenceChanged("automix_effect_intensity")
        assertEquals(40, store.settings.value.effectIntensityPercent)
        prefs.edit().putInt("automix_effect_intensity", 101).commit()
        store.onPreferenceChanged("automix_effect_intensity")
        assertEquals(100, store.settings.value.effectIntensityPercent)
    }

    @Test
    fun loopTransitionBeatsDefaultsPersistsAndRejectsUnsupportedValues() {
        val prefs = preferences()
        val store = AutomixSettingsStore(prefs)
        assertEquals(4, store.settings.value.loopTransitionBeats)
        for (beats in listOf(1, 2, 4, 8, 16)) {
            prefs.edit().putString("automix_loop_transition_beats", beats.toString()).commit()
            store.onPreferenceChanged("automix_loop_transition_beats")
            assertEquals(beats, store.settings.value.loopTransitionBeats)
        }
        for (bad in listOf("3", "0", "32", "abc")) {
            prefs.edit().putString("automix_loop_transition_beats", bad).commit()
            store.onPreferenceChanged("automix_loop_transition_beats")
            assertEquals(4, store.settings.value.loopTransitionBeats)
        }
    }

    @Test
    fun loudnessMatchingAndAgcDefaultOffAndPersist() {
        val prefs = preferences()
        val store = AutomixSettingsStore(prefs)
        assertEquals(false, store.settings.value.loudnessMatchingEnabled)
        assertEquals(false, store.settings.value.agcEnabled)
        prefs.edit().putBoolean("automix_loudness_matching", true).putBoolean("automix_agc", true).commit()
        store.onPreferenceChanged("automix_loudness_matching")
        assertEquals(true, store.settings.value.loudnessMatchingEnabled)
        store.onPreferenceChanged("automix_agc")
        assertEquals(true, store.settings.value.agcEnabled)
        assertEquals(store.settings.value, AutomixSettingsStore(prefs).settings.value)
    }

    private fun preferences(): SharedPreferences =
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "automix-test", Context.MODE_PRIVATE
        ).also { it.edit().clear().commit() }
}
