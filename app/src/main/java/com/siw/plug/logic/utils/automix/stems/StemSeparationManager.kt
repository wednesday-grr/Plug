package com.siw.plug.logic.utils.automix.stems

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class StemFailure { NOT_ELIGIBLE, MODEL_MISSING, MODEL_ERROR, DECODE_ERROR, INTEGRITY, IO }

/** Lifecycle of the stems for one track. Only [Ready] means real, checksum-verified separated audio exists. */
sealed interface StemStatus {
    data object NotRequested : StemStatus
    data object Queued : StemStatus
    data class Processing(val framesDone: Long) : StemStatus
    data class Ready(val entry: StemEntry) : StemStatus
    data class Failed(val reason: StemFailure, val detail: String?) : StemStatus
    data object Cancelled : StemStatus
    /** A previously cached entry failed verification and was discarded; it will be regenerated on the next request. */
    data class Invalid(val detail: String) : StemStatus
}

/**
 * Background pre-analysis queue for stem separation. One job runs at a time on [dispatcher]; the
 * playback thread only ever reads [statuses]. Nothing here is on the audio path.
 */
class StemSeparationManager(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val cache: StemCache,
    private val modelId: String,
    private val modelVersion: String,
    private val isModelInstalled: () -> Boolean,
    private val openModel: () -> SpectrogramModel,
    private val openSource: (uri: String) -> StereoPcmSource,
    private val contentSha256: (uri: String) -> String,
    private val eligibility: () -> Boolean = { true },
) {
    private val state = MutableStateFlow<Map<String, StemStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, StemStatus>> = state.asStateFlow()
    private val jobs = HashMap<String, Job>()
    private val gate = Semaphore(1)

    fun statusOf(trackId: String): StemStatus = state.value[trackId] ?: StemStatus.NotRequested

    /** Queues [trackId]; no-op when it is already queued, running or ready. */
    @Synchronized
    fun request(trackId: String, uri: String) {
        when (val current = statusOf(trackId)) {
            is StemStatus.Queued, is StemStatus.Processing -> return
            is StemStatus.Ready -> if (current.entry.dir.isDirectory) return
            else -> Unit
        }
        if (!eligibility()) { set(trackId, StemStatus.Failed(StemFailure.NOT_ELIGIBLE, null)); return }
        if (!isModelInstalled()) { set(trackId, StemStatus.Failed(StemFailure.MODEL_MISSING, null)); return }
        set(trackId, StemStatus.Queued)
        jobs[trackId] = scope.launch(dispatcher) { run(trackId, uri) }
    }

    @Synchronized
    fun cancel(trackId: String) {
        val job = jobs.remove(trackId) ?: return
        job.cancel()
        if (statusOf(trackId).let { it is StemStatus.Queued || it is StemStatus.Processing }) set(trackId, StemStatus.Cancelled)
    }

    @Synchronized
    fun cancelAll() { jobs.keys.toList().forEach(::cancel) }

    private suspend fun run(trackId: String, uri: String) {
        gate.withPermit {
            try {
                runInterruptible { process(trackId, uri) }
            } catch (e: CancellationException) {
                set(trackId, StemStatus.Cancelled)
                throw e
            } finally {
                synchronized(this) { jobs.remove(trackId) }
            }
        }
    }

    private fun process(trackId: String, uri: String) {
        set(trackId, StemStatus.Processing(0))
        val key = try {
            StemKey(trackId, contentSha256(uri), modelId, modelVersion)
        } catch (t: Throwable) {
            set(trackId, StemStatus.Failed(StemFailure.DECODE_ERROR, t.message)); return
        }
        cache.lookup(key)?.let { hit ->
            if (cache.verify(hit)) { set(trackId, StemStatus.Ready(hit)); return }
            cache.remove(key)
            set(trackId, StemStatus.Invalid("Cached stems failed checksum verification"))
            set(trackId, StemStatus.Processing(0))
        }
        val cancelled = { Thread.currentThread().isInterrupted }
        var writer: StemCache.Writer? = null
        try {
            openModel().use { model ->
                openSource(uri).let { source ->
                    try {
                        val w = cache.beginWrite(key).also { writer = it }
                        StemSeparator(model).separate(source, w, cancelled) { done ->
                            set(trackId, StemStatus.Processing(done))
                        }
                    } finally {
                        (source as? AutoCloseable)?.close()
                    }
                }
            }
            set(trackId, StemStatus.Ready(checkNotNull(writer).commit()))
        } catch (e: CancellationException) {
            writer?.abort()
            throw e
        } catch (e: InterruptedException) {
            writer?.abort()
            throw CancellationException("interrupted")
        } catch (t: Throwable) {
            writer?.abort()
            set(trackId, StemStatus.Failed(classify(t), t.message))
        }
    }

    private fun classify(t: Throwable) = when {
        t is java.io.IOException -> StemFailure.IO
        t.javaClass.name.startsWith("ai.onnxruntime") -> StemFailure.MODEL_ERROR
        t is IllegalStateException || t is IllegalArgumentException -> StemFailure.DECODE_ERROR
        else -> StemFailure.MODEL_ERROR
    }

    private fun set(trackId: String, status: StemStatus) = state.update { it + (trackId to status) }
}
