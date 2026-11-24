package com.siw.plug.ui.fragments.settings

import android.os.Bundle
import androidx.preference.ListPreference
import com.siw.plug.BuildConfig
import com.siw.plug.R
import com.siw.plug.ui.fragments.BasePreferenceFragment
import com.siw.plug.ui.fragments.BaseSettingsActivity

class AutomixSettingsActivity : BaseSettingsActivity(
    R.string.settings_automix_screen,
    { AutomixSettingsFragment() })

class AutomixSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_automix, rootKey)
        if (!BuildConfig.HAS_ESSENTIA) {
            findPreference<ListPreference>("automix_analyzer")?.let { analyzer ->
                val entries = analyzer.entries
                val values = analyzer.entryValues
                val keep = values.indices.filter { values[it] != "essentia" }
                analyzer.entries = keep.map { entries[it] }.toTypedArray()
                analyzer.entryValues = keep.map { values[it] }.toTypedArray()
                analyzer.summary = getString(R.string.settings_automix_analyzer_unavailable)
            }
        }
    }
}
