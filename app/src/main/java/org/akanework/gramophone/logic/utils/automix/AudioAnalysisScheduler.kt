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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import org.akanework.gramophone.BuildConfig

class AudioAnalysisScheduler(
    context: Context,
    val cache: TrackAnalysisCache,
    private val analyze: (suspend (MediaItem) -> TrackAnalysis)? = null,
) {
    enum class DisplayStatus { ANALYZING, READY, FAILED, PENDING }

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
    private val displayLoads = mutableMapOf<AnalysisKey, Job>()
    private val failures = ConcurrentHashMap.newKeySet<AnalysisKey>()

    init {
        scope.launch {
            while (isActive) {
                signal.receive()
                while (isActive) {
                    val next = synchronized(lock) {
                        val queued = queue.firstOrNull { AnalysisKey.from(it)?.let { k -> cache.selectedKey(k) !in attempted } == true }
                        var libraryCandidate: MediaItem? = null
                        if (queued == null && idle) {
                            while (libraryIndex < library.size && libraryCandidate == null) {
                                val item = library[libraryIndex++]
                                if (AnalysisKey.from(item)?.let { cache.selectedKey(it) !in attempted } == true)
                                    libraryCandidate = item
                            }
                        }
                        val candidate = queued ?: libraryCandidate
                        candidate?.let {
                            val key = cache.selectedKey(checkNotNull(AnalysisKey.from(it)))
                            attempted.add(key)
                            currentIsLibrary = queued == null
                            currentKey = key
                            it to key
                        }
                    } ?: break
                    val job = launch {
                        try {
                            val cached = cache.load(next.second)
                            if (cached == null || (analyze == null && cached.edgeAnalysis == null)) {
                                val result = if (analyze != null) analyze.invoke(next.first) else {
                                    val uri = checkNotNull(next.first.localConfiguration).uri
                                    val base = cached ?: if (next.second.analyzer == "essentia") {
                                        check(BuildConfig.HAS_ESSENTIA) { "This build does not include Essentia" }
                                        EssentiaTrackAnalyzer.analyze(decoder.decode(uri, sampleRate = 44_100))
                                    } else decoder.decode(uri).let {
                                        val analysis = TrackDspAnalyzer.analyze(it.samples, it.startMs)
                                        analysis.copy(pulseProvenance = if (analysis.beatOffsetMs != null)
                                            BeatEvidenceAdapter.spectralFluxProvenance else null)
                                    }
                                    val edges = try {
                                        val start = decoder.decode(uri, TrackPcmDecoder.Region.START)
                                        val end = decoder.decode(uri, TrackPcmDecoder.Region.END)
                                        val endMs = end.startMs + end.samples.size.toLong() * 1000 / TrackDspAnalyzer.SAMPLE_RATE
                                        if (start.startMs <= 100 && kotlin.math.abs(end.durationMs - endMs) <= 500) {
                                            val edges = TrackEdgeAnalyzer.analyze(start.samples, end.samples, end.durationMs)
                                            fun grid(analysis: TrackAnalysis): BeatPhaseAligner.Grid? {
                                                val bpm = analysis.bpm ?: return null
                                                val offset = analysis.beatOffsetMs ?: return null
                                                return BeatPhaseAligner.Grid(60_000.0 / bpm, offset, analysis.confidence)
                                            }
                                            edges?.copy(
                                                startGrid = grid(TrackDspAnalyzer.analyze(start.samples, start.startMs)),
                                                endGrid = grid(TrackDspAnalyzer.analyze(end.samples, end.startMs)),
                                                startWindowEndMs = start.startMs + start.samples.size.toLong() * 1000 / TrackDspAnalyzer.SAMPLE_RATE,
                                                endWindowStartMs = end.startMs,
                                            )
                                        } else null
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w("AudioAnalysis", "Edge analysis failed for ${next.second.mediaId}", e)
                                        null
                                    }
                                    base.copy(edgeAnalysis = edges)
                                }
                                currentCoroutineContext().ensureActive()
                                cache.store(next.second, result)
                            }
                            failures.remove(next.second)
                        } catch (e: CancellationException) {
                            synchronized(lock) {
                                attempted.remove(next.second)
                                if (currentIsLibrary) libraryIndex = 0
                            }
                            throw e
                        } catch (e: Exception) {
                            failures.add(next.second)
                            Log.w("AudioAnalysis", "Analysis failed for ${next.second.mediaId}", e)
                        }
                    }
                    synchronized(lock) {
                        currentJob = job
                        if (next.second.analyzer != cache.selectedAnalyzer || (currentIsLibrary && !idle) ||
                            (currentIsLibrary && queue.any {
                                AnalysisKey.from(it)?.let { key -> cache.selectedKey(key) !in attempted } == true
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

    fun loadCachedForDisplay(key: AnalysisKey): Job? {
        val selected = cache.selectedKey(key)
        if (cache.peek(selected) != null) return null
        return synchronized(lock) {
            displayLoads[selected] ?: scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    cache.load(selected)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AudioAnalysis", "Unable to load cached analysis for ${key.mediaId}", e)
                } finally {
                    synchronized(lock) { displayLoads.remove(selected) }
                }
            }.also {
                displayLoads[selected] = it
                it.start()
            }
        }
    }

    fun setAnalyzer(analyzer: String) {
        synchronized(lock) {
            if (cache.selectedAnalyzer == analyzer) return
            cache.selectedAnalyzer = analyzer
            attempted.clear()
            failures.clear()
            libraryIndex = 0
            currentJob?.cancel()
        }

        signal.trySend(Unit)
    }

    fun analysisFailed(key: AnalysisKey): Boolean = cache.selectedKey(key) in failures

    fun displayStatus(key: AnalysisKey): DisplayStatus {
        val selected = cache.selectedKey(key)
        if (cache.peek(selected) != null) return DisplayStatus.READY
        return synchronized(lock) {
            when {
                selected in failures -> DisplayStatus.FAILED
                currentKey == selected && currentJob?.isActive == true -> DisplayStatus.ANALYZING
                selected in attempted -> DisplayStatus.FAILED
                else -> DisplayStatus.PENDING
            }
        }
    }

    fun updateQueue(items: List<MediaItem>) {
        synchronized(lock) {
            if (queue == items) return
            queue = items.toList()
            val first = queue.firstNotNullOfOrNull { AnalysisKey.from(it)?.let(cache::selectedKey) }
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
