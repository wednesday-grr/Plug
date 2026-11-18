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
package org.akanework.gramophone.logic.utils.automix

import android.content.Context
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class AudioAnalysisScheduler(
    context: Context,
    val cache: TrackAnalysisCache,
    private val analyze: (suspend (MediaItem) -> TrackAnalysis)? = null,
) {
    private val decoder = TrackPcmDecoder(context.applicationContext)
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "TrackAudioAnalysis")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var queue = emptyList<MediaItem>()
    private var library = emptyList<MediaItem>()
    private var idle = false
    private val attempted = mutableSetOf<AnalysisKey>()
    private var currentJob: Job? = null
    private var currentIsLibrary = false
    private var currentKey: AnalysisKey? = null
    private var libraryIndex = 0

    init {
        scope.launch {
            while (isActive) {
                signal.receive()
                while (isActive) {
                    val next = synchronized(lock) {
                        val queued = queue.firstOrNull { AnalysisKey.from(it)?.let { k -> k !in attempted } == true }
                        var libraryCandidate: MediaItem? = null
                        if (queued == null && idle) {
                            while (libraryIndex < library.size && libraryCandidate == null) {
                                val item = library[libraryIndex++]
                                if (AnalysisKey.from(item)?.let { it !in attempted } == true)
                                    libraryCandidate = item
                            }
                        }
                        val candidate = queued ?: libraryCandidate
                        candidate?.let {
                            val key = checkNotNull(AnalysisKey.from(it))
                            attempted.add(key)
                            currentIsLibrary = queued == null
                            currentKey = key
                            it to key
                        }
                    } ?: break
                    val job = launch {
                        try {
                            if (cache.load(next.second) == null) {
                                val result = if (analyze != null) analyze.invoke(next.first) else {
                                    val window = decoder.decode(checkNotNull(next.first.localConfiguration).uri)
                                    TrackDspAnalyzer.analyze(window.samples, window.startMs)
                                }
                                cache.store(next.second, result)
                            }
                        } catch (e: CancellationException) {
                            synchronized(lock) {
                                attempted.remove(next.second)
                                if (currentIsLibrary) libraryIndex = 0
                            }
                            throw e
                        } catch (e: Exception) {
                            Log.w("AudioAnalysis", "Analysis failed for ${next.second.mediaId}", e)
                        }
                    }
                    synchronized(lock) {
                        currentJob = job
                        if ((currentIsLibrary && !idle) ||
                            (currentIsLibrary && queue.any {
                                AnalysisKey.from(it)?.let { key -> key !in attempted } == true
                            })
                        ) job.cancel()
                    }
                    job.join()
                    synchronized(lock) {
                        if (currentJob === job) {
                            currentJob = null
                            currentKey = null
                        }
                    }
                    delay(250)
                }
            }
        }
    }

    fun updateQueue(items: List<MediaItem>) {
        synchronized(lock) {
            if (queue == items) return
            queue = items.toList()
            val first = queue.firstNotNullOfOrNull { AnalysisKey.from(it) }
            if (currentIsLibrary || (first != null && currentKey != first && first !in attempted))
                currentJob?.cancel()
        }
        signal.trySend(Unit)
    }

    fun updateLibrary(items: List<MediaItem>) {
        synchronized(lock) {
            library = items.toList()
            libraryIndex = 0
        }
        signal.trySend(Unit)
    }

    fun setIdle(value: Boolean) {
        synchronized(lock) {
            idle = value
            if (!value && currentIsLibrary) currentJob?.cancel()
        }
        signal.trySend(Unit)
    }

    fun close() {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            cache.close()
            dispatcher.close()
        }
        scope.cancel()
        signal.close()
    }
}
