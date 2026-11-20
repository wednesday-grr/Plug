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

data class GeniusSong(val title: String, val artist: String, val url: String)

class GeniusClient(private val clientKey: String) {
    fun search(query: String): List<GeniusSong> {
        if (clientKey.isBlank()) throw IllegalStateException("Genius client key is not configured")
        val url = URL("https://api.genius.com/search?q=${encode(query)}")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Bearer $clientKey")
            connection.setRequestProperty("Accept", "application/json")
            when (val status = connection.responseCode) {
                in 200..299 -> Unit
                else -> throw IOException("Genius returned HTTP $status")
            }
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val hits = JSONObject(body).optJSONObject("response")
                ?.optJSONArray("hits") ?: return emptyList()
            return (0 until hits.length()).mapNotNull { index ->
                val song = hits.optJSONObject(index)?.optJSONObject("result") ?: return@mapNotNull null
                val title = song.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val artist = song.optJSONObject("primary_artist")?.optString("name")
                    ?.takeIf(String::isNotBlank) ?: ""
                val pageUrl = song.optString("url").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                GeniusSong(title, artist, pageUrl)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
