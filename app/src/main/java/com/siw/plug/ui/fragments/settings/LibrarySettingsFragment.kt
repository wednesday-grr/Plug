package com.siw.plug.ui.fragments.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.siw.plug.R
import com.siw.plug.logic.hasImagePermission
import com.siw.plug.logic.hasScopedStorageWithMediaTypes
import com.siw.plug.logic.utils.Flags
import com.siw.plug.ui.fragments.BasePreferenceFragment
import com.siw.plug.ui.fragments.BaseSettingsActivity

class LibrarySettingsActivity : BaseSettingsActivity(
    R.string.settings_library,
    { LibrarySettingsFragment() })

class LibrarySettingsFragment : BasePreferenceFragment() {

    override fun onResume() {
        super.onResume()
        if (hasScopedStorageWithMediaTypes()) {
            val preference = findPreference<SwitchPreferenceCompat>("album_covers")!!
            preference.isPersistent = false
            if (!Flags.REMOVE_IMAGE_PERMISSION)
                preference.isChecked = requireContext().hasImagePermission()
            else
                preference.isVisible = false
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_library, rootKey)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "blacklist") {
            startActivity(BlacklistSettingsActivity::class.java)
        }
        // Prior to Android 13, this changes a setting which changes MediaStoreUtils behaviour
        // Android 13 and later, this displays state of images permission granted/denied
        if (hasScopedStorageWithMediaTypes() && !Flags.REMOVE_IMAGE_PERMISSION &&
            preference.key == "album_covers") {
            Toast.makeText(
                requireActivity(), if (requireContext().hasImagePermission())
                    R.string.deny_images else R.string.grant_images, Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.setData("package:${requireContext().packageName}".toUri())
            startActivity(intent)
        }
        return super.onPreferenceTreeClick(preference)
    }
}
