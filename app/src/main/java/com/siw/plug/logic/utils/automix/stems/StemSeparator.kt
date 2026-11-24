package com.siw.plug.logic.utils.automix.stems

import java.util.concurrent.CancellationException

/** Sequential 44.1 kHz stereo float source. Implementations must never block the audio thread. */
interface StereoPcmSource {
    /** Reads up to [maxFrames] frames into [left]/[right]; returns frames read or -1 at end of stream. */
    fun read(left: FloatArray, right: FloatArray, maxFrames: Int): Int
}

/** Receives separated audio in order. All arrays are 44.1 kHz; [count] valid frames from index 0. */
interface StemSink {
    fun write(vocalsL: FloatArray, vocalsR: FloatArray, accL: FloatArray, accR: FloatArray, count: Int)
}

/** Maps a normalised magnitude spectrogram [2,1,512,1024] to the raw vocals and accompaniment network outputs. */
interface SpectrogramModel : AutoCloseable {
    fun infer(magnitudes: FloatArray): Pair<FloatArray, FloatArray>
}

/**
 * Streaming 2-stem separation: bounded memory (one ~12 s segment) regardless of track length,
 * cooperative cancellation between segments, no work on the playback thread.
 */
class StemSeparator(private val model: SpectrogramModel) {
    /** Returns the number of 44.1 kHz frames produced per stem. */
    fun separate(
        source: StereoPcmSource,
        sink: StemSink,
        isCancelled: () -> Boolean = { false },
        onProgress: (framesDone: Long) -> Unit = {},
    ): Long {
        val segmentLength = SpleeterDsp.SEGMENT_SAMPLES
        val margin = SpleeterDsp.MARGIN
        val valid = SpleeterDsp.SEGMENT_VALID
        val left = FloatArray(segmentLength)
        val right = FloatArray(segmentLength)
        var filled = margin // leading zeros stand in for audio before the track start
        var totalInput = 0L
        var emitted = 0L
        var eof = false

        while (true) {
            if (isCancelled()) throw CancellationException("Stem separation cancelled")
            while (!eof && filled < segmentLength) {
                val tmpL = FloatArray(segmentLength - filled)
                val tmpR = FloatArray(segmentLength - filled)
                val n = source.read(tmpL, tmpR, tmpL.size)
                if (n < 0) { eof = true; break }
                System.arraycopy(tmpL, 0, left, filled, n)
                System.arraycopy(tmpR, 0, right, filled, n)
                filled += n
                totalInput += n
            }
            if (filled < segmentLength) {
                java.util.Arrays.fill(left, filled, segmentLength, 0f)
                java.util.Arrays.fill(right, filled, segmentLength, 0f)
            }
            val count = minOf(valid.toLong(), totalInput - emitted).toInt()
            if (count <= 0) break

            val specL = SpleeterDsp.stft(left)
            val specR = SpleeterDsp.stft(right)
            val (vocalMask, accMask) = model.infer(SpleeterDsp.modelInput(specL, specR))
            SpleeterDsp.normaliseMasks(vocalMask, accMask)
            if (isCancelled()) throw CancellationException("Stem separation cancelled")

            fun slice(channel: FloatArray) = channel.copyOfRange(margin, margin + count)
            sink.write(
                slice(SpleeterDsp.istft(specL, vocalMask, 0)),
                slice(SpleeterDsp.istft(specR, vocalMask, 1)),
                slice(SpleeterDsp.istft(specL, accMask, 0)),
                slice(SpleeterDsp.istft(specR, accMask, 1)),
                count,
            )
            emitted += count
            onProgress(emitted)
            if (eof && emitted >= totalInput) break

            // Slide by one valid hop, keeping the 2*margin overlap as left context for the next segment.
            val keep = segmentLength - valid
            System.arraycopy(left, valid, left, 0, keep)
            System.arraycopy(right, valid, right, 0, keep)
            filled = keep
        }
        return emitted
    }
}
