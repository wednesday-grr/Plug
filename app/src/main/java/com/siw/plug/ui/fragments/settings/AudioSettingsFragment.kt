/*
 *     Copyright (C) 2024 Akane Foundation
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

package com.siw.plug.ui.fragments.settings

import android.os.Bundle
import androidx.preference.Preference
import com.siw.plug.R
import com.siw.plug.BuildConfig
import com.siw.plug.logic.utils.PostAmpAudioOutputProvider
import com.siw.plug.ui.fragments.BasePreferenceFragment
import com.siw.plug.ui.fragments.BaseSettingsActivity

class AudioSettingsActivity : BaseSettingsActivity(
    R.string.settings_player_options,
    { AudioSettingsFragment() })

class AudioSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_audio, rootKey)
        findPreference<Preference>("rg_boost_gain_category")!!.isVisible =
            PostAmpAudioOutputProvider.isVolumeAvailable || PostAmpAudioOutputProvider.isDpeAvailable
        if (!BuildConfig.HAS_ESSENTIA) {
            findPreference<Preference>("automix_analyzer")!!.summary =
                getString(R.string.settings_automix_analyzer_unavailable)
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "replaygain" -> {
                startActivity(ReplayGainSettingsActivity::class.java)
            }
        }
        return super.onPreferenceTreeClick(preference)
    }
}
