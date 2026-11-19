/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.hasAudioPermission
import org.akanework.gramophone.logic.plugApplication
import java.io.File

class MusicStoreDownloadReceiver : BroadcastReceiver() {
    companion object {
        private const val PREFERENCES = "music_store_downloads"

        fun track(context: Context, id: Long, filename: String, archive: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean(id.toString(), archive)
                .putString("$id.filename", filename)
                .apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = id.toString()
        if (id < 0 || !preferences.contains(key)) return
        val archive = preferences.getBoolean(key, false)
        val filename = preferences.getString("$id.filename", null)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val status = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    } else null
                }
                if (status != DownloadManager.STATUS_SUCCESSFUL && status != DownloadManager.STATUS_FAILED) {
                    Log.w("MusicStore", "Download $id has no completed status")
                    return@launch
                }
                preferences.edit().remove(key).remove("$id.filename").apply()
                val message = when {
                    status == DownloadManager.STATUS_FAILED -> R.string.music_store_download_failed
                    else -> {
                        val complete = withTimeoutOrNull(8_000) {
                            @Suppress("DEPRECATION")
                            val path = filename?.let {
                                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), it).absolutePath
                            }
                            val fileScanned = archive || path != null && DownloadScanner.scanFile(context, path)
                            if (context.hasAudioPermission()) {
                                val rootsScanned = DownloadScanner.scan(context)
                                context.plugApplication.reader.refresh()
                                fileScanned && rootsScanned
                            } else {
                                fileScanned
                            }
                        }
                        when {
                            archive -> R.string.music_store_download_archive
                            !context.hasAudioPermission() -> R.string.music_store_download_scan_needed
                            complete == true -> R.string.music_store_download_ready
                            else -> R.string.music_store_download_scan_partial
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: SecurityException) {
                Log.w("MusicStore", "Could not inspect completed download $id", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.music_store_download_scan_partial, Toast.LENGTH_LONG).show()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
