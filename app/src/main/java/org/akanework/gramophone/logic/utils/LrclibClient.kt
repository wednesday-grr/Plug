/*
 *     Copyright (C) 2026 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

package org.akanework.gramophone.logic.utils

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LrclibClient {
    fun lookup(title: String, artist: String, durationMs: Long?): String? {
        val query = buildString {
            append("track_name=").append(encode(title))
            append("&artist_name=").append(encode(artist))
            durationMs?.takeIf { it > 0 }?.let {
                append("&duration=").append(it / 1000)
            }
        }
        val connection = URL("https://lrclib.net/api/get?$query")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", "Gramophone/1.0")
            connection.setRequestProperty("Accept", "application/json")
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                in 200..299 -> Unit
                else -> throw IOException("LRCLIB returned HTTP $status")
            }
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            return json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                ?: json.optString("plainLyrics").takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
