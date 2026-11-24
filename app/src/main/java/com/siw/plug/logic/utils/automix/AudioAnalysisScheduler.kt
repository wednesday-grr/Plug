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
package com.siw.plug.logic.utils.automix

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
import com.siw.plug.BuildConfig

class AudioAnalysisScheduler(
    context: Context,
    val cache: TrackAnalysisCache,
    private val analyzeBeat: (suspend (MediaItem) -> BeatGridCacheRecord)? = null,
    private val analyze: (suspend (MediaItem) -> TrackAnalysis)? = null,
) {
    enum class DisplayStatus { ANALYZING, READY, FAILED, PENDING }
    enum class BeatAnalysisStatus { MISSING, PENDING, ANALYZING, FAILED, UNAVAILABLE, EMPTY, DETECTED, INVALIDATED }

    private data class BeatOwnerRequest(val key: AnalysisKey, val generation: Long)
    private data class ScheduledWork(
        val item: MediaItem,
        val key: AnalysisKey,
        val isLibrary: Boolean,
        val beatOwner: String? = null,
        val beatGeneration: Long = 0,
    ) {
        val isBeat: Boolean get() = beatOwner != null
    }

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
    private val failures = ConcurrentHashMap<AnalysisKey, Boolean>()
    private val beatFailures = ConcurrentHashMap<AnalysisKey, Boolean>()
    private val beatOwnerRequests = mutableMapOf<String, BeatOwnerRequest>()
    private val pendingBeatRequests = linkedMapOf<AnalysisKey, MediaItem>()
    private var beatGeneration = 0L
    private var currentBeatKey: AnalysisKey? = null
    private var currentIsBeat = false

    init {
        scope.launch {
            while (isActive) {
                signal.receive()
                while (isActive) {
                    val next = synchronized(lock) {
                        val queued = queue.firstOrNull { AnalysisKey.from(it)?.let { k -> cache.selectedKey(k) !in attempted } == true }
                        val beatRequest = if (queued == null) pendingBeatRequests.entries.firstOrNull { entry ->
                            beatOwnerRequests.values.any { it.key == entry.key }
                        } else null
                        var libraryCandidate: MediaItem? = null
                        if (queued == null && beatRequest == null && idle) {
                            while (libraryIndex < library.size && libraryCandidate == null) {
                                val item = library[libraryIndex++]
                                if (AnalysisKey.from(item)?.let { cache.selectedKey(it) !in attempted } == true)
                                    libraryCandidate = item
                            }
                        }
                        when {
                            queued != null -> {
                                val key = cache.selectedKey(checkNotNull(AnalysisKey.from(queued)))
                                attempted.add(key)
                                currentIsLibrary = false
                                currentIsBeat = false
                                currentBeatKey = null
                                currentKey = key
                                ScheduledWork(queued, key, false)
                            }
                            beatRequest != null -> {
                                pendingBeatRequests.remove(beatRequest.key)
                                val owner = beatOwnerRequests.entries.first { it.value.key == beatRequest.key }
                                currentIsLibrary = false
                                currentIsBeat = true
                                currentBeatKey = beatRequest.key
                                currentKey = beatRequest.key
                                ScheduledWork(beatRequest.value, beatRequest.key, false,
                                    owner.key, owner.value.generation)
                            }
                            libraryCandidate != null -> {
                                val key = cache.selectedKey(checkNotNull(AnalysisKey.from(libraryCandidate)))
                                attempted.add(key)
                                currentIsLibrary = true
                                currentIsBeat = false
                                currentBeatKey = null
                                currentKey = key
                                ScheduledWork(libraryCandidate, key, true)
                            }
                            else -> null
                        }
                    } ?: break
                    val job = launch {
                        try {
                            if (next.isBeat) {
                                val existing = cache.loadBeatGrid(next.key)
                                if (existing !is BeatGridCacheRead.Available) {
                                    val result = analyzeBeat?.invoke(next.item) ?: analyzeBeatGrid(next.item, next.key)
                                    currentCoroutineContext().ensureActive()
                                    if (!isCurrentBeatRequest(next)) throw CancellationException("Beat request superseded")
                                    if (AnalysisKey.from(next.item) != next.key)
                                        throw CancellationException("Beat analysis source identity changed")
                                    if (cache.loadBeatGrid(next.key) !is BeatGridCacheRead.Available)
                                        cache.storeBeatGrid(next.key, result)
                                }
                                beatFailures.remove(next.key)
                            } else {
                                val cached = cache.load(next.key)
                                if (cached == null || (analyze == null && cached.edgeAnalysis == null)) {
                                    val result = if (analyze != null) analyze.invoke(next.item) else {
                                        val uri = checkNotNull(next.item.localConfiguration).uri
                                        val sourceKey = AnalysisKey.from(next.item)
                                        val sharedBeatAnalysis = if (next.key.analyzer == "existing" &&
                                            sourceKey != null && hasBeatRequest(sourceKey)
                                        ) {
                                            val decoded = decoder.decode(uri)
                                            val timeline = sourceTimeline(sourceKey)
                                            val beatAnalysis = TrackDspAnalyzer.analyzeWithBeatTracking(
                                                decoded.samples, decoded.startMs, timeline,
                                            )
                                            val record = beatRecord(sourceKey, decoded.samples, decoded.startMs,
                                                beatAnalysis.tracking, timeline)
                                            currentCoroutineContext().ensureActive()
                                            if (isCurrentBeatRequest(sourceKey) &&
                                                cache.loadBeatGrid(sourceKey) !is BeatGridCacheRead.Available
                                            ) {
                                                cache.storeBeatGrid(sourceKey, record)
                                                synchronized(lock) { pendingBeatRequests.remove(sourceKey) }
                                            }
                                            beatAnalysis.analysis
                                        } else null
                                        val base = cached ?: if (next.key.analyzer == "essentia") {
                                            check(BuildConfig.HAS_ESSENTIA) { "This build does not include Essentia" }
                                            EssentiaTrackAnalyzer.analyze(decoder.decode(uri, sampleRate = 44_100))
                                        } else (sharedBeatAnalysis ?: decoder.decode(uri).let {
                                            TrackDspAnalyzer.analyze(it.samples, it.startMs)
                                        }).let { analysis ->
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
                                            Log.w("AudioAnalysis", "Edge analysis failed for ${next.key.mediaId}", e)
                                            null
                                        }
                                        base.copy(edgeAnalysis = edges)
                                    }
                                    currentCoroutineContext().ensureActive()
                                    cache.store(next.key, result)
                                }
                                if (analyze == null) measureLoudnessIfMissing(next.item, next.key)
                                if (analyze == null) measureEnergyIfMissing(next.item, next.key)
                                failures.remove(next.key)
                            }
                        } catch (e: CancellationException) {
                            synchronized(lock) {
                                if (next.isBeat) {
                                    if (beatOwnerRequests.values.any { it.key == next.key })
                                        pendingBeatRequests[next.key] = next.item
                                } else {
                                    attempted.remove(next.key)
                                    if (next.isLibrary) libraryIndex = 0
                                }
                            }
                            throw e
                        } catch (e: Exception) {
                            if (next.isBeat) beatFailures[next.key] = true else failures[next.key] = true
                            Log.w("AudioAnalysis", "Analysis failed for ${next.key.mediaId}", e)
                        }
                    }
                    synchronized(lock) {
                        currentJob = job
                        if (next.isBeat && hasQueuedAnalysis() ||
                            !next.isBeat && (next.key.analyzer != cache.selectedAnalyzer || (next.isLibrary && !idle) ||
                            (next.isLibrary && queue.any {
                                AnalysisKey.from(it)?.let { key -> cache.selectedKey(key) !in attempted } == true
                            }))
                        ) job.cancel()
                    }
                    job.join()
                    synchronized(lock) {
                        if (currentJob === job) {
                            currentJob = null
                            currentKey = null
                            currentIsBeat = false
                            currentBeatKey = null
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
            if (!currentIsBeat) currentJob?.cancel()
        }

        signal.trySend(Unit)
    }

    fun analysisFailed(key: AnalysisKey): Boolean = failures.containsKey(cache.selectedKey(key))

    fun beatAnalysisFailed(key: AnalysisKey): Boolean = beatFailures.containsKey(key)

    fun beatAnalysisStatus(key: AnalysisKey): BeatAnalysisStatus {
        when (val cached = cache.peekBeatGrid(key)) {
            is BeatGridCacheRead.Available -> return when (cached.record.state) {
                BeatGridCacheState.UNAVAILABLE -> BeatAnalysisStatus.UNAVAILABLE
                BeatGridCacheState.EMPTY -> BeatAnalysisStatus.EMPTY
                BeatGridCacheState.DETECTED -> BeatAnalysisStatus.DETECTED
                BeatGridCacheState.INVALIDATED -> BeatAnalysisStatus.INVALIDATED
            }
            BeatGridCacheRead.Invalidated -> return BeatAnalysisStatus.INVALIDATED
            BeatGridCacheRead.Missing -> Unit
        }
        return synchronized(lock) {
            when {
                beatFailures.containsKey(key) -> BeatAnalysisStatus.FAILED
                currentIsBeat && currentBeatKey == key && currentJob?.isActive == true ->
                    BeatAnalysisStatus.ANALYZING
                pendingBeatRequests.containsKey(key) || beatOwnerRequests.values.any { it.key == key } ->
                    BeatAnalysisStatus.PENDING
                else -> BeatAnalysisStatus.MISSING
            }
        }
    }

    /** Requests one offline beat analysis for an owner; duplicate source requests are coalesced. */
    fun requestBeatAnalysis(item: MediaItem, ownerId: String): Boolean {
        require(ownerId.isNotBlank())
        val key = AnalysisKey.from(item) ?: return false
        if (cache.peekBeatGrid(key) is BeatGridCacheRead.Available) return false
        synchronized(lock) {
            val previous = beatOwnerRequests[ownerId]
            if (previous?.key == key && !beatFailures.containsKey(key)) return true
            val generation = ++beatGeneration
            beatOwnerRequests[ownerId] = BeatOwnerRequest(key, generation)
            if (previous != null && previous.key != key &&
                beatOwnerRequests.values.none { it.key == previous.key }
            ) {
                pendingBeatRequests.remove(previous.key)
                if (currentBeatKey == previous.key) currentJob?.cancel()
            }
            if (currentBeatKey != key) pendingBeatRequests[key] = item
            beatFailures.remove(key)
            if (currentIsLibrary) currentJob?.cancel()
        }
        signal.trySend(Unit)
        return true
    }

    fun cancelBeatAnalysis(ownerId: String) {
        synchronized(lock) {
            val request = beatOwnerRequests.remove(ownerId) ?: return
            if (beatOwnerRequests.values.none { it.key == request.key }) {
                pendingBeatRequests.remove(request.key)
                if (currentBeatKey == request.key) currentJob?.cancel()
            }
        }
        signal.trySend(Unit)
    }

    fun displayStatus(key: AnalysisKey): DisplayStatus {
        val selected = cache.selectedKey(key)
        if (cache.peek(selected) != null) return DisplayStatus.READY
        return synchronized(lock) {
            when {
                failures.containsKey(selected) -> DisplayStatus.FAILED
                !currentIsBeat && currentKey == selected && currentJob?.isActive == true -> DisplayStatus.ANALYZING
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
            if (currentIsBeat && hasQueuedAnalysis() || currentIsLibrary ||
                (first != null && currentKey != first && first !in attempted)
            )
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

    private fun isCurrentBeatRequest(work: ScheduledWork): Boolean = synchronized(lock) {
        val owner = checkNotNull(work.beatOwner)
        beatOwnerRequests[owner]?.let {
            it.key == work.key && it.generation == work.beatGeneration
        } == true || beatOwnerRequests.any { (currentOwner, request) ->
            currentOwner != owner && request.key == work.key
        }
    }

    private fun hasBeatRequest(key: AnalysisKey): Boolean = synchronized(lock) {
        beatOwnerRequests.values.any { it.key == key }
    }

    private fun hasQueuedAnalysis(): Boolean = synchronized(lock) {
        queue.any { item ->
            AnalysisKey.from(item)?.let { cache.selectedKey(it) !in attempted } == true
        }
    }

    private fun isCurrentBeatRequest(key: AnalysisKey): Boolean = synchronized(lock) {
        beatOwnerRequests.values.any { it.key == key }
    }

    /**
     * Loudness comes from a full-band 60 s window of the middle of the track, not the whole track. Failure is
     * non-fatal: playback then treats loudness as unavailable.
     */
    private suspend fun measureLoudnessIfMissing(item: MediaItem, key: AnalysisKey) {
        try {
            if (cache.loadLoudness(key) != null) return
            val uri = checkNotNull(item.localConfiguration).uri
            val window = decoder.decodeFullBand(uri)
            val measured = LoudnessMeter.measure(window.samples, window.sampleRate, window.channels)
            currentCoroutineContext().ensureActive()
            cache.storeLoudness(key, measured ?: MeasuredLoudness(
                null, null, null, window.samples.size / window.channels / window.sampleRate.toFloat(),
                window.sampleRate / 2f))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AudioAnalysis", "Loudness measurement failed for ${key.mediaId}", e)
        }
    }

    private suspend fun measureEnergyIfMissing(item: MediaItem, key: AnalysisKey) {
        try {
            if (cache.loadEnergy(key) != null) return
            val uri = checkNotNull(item.localConfiguration).uri
            val start = decoder.decode(uri, TrackPcmDecoder.Region.START)
            val end = decoder.decode(uri, TrackPcmDecoder.Region.END)
            currentCoroutineContext().ensureActive()
            cache.storeEnergy(key, TrackEnergy(
                EnergyAnalyzer.analyze(start.samples, TrackDspAnalyzer.SAMPLE_RATE, start.startMs),
                EnergyAnalyzer.analyze(end.samples, TrackDspAnalyzer.SAMPLE_RATE, end.startMs),
                end.durationMs,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AudioAnalysis", "Energy analysis failed for ${key.mediaId}", e)
        }
    }
    private suspend fun analyzeBeatGrid(item: MediaItem, key: AnalysisKey): BeatGridCacheRecord {
        val uri = checkNotNull(item.localConfiguration).uri
        val decoded = decoder.decode(uri)
        val timeline = sourceTimeline(key)
        val result = TrackDspAnalyzer.analyzeWithBeatTracking(decoded.samples, decoded.startMs, timeline)
        return beatRecord(key, decoded.samples, decoded.startMs, result.tracking, timeline)
    }

    private fun sourceTimeline(key: AnalysisKey) = SourceTimeline(
        key.uri, TimelineOrigin.DECODER_PRESENTATION, TimelineMapping.UNKNOWN,
        TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN,
    )

    private fun beatRecord(
        key: AnalysisKey,
        samples: FloatArray,
        startMs: Long,
        tracking: BeatTrackingResult,
        timeline: SourceTimeline,
    ): BeatGridCacheRecord {
        val startUs = SourceTime.millisecondsToMicros(startMs)
        val durationUs = SourceTime.framesToMicros(samples.size.toLong(), TrackDspAnalyzer.SAMPLE_RATE)
        require(startUs <= Long.MAX_VALUE - durationUs)
        val window = AnalysisWindow(startUs, startUs + durationUs)
        tracking.grid?.let { require(it.window == window) }
        return BeatGridCacheRecord(
            key.uri,
            TrackDspAnalyzer.BEAT_ANALYSIS_CONFIG_VERSION,
            tracking,
            window,
            timeline,
        )
    }
}
