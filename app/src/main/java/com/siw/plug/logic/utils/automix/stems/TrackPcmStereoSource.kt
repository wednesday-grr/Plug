package com.siw.plug.logic.utils.automix.stems

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Growable FIFO of stereo float frames. */
class StereoFrameQueue(initial: Int = 8192) {
    private var left = FloatArray(initial)
    private var right = FloatArray(initial)
    private var head = 0
    private var tail = 0
    val size: Int get() = tail - head

    fun push(l: Float, r: Float) {
        if (tail == left.size) {
            if (head > 0) {
                System.arraycopy(left, head, left, 0, size)
                System.arraycopy(right, head, right, 0, size)
                tail -= head; head = 0
            } else {
                left = left.copyOf(left.size * 2); right = right.copyOf(right.size * 2)
            }
        }
        left[tail] = l; right[tail] = r; tail++
    }

    fun pop(outL: FloatArray, outR: FloatArray, max: Int): Int {
        val n = minOf(max, size)
        System.arraycopy(left, head, outL, 0, n)
        System.arraycopy(right, head, outR, 0, n)
        head += n
        return n
    }
}

/** Streaming linear-interpolation resampler for stereo audio. Adequate for separation input; not mastering grade. */
class StereoLinearResampler(private val inRate: Int, private val outRate: Int) {
    private var prevL = 0f
    private var prevR = 0f
    private var havePrev = false
    private var position = 0.0 // output sample position relative to the previous input frame, in input frames
    private val step = inRate.toDouble() / outRate

    fun process(l: Float, r: Float, out: StereoFrameQueue) {
        if (inRate == outRate) { out.push(l, r); return }
        if (!havePrev) { prevL = l; prevR = r; havePrev = true; return }
        while (position < 1.0) {
            val f = position.toFloat()
            out.push(prevL + (l - prevL) * f, prevR + (r - prevR) * f)
            position += step
        }
        position -= 1.0
        prevL = l; prevR = r
    }
}

/**
 * Decodes an entire local track through MediaExtractor/MediaCodec into 44.1 kHz stereo floats.
 * Mono is duplicated; channels beyond two are dropped. Runs on the caller's (background) thread.
 */
class MediaCodecStereoSource(
    context: Context,
    uri: Uri,
    private val isCancelled: () -> Boolean = { false },
) : StereoPcmSource, AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val queue = StereoFrameQueue()
    private var resampler: StereoLinearResampler? = null
    private var rate = 0
    private var channels = 0
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var inputEnded = false
    private var outputEnded = false
    private var idle = 0

    init {
        require(uri.scheme == "content" || uri.scheme == "file") { "Only local audio is supported" }
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in $uri")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
        codec.configure(format, null, null, 0)
        codec.start()
    }

    override fun read(left: FloatArray, right: FloatArray, maxFrames: Int): Int {
        while (queue.size < maxFrames && !outputEnded) {
            check(!isCancelled()) { "cancelled" }
            pump()
        }
        if (queue.size == 0) return -1
        return queue.pop(left, right, maxFrames)
    }

    private fun pump() {
        var progressed = false
        if (!inputEnded) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                progressed = true
                val buffer = checkNotNull(codec.getInputBuffer(index))
                val n = extractor.readSampleData(buffer, 0)
                val time = extractor.sampleTime
                if (n < 0 || time < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEnded = true
                } else {
                    codec.queueInputBuffer(index, 0, n, time, 0)
                    extractor.advance()
                }
            }
        }
        val out = codec.dequeueOutputBuffer(info, 10_000)
        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val format = codec.outputFormat
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                "Unsupported PCM encoding $encoding"
            }
            require(rate in 8_000..192_000 && channels > 0) { "Unsupported PCM format" }
            resampler = StereoLinearResampler(rate, SpleeterDsp.SAMPLE_RATE)
            progressed = true
        } else if (out >= 0) {
            progressed = true
            val buffer = checkNotNull(codec.getOutputBuffer(out)).order(ByteOrder.nativeOrder())
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val activeResampler = resampler ?: StereoLinearResampler(rate, SpleeterDsp.SAMPLE_RATE).also { resampler = it }
            val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            while (buffer.remaining() >= bytes * channels) {
                var l = 0f
                var r = 0f
                for (c in 0 until channels) {
                    val s = if (bytes == 4) buffer.float else buffer.short / 32768f
                    if (c == 0) { l = s; r = s } else if (c == 1) r = s
                }
                activeResampler.process(l, r, queue)
            }
            codec.releaseOutputBuffer(out, false)
            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        }
        idle = if (progressed) 0 else idle + 1
        check(idle < 1000) { "Audio decoder stalled" }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }
}
