package com.siw.plug.logic.utils.automix.stems

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Sink for mixed PCM; abstracted so the render loop is testable without Android. */
interface StemAudioOutput {
    /** Blocks until accepted; returns frames written or a negative value on error. */
    fun write(samples: FloatArray, frames: Int): Int
    fun start()
    fun release()
    fun setVolume(volume: Float)
    /** Frames the hardware has played since [start]. */
    fun playedFrames(): Long
}

class AudioTrackStemOutput(sessionId: Int = AudioTrack.ERROR) : StemAudioOutput {
    private val track: AudioTrack

    init {
        val minBytes = AudioTrack.getMinBufferSize(StemMath.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minBytes > 0) { "AudioTrack float stereo unsupported" }
        val builder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(StemMath.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
        track = builder.build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack not initialised" }
    }

    override fun write(samples: FloatArray, frames: Int) = track.write(samples, 0, frames * 2, AudioTrack.WRITE_BLOCKING) / 2
    override fun start() = track.play()
    override fun setVolume(volume: Float) { track.setVolume(volume) }
    override fun playedFrames() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    override fun release() { runCatching { track.stop() }; track.release() }
}

object StemMath {
    const val SAMPLE_RATE = 44_100
}

/**
 * Renders a stem transition on its own audio-priority thread. All heavy lifting happened earlier (cached stems);
 * this only reads PCM files, mixes four sources and writes to the output. Any failure calls [onFailed] exactly once
 * so the caller can restore the ordinary deck crossfade.
 */
class StemPlayer(
    private val outputFactory: () -> StemAudioOutput,
    private val onFailed: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile var framesRendered = 0L
        private set
    @Volatile var lastActiveSources = 0
        private set

    /**
     * Starts rendering [effect]. [durationFrames] is the whole transition; the caller sampled deck positions
     * ([outgoingStartFrame], [incomingStartFrame], 44.1 kHz frames) at [sampledAtNanos] when the transition had
     * progressed [progressAtSampleFrames] frames. Thread start-up and file opening are compensated by advancing
     * both stems and the gain curves by the elapsed time. [onFinished] runs once the tail has been played out.
     */
    fun start(
        outgoing: StemEntry, incoming: StemEntry, effect: StemEffect,
        outgoingStartFrame: Long, incomingStartFrame: Long, durationFrames: Long,
        progressAtSampleFrames: Long = 0L, sampledAtNanos: Long = System.nanoTime(),
        outgoingTrim: (Float) -> Float = { 1f }, incomingTrim: (Float) -> Float = { 1f },
        onFinished: () -> Unit,
    ) {
        check(running.compareAndSet(false, true)) { "StemPlayer already running" }
        framesPlayed = 0
        framesRendered = 0
        val t = Thread({
            var readers: List<StemPcmReader> = emptyList()
            var out: StemAudioOutput? = null
            try {
                readers = listOf(outgoing.vocals, outgoing.accompaniment, incoming.vocals, incoming.accompaniment).map { StemPcmReader(it) }
                out = outputFactory()
                out.setVolume(volume)
                val elapsedFrames = ((System.nanoTime() - sampledAtNanos).coerceAtLeast(0L) * StemMath.SAMPLE_RATE / 1_000_000_000L) +
                    SETUP_SLACK_FRAMES
                readers[0].seekToFrame(outgoingStartFrame + elapsedFrames); readers[1].seekToFrame(outgoingStartFrame + elapsedFrames)
                readers[2].seekToFrame(incomingStartFrame + elapsedFrames); readers[3].seekToFrame(incomingStartFrame + elapsedFrames)
                val base = progressAtSampleFrames + elapsedFrames
                val total = durationFrames - base
                if (total <= 0) { running.set(false); onFinished(); return@Thread }
                totalFrames = total
                val sources = readers.map { r -> object : StemSource { override fun read(out: FloatArray, frames: Int) = r.read(out, frames) } }
                val mixer = StemMixer(sources[0], sources[1], sources[2], sources[3]) { f ->
                    val tf = base + f
                    val progress = (tf.toFloat() / durationFrames).coerceIn(0f, 1f)
                    val g = StemTransitionMath.gains(effect, progress)
                    val leadIn = (f.toFloat() / EDGE_FRAMES).coerceIn(0f, 1f)
                    val leadOut = ((total - f).toFloat() / EDGE_FRAMES).coerceIn(0f, 1f)
                    val o = outgoingTrim(progress) * leadIn
                    val i = incomingTrim(progress) * leadOut
                    StemGains(g.aVocal * o, g.aInstrumental * o, g.bVocal * i, g.bInstrumental * i)
                }
                val buf = FloatArray(BLOCK_FRAMES * 2)
                var written = 0L
                var started = false
                while (running.get() && written < total) {
                    val n = minOf(BLOCK_FRAMES.toLong(), total - written).toInt()
                    mixer.mix(buf, n)
                    lastActiveSources = mixer.lastActiveSources
                    val w = out.write(buf, n)
                    if (w < 0) throw IllegalStateException("AudioTrack write error $w")
                    written += n
                    framesRendered = written
                    if (!started && written >= PREFILL_FRAMES) { out.start(); started = true }
                    if (started) framesPlayed = out.playedFrames()
                }
                if (!started && running.get()) { out.start(); started = true }
                val drainDeadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS
                while (running.get() && out.playedFrames() < written && System.nanoTime() < drainDeadline) {
                    framesPlayed = out.playedFrames()
                    Thread.sleep(5)
                }
                framesPlayed = written
                val finished = running.get()
                // Clear before the callback so it may start another transition.
                running.set(false)
                if (finished) onFinished()
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                if (running.getAndSet(false)) onFailed(t)
            } finally {
                outputRef = null
                runCatching { out?.release() }
                readers.forEach { runCatching { it.close() } }
                running.set(false)
            }
        }, "StemPlayer")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    /** Output frames the hardware has actually played since start (drives deck/stem hand-over gains). */
    @Volatile var framesPlayed = 0L
        private set
    /** Frames this player will write in total; 0 until started. */
    @Volatile var totalFrames = 0L
        private set
    @Volatile private var volume = 1f
    @Volatile private var outputRef: StemAudioOutput? = null

    fun setVolume(v: Float) { volume = v; outputRef?.setVolume(v) }
    val isRunning: Boolean get() = running.get()
    val edgeFrames: Long get() = EDGE_FRAMES

    /** Stops promptly; safe from any thread. The render thread releases the output. */
    fun stop() {
        running.set(false)
        thread?.interrupt()
    }

    private companion object {
        const val BLOCK_FRAMES = 1024
        const val PREFILL_FRAMES = 4096L
        /** Length of the deck-to-stem and stem-to-deck hand-over ramps (300 ms). */
        const val EDGE_FRAMES = 13_230L
        const val SETUP_SLACK_FRAMES = 220L
        const val DRAIN_TIMEOUT_NANOS = 1_000_000_000L
    }
}
