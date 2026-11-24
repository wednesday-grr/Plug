package com.siw.plug.logic.utils.automix.stems

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Seekable reader of a cached 16-bit little-endian interleaved stereo stem file. Reads past the end return silence. */
class StemPcmReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val totalFrames: Long = raf.length() / BYTES_PER_FRAME
    private var bytes = ByteArray(0)

    fun seekToFrame(frame: Long) {
        raf.seek(frame.coerceIn(0L, totalFrames) * BYTES_PER_FRAME)
    }

    /** Fills [out] with [frames] stereo float frames in [-1, 1]; returns frames of real data read (rest is zero). */
    fun read(out: FloatArray, frames: Int): Int {
        if (bytes.size < frames * BYTES_PER_FRAME) bytes = ByteArray(frames * BYTES_PER_FRAME)
        var got = 0
        val want = frames * BYTES_PER_FRAME
        while (got < want) {
            val n = raf.read(bytes, got, want - got)
            if (n <= 0) break
            got += n
        }
        val readFrames = got / BYTES_PER_FRAME
        for (i in 0 until readFrames * 2) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        out.fill(0f, readFrames * 2, frames * 2)
        return readFrames
    }

    override fun close() = raf.close()

    companion object { const val BYTES_PER_FRAME = 4 }
}

/** One stereo stem source for the mixer. */
interface StemSource {
    fun read(out: FloatArray, frames: Int): Int
}

/**
 * Real four-source mixer (A vocal, A instrumental, B vocal, B instrumental). Gains come from [gainsAt], evaluated per
 * frame index and linearly interpolated within [GAIN_BLOCK] frames, so there are no per-buffer volume steps.
 * Output is soft limited.
 */
class StemMixer(
    private val aVocal: StemSource,
    private val aInstrumental: StemSource,
    private val bVocal: StemSource,
    private val bInstrumental: StemSource,
    private val gainsAt: (frameIndex: Long) -> StemGains,
) {
    private val bufs = Array(4) { FloatArray(0) }
    private var frameIndex = 0L
    private var prev: StemGains = gainsAt(0)

    /** Number of sources that contributed non-zero gain in the last [mix] call. Never reports more than were mixed. */
    var lastActiveSources = 0
        private set

    fun mix(out: FloatArray, frames: Int) {
        if (bufs[0].size < frames * 2) for (i in 0 until 4) bufs[i] = FloatArray(frames * 2)
        aVocal.read(bufs[0], frames); aInstrumental.read(bufs[1], frames)
        bVocal.read(bufs[2], frames); bInstrumental.read(bufs[3], frames)
        var maxActive = 0
        var f = 0
        while (f < frames) {
            val n = minOf(GAIN_BLOCK, frames - f)
            val next = gainsAt(frameIndex + n)
            maxActive = maxOf(maxActive, next.activeSources, prev.activeSources)
            for (k in 0 until n) {
                val t = (k + 1) / n.toFloat()
                val g0 = lerp(prev.aVocal, next.aVocal, t)
                val g1 = lerp(prev.aInstrumental, next.aInstrumental, t)
                val g2 = lerp(prev.bVocal, next.bVocal, t)
                val g3 = lerp(prev.bInstrumental, next.bInstrumental, t)
                val i = (f + k) * 2
                out[i] = StemSoftLimiter.process(bufs[0][i] * g0 + bufs[1][i] * g1 + bufs[2][i] * g2 + bufs[3][i] * g3)
                out[i + 1] = StemSoftLimiter.process(
                    bufs[0][i + 1] * g0 + bufs[1][i + 1] * g1 + bufs[2][i + 1] * g2 + bufs[3][i + 1] * g3,
                )
            }
            prev = next
            f += n
            frameIndex += n
        }
        lastActiveSources = maxActive
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object { const val GAIN_BLOCK = 64 }
}
