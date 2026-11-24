/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.siw.plug

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import com.siw.plug.logic.utils.automix.AudioAnalysisScheduler
import com.siw.plug.logic.utils.automix.AnalysisKey
import com.siw.plug.logic.utils.automix.AnalysisWindow
import com.siw.plug.logic.utils.automix.BeatGridCacheRead
import com.siw.plug.logic.utils.automix.BeatGridCacheRecord
import com.siw.plug.logic.utils.automix.BeatTempoInterpretation
import com.siw.plug.logic.utils.automix.BeatTrackingResult
import com.siw.plug.logic.utils.automix.DetectedBeatEvent
import com.siw.plug.logic.utils.automix.DetectedBeatGridEvidence
import com.siw.plug.logic.utils.automix.MusicalBeatTracker
import com.siw.plug.logic.utils.automix.SourceTimeline
import com.siw.plug.logic.utils.automix.TrackAnalysis
import com.siw.plug.logic.utils.automix.TrackAnalysisCache
import com.siw.plug.logic.utils.automix.TrackDspAnalyzer
import com.siw.plug.logic.utils.automix.TimelineMapping
import com.siw.plug.logic.utils.automix.TimelineOrigin
import com.siw.plug.logic.utils.automix.TimingFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uk.akane.libphonograph.items.EXTRA_MODIFIED_DATE

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AudioAnalysisSchedulerTest {
    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id)
        .setUri("content://media/track/$id")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putLong(EXTRA_MODIFIED_DATE, 12345)
        }).build()).build()

    @Test
    fun failedAnalysisIsReportedWithoutSwitchingEngine() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val track = item("failure-engine-test")
        val key = checkNotNull(AnalysisKey.from(track))
        val scheduler = AudioAnalysisScheduler(context, TrackAnalysisCache(context)) {
            error("simulated failure")
        }
        try {
            scheduler.setAnalyzer("essentia")
            scheduler.updateQueue(listOf(track))
            withTimeout(10_000) {
                while (!scheduler.analysisFailed(key)) delay(10)
            }
            assertEquals("essentia", scheduler.cache.selectedAnalyzer)
            assertNull(scheduler.cache.peekSelected(key))
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun switchingAnalyzerCancelsWorkAndPreservesSeparateCacheEntries() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val track = item("switch-engine-test")
        val key = checkNotNull(AnalysisKey.from(track))
        val started = Channel<Int>(Channel.UNLIMITED)
        val block = CompletableDeferred<Unit>()
        var calls = 0
        val cache = TrackAnalysisCache(context)
        val scheduler = AudioAnalysisScheduler(context, cache) {
            calls++
            started.send(calls)
            if (calls == 1) block.await()
            TrackAnalysis(130f, "8A", null, 0f, 0, 1000)
        }
        try {
            scheduler.updateQueue(listOf(track))
            assertEquals(1, withTimeout(10_000) { started.receive() })
            scheduler.setAnalyzer("essentia")
            assertEquals(2, withTimeout(10_000) { started.receive() })
            withTimeout(10_000) {
                while (cache.peek(key.copy(analyzer = "essentia")) == null) delay(10)
            }
            assertNull(cache.peek(key))
            assertEquals(130f, cache.peekSelected(key)?.bpm)
        } finally {
            scheduler.close()
            started.close()
        }
    }

    @Test
    fun displayLoadsPersistedAnalysisWithoutWaitingForLibraryWork() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val key = checkNotNull(AnalysisKey.from(item("display-cache-test")))
        val analysis = TrackAnalysis(125f, "8A", 10.0, 0.9f, 0, 1000)
        TrackAnalysisCache(context).also {
            it.store(key, analysis)
            it.close()
        }
        val cache = TrackAnalysisCache(context)
        val scheduler = AudioAnalysisScheduler(context, cache)
        try {
            assertNull(cache.peek(key))
            val load = scheduler.loadCachedForDisplay(key)
            assertNotNull(load)
            withTimeout(10_000) { load!!.join() }
            assertEquals(analysis, cache.peek(key))
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun upcomingQueuePreemptsIdleLibrary() = runBlocking {
        val started = Channel<String>(Channel.UNLIMITED)
        val waitForCancellation = CompletableDeferred<Unit>()
        val scheduler = AudioAnalysisScheduler(
            RuntimeEnvironment.getApplication(), TrackAnalysisCache(RuntimeEnvironment.getApplication())
        ) { item ->
            started.send(item.mediaId)
            if (item.mediaId == "library") waitForCancellation.await()
            TrackAnalysis(120f, "8A", 10.0, 0.9f, 0, 1000)
        }
        try {
            scheduler.updateLibrary(listOf(item("library")))
            scheduler.setIdle(true)
            assertEquals("library", withTimeout(10_000) { started.receive() })
            scheduler.updateQueue(listOf(item("next")))
            assertEquals("next", withTimeout(10_000) { started.receive() })
            assertEquals("library", withTimeout(10_000) { started.receive() })
        } finally {
            scheduler.close()
            started.close()
        }
    }

    @Test
    fun explicitBeatRequestRunsOnceAndPersistsTheGrid() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val track = item("scheduled-beat")
        val key = checkNotNull(AnalysisKey.from(track))
        val completed = CompletableDeferred<Unit>()
        var calls = 0
        val cache = TrackAnalysisCache(context)
        val scheduler = AudioAnalysisScheduler(context, cache, analyzeBeat = {
            calls++
            completed.complete(Unit)
            beatRecord(it)
        })
        try {
            assertEquals(true, scheduler.requestBeatAnalysis(track, "details-screen"))
            withTimeout(10_000) { completed.await() }
            withTimeout(10_000) {
                while (cache.peekBeatGrid(key) !is BeatGridCacheRead.Available) yield()
            }
            assertEquals(1, calls)
            assertEquals(false, scheduler.requestBeatAnalysis(track, "details-screen"))
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun beatAnalysisStatusTracksRunningAndPersistedResults() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val track = item("beat-status")
        val key = checkNotNull(AnalysisKey.from(track))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = AudioAnalysisScheduler(context, TrackAnalysisCache(context), analyzeBeat = {
            started.complete(Unit)
            release.await()
            beatRecord(it)
        })
        try {
            assertEquals(AudioAnalysisScheduler.BeatAnalysisStatus.MISSING, scheduler.beatAnalysisStatus(key))
            scheduler.requestBeatAnalysis(track, "details-screen")
            withTimeout(10_000) { started.await() }
            assertEquals(
                AudioAnalysisScheduler.BeatAnalysisStatus.ANALYZING,
                scheduler.beatAnalysisStatus(key),
            )
            release.complete(Unit)
            withTimeout(10_000) {
                while (scheduler.cache.peekBeatGrid(key) !is BeatGridCacheRead.Available) yield()
            }
            assertEquals(
                AudioAnalysisScheduler.BeatAnalysisStatus.DETECTED,
                scheduler.beatAnalysisStatus(key),
            )
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun persistedBeatGridHitDoesNotRunDetectorAgain() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val track = item("persisted-beat-cache-hit")
        val key = checkNotNull(AnalysisKey.from(track))
        TrackAnalysisCache(context).also {
            it.storeBeatGrid(key, beatRecord(track))
            it.close()
        }
        var detectorCalls = 0
        val scheduler = AudioAnalysisScheduler(context, TrackAnalysisCache(context), analyzeBeat = {
            detectorCalls++
            beatRecord(it)
        })
        try {
            assertEquals(true, scheduler.requestBeatAnalysis(track, "details-screen"))
            withTimeout(10_000) {
                while (scheduler.cache.peekBeatGrid(key) !is BeatGridCacheRead.Available) yield()
            }
            assertEquals(0, detectorCalls)
            assertEquals(false, scheduler.requestBeatAnalysis(track, "details-screen"))
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun duplicateOwnersShareOneBeatAnalysis() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val track = item("duplicate-beat")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val scheduler = AudioAnalysisScheduler(
            context, TrackAnalysisCache(context), analyzeBeat = {
                calls++
                started.complete(Unit)
                release.await()
                beatRecord(it)
            },
        )
        try {
            scheduler.requestBeatAnalysis(track, "owner-one")
            withTimeout(10_000) { started.await() }
            scheduler.requestBeatAnalysis(track, "owner-two")
            release.complete(Unit)
            val key = checkNotNull(AnalysisKey.from(track))
            withTimeout(10_000) {
                while (scheduler.cache.peekBeatGrid(key) !is BeatGridCacheRead.Available) yield()
            }
            assertEquals(1, calls)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun duplicateSameOwnerRequestSharesInFlightAnalysis() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val track = item("same-source-new-generation")
        val key = checkNotNull(AnalysisKey.from(track))
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val scheduler = AudioAnalysisScheduler(
            context, TrackAnalysisCache(context), analyzeBeat = {
                calls++
                if (calls == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    beatRecord(it, "superseded")
                } else beatRecord(it, "current")
            },
        )
        try {
            scheduler.requestBeatAnalysis(track, "same-owner")
            withTimeout(10_000) { firstStarted.await() }
            assertEquals(true, scheduler.requestBeatAnalysis(track, "same-owner"))
            releaseFirst.complete(Unit)
            withTimeout(10_000) {
                while (scheduler.cache.peekBeatGrid(key) !is BeatGridCacheRead.Available) yield()
            }
            assertEquals(1, calls)
            val stored = (scheduler.cache.peekBeatGrid(key) as BeatGridCacheRead.Available).record
            assertEquals("superseded", stored.result.reason)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun queuedPlaybackAnalysisPreemptsBeatWorkForSameSource() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val track = item("same-source-priority")
        val beatStarted = Channel<Int>(Channel.UNLIMITED)
        val analysisStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val releaseSecondBeat = CompletableDeferred<Unit>()
        var beatCalls = 0
        val scheduler = AudioAnalysisScheduler(
            context,
            TrackAnalysisCache(context),
            analyzeBeat = {
                beatCalls++
                beatStarted.send(beatCalls)
                if (beatCalls == 1) {
                    try {
                        CompletableDeferred<Unit>().await()
                    } catch (e: CancellationException) {
                        cancelled.complete(Unit)
                        throw e
                    }
                }
                releaseSecondBeat.await()
                beatRecord(it)
            },
            analyze = {
                analysisStarted.complete(Unit)
                TrackAnalysis(120f, "8A", 10.0, 0.9f, 0, 1000)
            },
        )
        try {
            scheduler.requestBeatAnalysis(track, "beat-owner")
            assertEquals(1, withTimeout(10_000) { beatStarted.receive() })
            scheduler.updateQueue(listOf(track))
            withTimeout(10_000) { cancelled.await() }
            withTimeout(10_000) { analysisStarted.await() }
            assertEquals(2, withTimeout(10_000) { beatStarted.receive() })
            releaseSecondBeat.complete(Unit)
            val key = checkNotNull(AnalysisKey.from(track))
            withTimeout(10_000) {
                while (scheduler.cache.peekBeatGrid(key) !is BeatGridCacheRead.Available) yield()
            }
        } finally {
            scheduler.close()
            beatStarted.close()
        }
    }

    @Test
    fun changingSourceCancelsOldGenerationAndCommitsOnlyCurrentGrid() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val oldTrack = item("old-beat-source")
        val newTrack = item("new-beat-source")
        val started = Channel<String>(Channel.UNLIMITED)
        val oldCancelled = CompletableDeferred<Unit>()
        val scheduler = AudioAnalysisScheduler(
            context, TrackAnalysisCache(context), analyzeBeat = { track ->
                started.send(track.mediaId)
                if (track.mediaId == oldTrack.mediaId) {
                    try {
                        CompletableDeferred<Unit>().await()
                    } catch (e: CancellationException) {
                        oldCancelled.complete(Unit)
                        throw e
                    }
                }
                beatRecord(track)
            },
        )
        try {
            scheduler.requestBeatAnalysis(oldTrack, "same-owner")
            assertEquals(oldTrack.mediaId, withTimeout(10_000) { started.receive() })
            scheduler.requestBeatAnalysis(newTrack, "same-owner")
            withTimeout(10_000) { oldCancelled.await() }
            assertEquals(newTrack.mediaId, withTimeout(10_000) { started.receive() })
            val oldKey = checkNotNull(AnalysisKey.from(oldTrack))
            val newKey = checkNotNull(AnalysisKey.from(newTrack))
            withTimeout(10_000) {
                while (scheduler.cache.peekBeatGrid(newKey) !is BeatGridCacheRead.Available) yield()
            }
            assertEquals(BeatGridCacheRead.Missing, scheduler.cache.peekBeatGrid(oldKey))
        } finally {
            scheduler.close()
            started.close()
        }
    }

    @Test
    fun cancellationStopsBeatWorkAndPreemptsIdleAnalysis() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val beat = item("cancel-beat")
        val library = item("library-analysis")
        val urgent = item("urgent-analysis")
        val beatStarted = CompletableDeferred<Unit>()
        val beatCancelled = CompletableDeferred<Unit>()
        val libraryStarted = CompletableDeferred<Unit>()
        val analysisStarted = CompletableDeferred<Unit>()
        val scheduler = AudioAnalysisScheduler(
            context,
            TrackAnalysisCache(context),
            analyze = { item ->
                if (item.mediaId == library.mediaId) {
                    libraryStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                analysisStarted.complete(Unit)
                TrackAnalysis(120f, "8A", 10.0, 0.9f, 0, 1000)
            },
            analyzeBeat = {
                beatStarted.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } catch (e: CancellationException) {
                    beatCancelled.complete(Unit)
                    throw e
                }
                beatRecord(it)
            },
        )
        try {
            scheduler.updateLibrary(listOf(library))
            scheduler.setIdle(true)
            withTimeout(10_000) { libraryStarted.await() }
            scheduler.requestBeatAnalysis(beat, "beat-owner")
            withTimeout(10_000) { beatStarted.await() }
            scheduler.updateQueue(listOf(urgent))
            withTimeout(10_000) { beatCancelled.await() }
            withTimeout(10_000) { analysisStarted.await() }
            val beatKey = checkNotNull(AnalysisKey.from(beat))
            assertEquals(false, scheduler.beatAnalysisFailed(beatKey))
        } finally {
            scheduler.close()
        }
    }

    private fun beatRecord(item: MediaItem, reason: String = "test"): BeatGridCacheRecord {
        val key = checkNotNull(AnalysisKey.from(item))
        val timeline = SourceTimeline(key.uri, TimelineOrigin.DECODER_PRESENTATION,
            TimelineMapping.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN)
        val window = AnalysisWindow(0, 1_000_000)
        val grid = DetectedBeatGridEvidence(
            listOf(DetectedBeatEvent(0, 100_000, 0.7f, onsetStrength = 0.9f)),
            "scheduler-test", MusicalBeatTracker.ALGORITHM_VERSION, window, timeline, 0.8f, null,
        )
        return BeatGridCacheRecord(
            key.uri,
            TrackDspAnalyzer.BEAT_ANALYSIS_CONFIG_VERSION,
            BeatTrackingResult(grid, emptyList(), BeatTempoInterpretation.PRIMARY, false, reason),
            window,
            timeline,
        )
    }
}
