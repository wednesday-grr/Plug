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

package org.akanework.gramophone.ui.fragments.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity

class LyricSettingsActivity : BaseSettingsActivity(
    R.string.settings_lyric,
    { LyricSettingsFragment() })

class LyricSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_lyric, rootKey)
        listOf("lyric_custom_active_color", "lyric_custom_inactive_color").forEach { key ->
            findPreference<EditTextPreference>(key)!!.setOnPreferenceChangeListener { _, value ->
                val valid = (value as? String)?.matches(Regex("#[0-9a-fA-F]{6}")) == true
                if (!valid)
                    Toast.makeText(requireContext(), R.string.settings_lyric_invalid_color, Toast.LENGTH_SHORT).show()
                valid
            }
        }
    }
}
