package com.siw.plug.ui.fragments.settings

import android.os.Bundle
import androidx.preference.Preference
import com.siw.plug.R
import com.siw.plug.ui.fragments.BasePreferenceFragment
import com.siw.plug.ui.fragments.BaseSettingsActivity


class BehaviorSettingsActivity : BaseSettingsActivity(
    R.string.settings_playback,
    { BehaviorSettingsFragment() })

class BehaviorSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "replaygain") {
            startActivity(ReplayGainSettingsActivity::class.java)
        }
        return super.onPreferenceTreeClick(preference)
    }
}
