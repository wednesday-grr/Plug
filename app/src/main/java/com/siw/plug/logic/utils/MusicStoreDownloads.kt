/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils

import java.util.Locale

internal object MusicStoreDownloads {
    fun extension(filename: String): String =
        filename.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isArchive(filename: String, mimeType: String): Boolean =
        extension(filename) == "zip" || mimeType.equals("application/zip", true) ||
            mimeType.equals("application/x-zip-compressed", true)

    fun isSupported(filename: String, mimeType: String): Boolean =
        extension(filename) in setOf("flac", "m4a", "mp3", "wav", "zip") ||
            mimeType.startsWith("audio/", true) || isArchive(filename, mimeType)

    fun destinationName(filename: String, uniqueId: String): String {
        val safe = filename.replace(Regex("[\\\\/\\p{Cntrl}]"), "_")
        val extension = extension(safe)
        val base = safe.substringBeforeLast('.', safe).take(160)
        val suffix = if (extension.isNotEmpty()) ".$extension" else ""
        return "$base-$uniqueId$suffix"
    }
}
