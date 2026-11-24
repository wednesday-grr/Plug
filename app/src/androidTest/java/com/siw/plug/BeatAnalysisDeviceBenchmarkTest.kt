/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import android.content.Context
import android.content.ComponentName
import android.media.MediaCodecList
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import com.siw.plug.logic.PlugPlaybackService
import com.siw.plug.logic.utils.MediaItemList
import com.siw.plug.logic.utils.automix.AnalysisKey
import com.siw.plug.logic.utils.automix.AnalysisWindow
import com.siw.plug.logic.utils.automix.AudioAnalysisScheduler
import com.siw.plug.logic.utils.automix.BeatGridCacheRead
import com.siw.plug.logic.utils.automix.BeatGridCacheRecord
import com.siw.plug.logic.utils.automix.SourceTime
import com.siw.plug.logic.utils.automix.SourceTimeline
import com.siw.plug.logic.utils.automix.TimelineMapping
import com.siw.plug.logic.utils.automix.TimelineOrigin
import com.siw.plug.logic.utils.automix.TimingFact
import com.siw.plug.logic.utils.automix.TrackAnalysisCache
import com.siw.plug.logic.utils.automix.TrackBeatAnalysis
import com.siw.plug.logic.utils.automix.TrackDspAnalyzer
import com.siw.plug.logic.utils.automix.TrackPcmDecoder
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uk.akane.libphonograph.items.EXTRA_MODIFIED_DATE
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BeatAnalysisDeviceBenchmarkTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun benchmarkLocalAudioFixtures() {
        runBlocking {
            val directories = listOfNotNull(
                File(context.filesDir, "beat-benchmark"),
                context.getExternalFilesDir("beat-benchmark"),
            )
            val directory = directories.firstOrNull { candidate ->
                candidate.listFiles()?.any { it.isAudioFixture() } == true
            } ?: directories.first()
            val fixtures = directory.listFiles()
                ?.filter { it.isAudioFixture() }
                ?.sortedBy { it.name }
                .orEmpty()
            assumeTrue("Provide local audio fixtures in ${directory.path}", fixtures.isNotEmpty())

            val results = JSONArray()
            val benchmarkResults = fixtures.mapIndexed { index, file ->
                benchmark("fixture-${index + 1}", file)
            }
            benchmarkResults.forEach { results.put(it) }

            val cancellationIndex = benchmarkResults.mapIndexedNotNull { index, result ->
                if (result.optString("status") == "decoded") index to result.getLong("file_size_bytes")
                else null
            }.maxByOrNull { it.second }?.first
            val cancellationFile = cancellationIndex?.let(fixtures::get)
            val cancellation = cancellationFile?.let { measureCancellation(it) }
            val report = JSONObject()
                .put("device_model", android.os.Build.MODEL)
                .put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("device_audio_decoder_mime_types", JSONArray(audioDecoderMimeTypes()))
                .put("fixtures", results)
                .put("cancellation_response_ms", cancellation)
                .put("cancellation_fixture", cancellationIndex?.let { "fixture-${it + 1}" })
            val reportJson = report.toString()
            File(directory, "benchmark-results.json").writeText(report.toString(2))
            reportJson.chunked(2_000).forEachIndexed { index, chunk ->
                Log.i(TAG, "report[$index]:$chunk")
            }
        }
    }

    @Test
    fun mediaSessionCommandRunsOfflineBeatAnalysisAndReturnsStatus() = runBlocking {
        val directories = listOfNotNull(
            File(context.filesDir, "beat-benchmark"),
            context.getExternalFilesDir("beat-benchmark"),
        )
        val directory = directories.firstOrNull { candidate ->
            candidate.listFiles()?.any { it.isAudioFixture() } == true
        } ?: directories.first()
        val fixture = directory.listFiles()?.firstOrNull { it.isAudioFixture() }
        assumeTrue("Provide a local audio fixture in ${directory.path}", fixture != null)
        val file = checkNotNull(fixture)
        val item = mediaItem(file, Uri.fromFile(file), "session-test-${UUID.randomUUID()}")
        val ownerId = "session-test-${UUID.randomUUID()}"
        val token = SessionToken(context, ComponentName(context, PlugPlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync()
            .get(30, TimeUnit.SECONDS)
        try {
            val requested = sendBeatAnalysisCommand(controller, "request", ownerId, item)
            assertEquals(SessionResult.RESULT_SUCCESS, requested.resultCode)
            var status = requested.extras.getString("beat_analysis_status")
                ?: error("Beat analysis response omitted status")
            withTimeout(120_000) {
                while (status == "PENDING" || status == "ANALYZING") {
                    kotlinx.coroutines.delay(100)
                    val response = sendBeatAnalysisCommand(controller, "status", ownerId, item)
                    assertEquals(SessionResult.RESULT_SUCCESS, response.resultCode)
                    status = response.extras.getString("beat_analysis_status")
                        ?: error("Beat analysis response omitted status")
                }
            }
            assertTrue("Unexpected terminal beat-analysis status: $status",
                status in setOf("DETECTED", "EMPTY", "UNAVAILABLE"))
        } finally {
            sendBeatAnalysisCommand(controller, "cancel", ownerId, item)
            withContext(Dispatchers.Main.immediate) { controller.release() }
        }
    }

    private suspend fun sendBeatAnalysisCommand(
        controller: MediaController,
        operation: String,
        ownerId: String,
        item: MediaItem,
    ): SessionResult {
        val args = Bundle().apply {
            putString("operation", operation)
            putString("owner_id", ownerId)
            putBinder("item", MediaItemList(listOf(item)))
        }
        val result = withContext(Dispatchers.Main.immediate) {
            controller.sendCustomCommand(
                SessionCommand(PlugPlaybackService.SERVICE_BEAT_ANALYSIS, Bundle.EMPTY),
                args,
            )
        }
        return result.await()
    }

    private suspend fun benchmark(id: String, file: File): JSONObject {
        return try {
            benchmarkDecodedFile(id, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Audio fixture $id failed", e)
            JSONObject()
                .put("id", id)
                .put("format_hint", file.extension.lowercase())
                .put("file_size_bytes", file.length())
                .put("status", "unsupported_or_failed")
                .put("error", "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                    .replace(file.absolutePath, "<fixture>").replace(file.name, "<fixture>"))
        }
    }

    private suspend fun benchmarkDecodedFile(id: String, file: File): JSONObject {
        val context = this.context
        val uri = Uri.fromFile(file)
        val sourceMimes = audioMimes(uri)
        check(sourceMimes.isNotEmpty()) { "No audio track detected by MediaExtractor" }
        val item = mediaItem(file, uri)
        val key = checkNotNull(AnalysisKey.from(item))
        val decoder = TrackPcmDecoder(context)
        val pssBeforeKb = Debug.getPss()
        val cpuBeforeNs = Debug.threadCpuTimeNanos()
        val analysisStartNs = SystemClock.elapsedRealtimeNanos()
        val decoded = decoder.decode(uri)
        val analyzed = TrackDspAnalyzer.analyzeWithBeatTracking(
            decoded.samples,
            decoded.startMs,
            timeline(key),
        )
        val analysisDurationNs = SystemClock.elapsedRealtimeNanos() - analysisStartNs
        val cpuDurationNs = Debug.threadCpuTimeNanos() - cpuBeforeNs
        val pssAfterKb = Debug.getPss()
        val record = cacheRecord(key, decoded, analyzed)

        val cache = TrackAnalysisCache(context)
        val cacheWriteStartNs = SystemClock.elapsedRealtimeNanos()
        cache.storeBeatGrid(key, record)
        val cacheWriteNs = SystemClock.elapsedRealtimeNanos() - cacheWriteStartNs
        cache.close()

        val readCache = TrackAnalysisCache(context)
        val missKey = checkNotNull(AnalysisKey.from(mediaItem(
            file,
            Uri.fromFile(File(file.parentFile, "${file.nameWithoutExtension}-cache-miss.${file.extension}")),
        )))
        val cacheMissStartNs = SystemClock.elapsedRealtimeNanos()
        val cacheMiss = readCache.loadBeatGrid(missKey)
        val cacheMissNs = SystemClock.elapsedRealtimeNanos() - cacheMissStartNs
        check(cacheMiss !is BeatGridCacheRead.Available) {
            "Unexpected cache entry for ${file.name} miss key"
        }

        val cacheHitStartNs = SystemClock.elapsedRealtimeNanos()
        val cached = readCache.loadBeatGrid(key)
        val cacheHitNs = SystemClock.elapsedRealtimeNanos() - cacheHitStartNs
        readCache.close()
        check(cached is BeatGridCacheRead.Available) {
            "Persisted result was not readable for ${file.name}"
        }

        val durationMs = decoded.durationMs
        check(durationMs > 0) { "Invalid source duration for ${file.name}: $durationMs ms" }
        val windowDurationMs = decoded.samples.size.toDouble() * 1000 / TrackDspAnalyzer.SAMPLE_RATE
        val analysisDurationMs = analysisDurationNs / 1_000_000.0
        return JSONObject()
            .put("id", id)
            .put("format_hint", file.extension.lowercase())
            .put("extractor_audio_mime_types", JSONArray(sourceMimes))
            .put("status", "decoded")
            .put("file_size_bytes", file.length())
            .put("track_duration_ms", durationMs)
            .put("analyzed_window_duration_ms", windowDurationMs)
            .put("decoded_window_samples", decoded.samples.size)
            .put("analysis_duration_ms", analysisDurationMs)
            .put("analysis_to_window_ratio", analysisDurationMs / windowDurationMs)
            .put("analysis_to_track_ratio", analysisDurationMs / durationMs)
            .put("thread_cpu_ms", cpuDurationNs / 1_000_000.0)
            .put("process_pss_before_kb", pssBeforeKb)
            .put("process_pss_after_kb", pssAfterKb)
            .put("cache_miss_ms", cacheMissNs / 1_000_000.0)
            .put("cache_write_ms", cacheWriteNs / 1_000_000.0)
            .put("cache_hit_ms", cacheHitNs / 1_000_000.0)
            .put("detected_beats", analyzed.tracking.grid?.events?.size ?: 0)
            .put("tempo_ambiguous", analyzed.tracking.ambiguous)
            .put("cache_state", cached.record.state.name)
    }

    private fun audioMimes(uri: Uri): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).mapNotNull { track ->
                extractor.getTrackFormat(track)
                    .takeIf { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?.getString(MediaFormat.KEY_MIME)
            }
        } finally {
            extractor.release()
        }
    }

    private fun audioDecoderMimeTypes(): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.asSequence() }
            .filter { it.startsWith("audio/") }
            .distinct()
            .sorted()
            .toList()

    private suspend fun measureCancellation(file: File): Double? {
        val uri = Uri.fromFile(file)
        val item = mediaItem(file, uri, "${file.nameWithoutExtension}-cancel")
        check(AnalysisKey.from(item) != null)
        val started = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val cancelledAtNs = AtomicLong(0)
        val responseNs = CompletableDeferred<Long>()
        val cache = TrackAnalysisCache(context)
        val scheduler = AudioAnalysisScheduler(context, cache, analyzeBeat = { requestedItem ->
            val requestedUri = checkNotNull(requestedItem.localConfiguration).uri
            val decoded = TrackPcmDecoder(context).decode(requestedUri)
            val requestedKey = checkNotNull(AnalysisKey.from(requestedItem))
            started.complete(Unit)
            try {
                val analyzed = TrackDspAnalyzer.analyzeWithBeatTracking(
                    decoded.samples,
                    decoded.startMs,
                    timeline(requestedKey),
                )
                completed.complete(Unit)
                cacheRecord(requestedKey, decoded, analyzed)
            } catch (e: CancellationException) {
                val requestedAt = cancelledAtNs.get()
                if (requestedAt != 0L) responseNs.complete(SystemClock.elapsedRealtimeNanos() - requestedAt)
                throw e
            }
        })
        return try {
            scheduler.requestBeatAnalysis(item, "device-benchmark")
            withTimeout(120_000) { started.await() }
            delay(10)
            if (completed.isCompleted) null else {
                cancelledAtNs.set(SystemClock.elapsedRealtimeNanos())
                scheduler.cancelBeatAnalysis("device-benchmark")
                withTimeout(30_000) { responseNs.await() } / 1_000_000.0
            }
        } finally {
            scheduler.close()
        }
    }

    private fun mediaItem(
        file: File,
        uri: Uri,
        mediaId: String = file.nameWithoutExtension,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putLong(EXTRA_MODIFIED_DATE, file.lastModified())
        }).build())
        .build()

    private fun File.isAudioFixture(): Boolean =
        isFile && extension.lowercase() in setOf("mp3", "flac", "wav")

    private fun timeline(key: AnalysisKey) = SourceTimeline(
        key.uri,
        TimelineOrigin.DECODER_PRESENTATION,
        TimelineMapping.UNKNOWN,
        TimingFact.UNKNOWN,
        TimingFact.UNKNOWN,
        TimingFact.UNKNOWN,
        TimingFact.UNKNOWN,
    )

    private fun cacheRecord(
        key: AnalysisKey,
        decoded: TrackPcmDecoder.Window,
        analyzed: TrackBeatAnalysis,
    ): BeatGridCacheRecord {
        val startUs = SourceTime.millisecondsToMicros(decoded.startMs)
        val durationUs = SourceTime.framesToMicros(
            decoded.samples.size.toLong(),
            TrackDspAnalyzer.SAMPLE_RATE,
        )
        val window = AnalysisWindow(startUs, startUs + durationUs)
        return BeatGridCacheRecord(
            key.uri,
            TrackDspAnalyzer.BEAT_ANALYSIS_CONFIG_VERSION,
            analyzed.tracking,
            window,
            timeline(key),
        )
    }

    private companion object {
        const val TAG = "BeatDeviceBenchmark"
    }
}
