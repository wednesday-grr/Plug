/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.utils.automix.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in local stability check; these files have no reference annotations. */
@RunWith(AndroidJUnit4::class)
class LocalM4aSmokeTest {
    @Test
    fun localM4aDecodeAnalysisAndCache() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = InstrumentationRegistry.getArguments().getString("expected_m4a_count")?.toInt()
            ?: error("Pass expected_m4a_count to opt in to local M4A testing")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val files = root.walkTopDown().onFail { _, exception -> throw exception }
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
            .sortedBy { it.path }.toList()
        assertEquals("Unexpected local M4A fixture count", expected, files.size)
        val context = instrumentation.targetContext
        val testId = "m4a-smoke-${UUID.randomUUID()}"
        val keys = mutableListOf<AnalysisKey>()
        val failureTypes = mutableMapOf<String, Int>()
        var decoded = 0
        var cacheHits = 0
        try {
            files.forEachIndexed { index, file ->
                val uri = Uri.fromFile(file)
                val key = AnalysisKey("$testId-$index", file.lastModified(), uri.toString())
                keys.add(key)
                try {
                    val window = TrackPcmDecoder(context).decode(uri)
                    check(window.samples.isNotEmpty())
                    val timeline = SourceTimeline(key.uri, TimelineOrigin.DECODER_PRESENTATION,
                        TimelineMapping.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN,
                        TimingFact.UNKNOWN, TimingFact.UNKNOWN)
                    val tracking = TrackDspAnalyzer.analyzeWithBeatTracking(
                        window.samples, window.startMs, timeline).tracking
                    val startUs = SourceTime.millisecondsToMicros(window.startMs)
                    val durationUs = SourceTime.framesToMicros(
                        window.samples.size.toLong(), TrackDspAnalyzer.SAMPLE_RATE)
                    val record = BeatGridCacheRecord(key.uri, TrackDspAnalyzer.BEAT_ANALYSIS_CONFIG_VERSION,
                        tracking, AnalysisWindow(startUs, startUs + durationUs), timeline)
                    TrackAnalysisCache(context).also { cache ->
                        try { cache.storeBeatGrid(key, record) } finally { cache.close() }
                    }
                    decoded++
                    TrackAnalysisCache(context).also { cache ->
                        try {
                            check(cache.loadBeatGrid(key) == BeatGridCacheRead.Available(record))
                            cacheHits++
                        } finally { cache.close() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val type = e.javaClass.simpleName
                    failureTypes[type] = (failureTypes[type] ?: 0) + 1
                }
            }
        } finally {
            TrackAnalysisCache(context).also { cache ->
                try {
                    keys.forEach { key ->
                        cache.writableDatabase.delete("beat_grid", "media_id=? AND modified=? AND uri=? AND analyzer=?",
                            arrayOf(key.mediaId, key.modified.toString(), key.uri, key.analyzer))
                    }
                } finally { cache.close() }
            }
        }
        Log.i("BeatM4aSmoke", "fixtures=${files.size} decoded=$decoded cacheHits=$cacheHits failures=$failureTypes")
        assertEquals("M4A decode/analysis failures by exception type: $failureTypes", files.size, decoded)
        assertEquals("M4A cache round-trip failures by exception type: $failureTypes", files.size, cacheHits)
    }
}
