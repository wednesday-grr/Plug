/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.siw.plug.R
import com.siw.plug.logic.hasAudioPermission
import com.siw.plug.logic.utils.DownloadScanner
import com.siw.plug.ui.MainActivity
import kotlin.coroutines.resume

internal fun openMusicStoreInBrowser(activity: Activity, url: String): Boolean {
    try {
        CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
        return true
    } catch (e: ActivityNotFoundException) {
        Log.w("MusicStore", "No browser available", e)
    } catch (e: SecurityException) {
        Log.w("MusicStore", "Browser rejected store link", e)
    }
    Toast.makeText(activity, R.string.music_store_no_browser, Toast.LENGTH_LONG).show()
    return false
}

class MusicStoreBottomSheet(private val activity: MainActivity) : BottomSheetDialog(activity) {
    private data class Store(
        val name: Int, val description: Int, val formats: List<String>, val url: String
    )

    init {
        setContentView(R.layout.music_store_bottom_sheet)
        val scroll = findViewById<android.view.View>(R.id.store_scroll)!!
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        val stores = listOf(
            Store(R.string.music_store_qobuz, R.string.music_store_qobuz_formats, listOf(".FLAC", ".WAV"), "https://qobuz.com"),
            Store(R.string.music_store_bandcamp, R.string.music_store_bandcamp_formats, listOf(".FLAC", ".MP3", ".M4A"), "https://bandcamp.com"),
            Store(R.string.music_store_7digital, R.string.music_store_7digital_formats, listOf(".FLAC", ".MP3"), "https://7digital.com"),
            Store(R.string.music_store_ototoy, R.string.music_store_ototoy_formats, listOf(".FLAC", ".WAV"), "https://ototoy.jp"),
        )
        val list = findViewById<LinearLayout>(R.id.store_list)!!
        stores.forEach { store ->
            val item = LayoutInflater.from(context).inflate(R.layout.music_store_item, list, false)
            item.findViewById<TextView>(R.id.store_name).setText(store.name)
            item.findViewById<TextView>(R.id.store_formats).setText(store.description)
            val badges = item.findViewById<ChipGroup>(R.id.store_format_badges)
            store.formats.forEach { format ->
                badges.addView(Chip(context).apply {
                    text = format
                    isClickable = false
                    isFocusable = false
                })
            }
            item.setOnClickListener {
                if (openMusicStoreInBrowser(activity, store.url)) dismiss()
            }
            list.addView(item)
        }
        val scan = findViewById<MaterialButton>(R.id.store_scan)!!
        fun observeScan() {
            val job = activity.musicStoreScanJob
            scan.isEnabled = job?.isActive != true
            job?.invokeOnCompletion {
                scan.post { if (isShowing) scan.isEnabled = activity.musicStoreScanJob?.isActive != true }
            }
        }
        observeScan()
        scan.setOnClickListener {
            if (!activity.hasAudioPermission()) {
                Toast.makeText(activity, R.string.music_store_scan_permission, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            scan.isEnabled = false
            Toast.makeText(activity, R.string.music_store_scanning, Toast.LENGTH_SHORT).show()
            activity.musicStoreScanJob = activity.lifecycleScope.launch {
                val complete = withContext(Dispatchers.IO) {
                    DownloadScanner.scan(activity.applicationContext)
                }
                suspendCancellableCoroutine { continuation ->
                    activity.updateLibrary(false) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                Toast.makeText(
                    activity,
                    if (complete) R.string.music_store_scan_complete else R.string.music_store_scan_partial,
                    Toast.LENGTH_LONG
                ).show()
            }
            observeScan()
        }
    }

}
