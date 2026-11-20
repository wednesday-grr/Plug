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
package org.akanework.gramophone

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.akanework.gramophone.logic.utils.automix.AudioAnalysisScheduler
import org.akanework.gramophone.logic.utils.automix.AnalysisKey
import org.akanework.gramophone.logic.utils.automix.TrackAnalysis
import org.akanework.gramophone.logic.utils.automix.TrackAnalysisCache
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
            TrackAnalysis(130f, null, null, 0f, 0, 1000)
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
}
