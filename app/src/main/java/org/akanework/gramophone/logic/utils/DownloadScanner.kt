/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

internal object DownloadScanner {
    private val audioExtensions = setOf(
        "aac", "aif", "aiff", "alac", "amr", "ape", "dff", "dsf", "flac", "m4a",
        "m4b", "mid", "midi", "mka", "mp2", "mp3", "oga", "ogg", "opus", "wav", "wma"
    )

    suspend fun scan(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        return scanRoots(context, roots)
    }

    suspend fun scanFile(context: Context, path: String): Boolean =
        scanBatch(context, arrayOf(path))

    internal suspend fun scanRoots(
        context: Context,
        roots: List<File>,
        scanFiles: suspend (Context, Array<String>) -> Boolean = ::scanBatch
    ): Boolean {
        var complete = true
        val batch = ArrayList<String>(128)
        val visited = hashSetOf<File>()
        try {
            for (root in roots) {
                if (!root.exists()) continue
                val canonicalRoot = root.canonicalFile
                val files = root.walkTopDown().onEnter { directory ->
                    val canonical = directory.canonicalFile
                    (canonical == canonicalRoot || canonical.path.startsWith(canonicalRoot.path + File.separator)) &&
                        visited.add(canonical) && !File(directory, ".nomedia").exists()
                }.onFail { file, error ->
                    Log.w("DownloadScanner", "Unable to read ${file.path}", error)
                    complete = false
                }
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    if (!file.isFile || !isAudio(file.name)) continue
                    if (!file.canonicalPath.startsWith(canonicalRoot.path + File.separator)) continue
                    batch.add(file.absolutePath)
                    if (batch.size == 128) {
                        if (!scanFiles(context, batch.toTypedArray())) complete = false
                        batch.clear()
                    }
                }
            }
            if (batch.isNotEmpty() && !scanFiles(context, batch.toTypedArray())) complete = false
        } catch (e: IOException) {
            Log.w("DownloadScanner", "Unable to traverse downloads", e)
            complete = false
        } catch (e: SecurityException) {
            Log.w("DownloadScanner", "Unable to access downloads", e)
            complete = false
        }
        return complete
    }

    internal fun isAudio(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in audioExtensions ||
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.startsWith("audio/") == true
    }

    private suspend fun scanBatch(context: Context, paths: Array<String>): Boolean =
        suspendCancellableCoroutine { continuation ->
            val remaining = paths.toMutableSet()
            var complete = true
            MediaScannerConnection.scanFile(context, paths, null) { path, uri ->
                synchronized(remaining) {
                    if (remaining.remove(path)) {
                        if (uri == null) {
                            Log.w("DownloadScanner", "Media scanner could not scan $path")
                            complete = false
                        }
                        if (remaining.isEmpty() && continuation.isActive) {
                            continuation.resume(complete)
                        }
                    }
                }
            }
        }
}
