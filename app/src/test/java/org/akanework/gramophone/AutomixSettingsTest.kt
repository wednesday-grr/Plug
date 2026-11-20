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

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.akanework.gramophone.logic.utils.automix.AutomixSettings
import org.akanework.gramophone.logic.utils.automix.AutomixSettingsStore
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

    private fun preferences(): SharedPreferences =
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "automix-test", Context.MODE_PRIVATE
        ).also { it.edit().clear().commit() }
}
