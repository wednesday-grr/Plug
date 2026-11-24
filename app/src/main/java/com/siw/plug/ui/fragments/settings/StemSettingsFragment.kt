package com.siw.plug.ui.fragments.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.siw.plug.R
import com.siw.plug.logic.utils.automix.stems.StemCache
import com.siw.plug.logic.utils.automix.stems.StemSeparation
import com.siw.plug.ui.fragments.BasePreferenceFragment
import com.siw.plug.ui.fragments.BaseSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class StemSettingsActivity : BaseSettingsActivity(
    R.string.settings_stems,
    { StemSettingsFragment() })

class StemSettingsFragment : BasePreferenceFragment() {
    private var downloading = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_stems, rootKey)
        updateStemSwitchEnabled()
        refreshStemPreferences()
    }

    // Preference dependencies cannot span screens, so the AutoMix master switch is tracked manually.
    private fun updateStemSwitchEnabled() {
        val automixEnabled = preferenceScreen.sharedPreferences?.getBoolean("automix_enabled", false) ?: false
        findPreference<Preference>("automix_stem_transitions")?.isEnabled = automixEnabled
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "automix_enabled") updateStemSwitchEnabled()
    }

    override fun onResume() {
        super.onResume()
        updateStemSwitchEnabled()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "stem_model" -> onStemModelClicked()
            "stem_cache" -> onStemCacheClicked()
        }
        return super.onPreferenceTreeClick(preference)
    }

    // Completed entries only: skips in-flight "tmp-" staging directories that the playback service may be writing.
    private fun stemCacheDirs(): List<File> =
        File(requireContext().applicationContext.filesDir, "stems").listFiles { f ->
            f.isDirectory && !f.name.startsWith(StemCache.STAGING_PREFIX)
        }?.toList().orEmpty()

    private fun refreshStemPreferences() {
        val context = requireContext()
        val model = findPreference<Preference>("stem_model") ?: return
        val cache = findPreference<Preference>("stem_cache") ?: return
        val store = StemSeparation.modelStore(context)
        model.summary = when {
            downloading -> model.summary
            !StemSeparation.isDeviceEligible(context) -> getString(R.string.settings_stem_model_unsupported)
            store.isInstalled() -> getString(R.string.settings_stem_model_installed,
                Formatter.formatFileSize(context, store.spec.totalBytes))
            else -> getString(R.string.settings_stem_model_missing,
                Formatter.formatFileSize(context, store.spec.totalBytes))
        }
        lifecycleScope.launch {
            val (bytes, count) = withContext(Dispatchers.IO) {
                stemCacheDirs().let { dirs -> dirs.sumOf { it.walkTopDown().filter(File::isFile).sumOf(File::length) } to dirs.size }
            }
            cache.summary = if (count == 0) getString(R.string.settings_stem_cache_empty)
            else getString(R.string.settings_stem_cache_summary, Formatter.formatFileSize(context, bytes), count)
        }
    }

    private fun onStemModelClicked() {
        val context = requireContext()
        if (downloading || !StemSeparation.isDeviceEligible(context)) return
        val store = StemSeparation.modelStore(context)
        if (store.isInstalled()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_stem_model_delete_title)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    store.delete()
                    refreshStemPreferences()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val preference = findPreference<Preference>("stem_model") ?: return
        downloading = true
        preference.summary = getString(R.string.settings_stem_model_downloading, 0)
        // Tied to the activity, not the view, so leaving the screen cancels the download and drops the .part file.
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job]!!
                    var lastPercent = -1
                    store.download(isCancelled = { !job.isActive }, onProgress = { done, total ->
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            launchOnMain {
                                preference.summary = if (percent >= 100) getString(R.string.settings_stem_model_verifying)
                                else getString(R.string.settings_stem_model_downloading, percent)
                            }
                        }
                    })
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    preference.summary = getString(R.string.settings_stem_model_failed, e.message ?: e.javaClass.simpleName)
                }
                downloading = false
                return@launch
            } finally {
                downloading = false
            }
            if (isAdded) refreshStemPreferences()
        }
    }

    private fun launchOnMain(block: () -> Unit) {
        activity?.runOnUiThread { if (isAdded) block() }
    }

    private fun onStemCacheClicked() {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_stem_cache_clear_title)
            .setMessage(R.string.settings_stem_cache_clear_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        stemCacheDirs().forEach(File::deleteRecursively)
                    }
                    Toast.makeText(context, R.string.settings_stem_cache_cleared, Toast.LENGTH_SHORT).show()
                    refreshStemPreferences()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
