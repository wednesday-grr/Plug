/*
 *     Copyright (C) 2026 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

package com.siw.plug.logic.utils

import android.content.SharedPreferences

internal class LyricsResponseCache(private val preferences: SharedPreferences) {
    fun get(key: String, now: Long = System.currentTimeMillis()): String? {
        val timestamp = preferences.getLong(timestampKey(key), 0L)
        val value = preferences.getString(key, null)
        if (value == null || timestamp <= 0L || now - timestamp >= CACHE_TTL_MS) {
            preferences.edit().remove(key).remove(timestampKey(key)).apply()
            return null
        }
        return value
    }

    fun put(key: String, value: String, now: Long = System.currentTimeMillis()) {
        preferences.edit().putString(key, value).putLong(timestampKey(key), now).apply()
    }

    fun remove(key: String) {
        preferences.edit().remove(key).remove(timestampKey(key)).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun removeExpired(now: Long = System.currentTimeMillis()) {
        preferences.all.keys.filter { !it.endsWith(TIMESTAMP_SUFFIX) }.forEach { key ->
            val timestamp = preferences.getLong(timestampKey(key), 0L)
            if (timestamp <= 0L || now - timestamp >= CACHE_TTL_MS) remove(key)
        }
    }

    private fun timestampKey(key: String) = "$key$TIMESTAMP_SUFFIX"

    private companion object {
        const val TIMESTAMP_SUFFIX = ":cached_at"
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
