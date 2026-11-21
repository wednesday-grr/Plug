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
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

/**
 * Decodes local audio through Android's extractor/codec registry. Supported containers and
 * codecs depend on the Android build; callers must handle decoder failures for unsupported media.
 */
class TrackPcmDecoder(private val context: Context) {
    data class Window(val samples: FloatArray, val startMs: Long, val durationMs: Long)
    enum class Region { MIDDLE, START, END }

    suspend fun decode(
        uri: Uri,
        region: Region = Region.MIDDLE,
        sampleRate: Int = TrackDspAnalyzer.SAMPLE_RATE,
    ): Window {
        require(uri.scheme == "content" || uri.scheme == "file") { "Only local audio is supported" }
        require(sampleRate == TrackDspAnalyzer.SAMPLE_RATE || sampleRate == 44_100)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in $uri")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val encoderDelay = if (inputFormat.containsKey(MediaFormat.KEY_ENCODER_DELAY))
                inputFormat.getInteger(MediaFormat.KEY_ENCODER_DELAY) else 0
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            require(sampleRate != 44_100 || durationUs > 0) {
                "Track duration is required for Essentia middle-window analysis"
            }
            val windowUs = if (region == Region.MIDDLE) WINDOW_US else EDGE_WINDOW_US
            val startUs = when (region) {
                Region.MIDDLE -> ((durationUs - windowUs) / 2).coerceAtLeast(0L)
                Region.START -> 0L
                Region.END -> {
                    require(durationUs > 0) { "Track duration is required for end analysis" }
                    (durationUs - windowUs).coerceAtLeast(0L)
                }
            }
            val endUs = startUs + windowUs
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(inputFormat.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val result = FloatArray(TrackDspAnalyzer.SAMPLE_RATE * (windowUs / 1_000_000L).toInt())
            var size = 0
            var firstUs: Long? = null
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var sum = 0.0
            var count = 0
            var accumulator = 0
            var sourceSamples: FloatArray? = null
            var sourceSize = 0
            var sourceRate = 0
            var sourceFirstBuffer = -1
            var bufferSequence = 0
            var discardedStartupBuffer = false
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var idle = 0
            while (!outputEnded && (sampleRate != TrackDspAnalyzer.SAMPLE_RATE || size < result.size)) {
                currentCoroutineContext().ensureActive()
                var progressed = false
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        progressed = true
                        val buffer = checkNotNull(decoder.getInputBuffer(index))
                        val n = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (n < 0 || time < 0 || time >= endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, n, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(rate in 8_000..192_000 && channels > 0 &&
                        (sampleRate != TrackDspAnalyzer.SAMPLE_RATE || rate >= sampleRate)
                    ) { "Unsupported PCM format" }
                    require(sourceRate == 0 || sourceRate == rate) { "PCM sample rate changed during analysis" }
                    require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        "Unsupported PCM encoding $encoding"
                    }
                    progressed = true
                } else if (index >= 0) {
                    bufferSequence++
                    progressed = true
                    val buffer = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    var frame = 0L
                    if (sampleRate == 44_100 && sourceSamples == null) {
                        require(rate in 8_000..192_000 && channels > 0) { "Unsupported PCM format" }
                        sourceRate = rate
                        sourceSamples = FloatArray(rate * (windowUs / 1_000_000L).toInt())
                    }
                    while (buffer.remaining() >= bytes * channels &&
                        (sampleRate == 44_100 || size < result.size)) {
                        var sample = 0.0
                        var strongest = 0.0
                        repeat(channels) {
                            val channel = if (bytes == 4) buffer.float.toDouble() else buffer.short / 32768.0
                            sample += channel
                            if (kotlin.math.abs(channel) > kotlin.math.abs(strongest)) strongest = channel
                        }
                        val timeUs = info.presentationTimeUs + frame * 1_000_000L / rate
                        frame++
                        if (timeUs < startUs || timeUs >= endUs) continue
                        if (firstUs == null) firstUs = timeUs
                        val mono = if (region == Region.MIDDLE) sample / channels else strongest
                        if (sampleRate == 44_100) {
                            val source = checkNotNull(sourceSamples)
                            if (sourceSize < source.size) {
                                if (sourceSize == 0) sourceFirstBuffer = bufferSequence
                                else {
                                    val expectedUs = checkNotNull(firstUs) + sourceSize.toLong() * 1_000_000L / rate
                                    if (kotlin.math.abs(timeUs - expectedUs) > PcmTimestampContinuity.TOLERANCE_US) {
                                        val discardStartup = PcmTimestampContinuity.canDiscardInitialBuffer(
                                            expectedUs, timeUs, rate, encoderDelay, sourceSize, startUs,
                                            !discardedStartupBuffer && bufferSequence == sourceFirstBuffer + 1,
                                        )
                                        require(discardStartup) {
                                            "Discontinuous PCM timestamps: expected=$expectedUs actual=$timeUs rate=$rate"
                                        }
                                        Log.w("AudioAnalysis", "Discarding seek-trimmed initial PCM buffer")
                                        sourceSize = 0
                                        sourceFirstBuffer = bufferSequence
                                        firstUs = timeUs
                                        discardedStartupBuffer = true
                                    }
                                }
                                source[sourceSize++] = mono.toFloat()
                            }
                            continue
                        }
                        sum += mono
                        count++
                        accumulator += TrackDspAnalyzer.SAMPLE_RATE
                        if (accumulator >= rate) {
                            result[size++] = (sum / count).toFloat()
                            accumulator -= rate
                            sum = 0.0
                            count = 0
                        }
                    }
                    decoder.releaseOutputBuffer(index, false)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
                idle = if (progressed) 0 else idle + 1
                check(idle < 1000) { "Audio decoder stalled for $uri" }
            }
            val samples = if (sampleRate == 44_100 && sourceSize > 0) {
                AnalysisPcmResampler.convert(checkNotNull(sourceSamples), sourceSize, sourceRate, sampleRate)
            } else result.copyOf(size)
            check(samples.isNotEmpty() && firstUs != null) { "No decodable PCM in $uri" }
            return Window(samples, firstUs / 1000, durationUs / 1000)
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    /** Full-band interleaved PCM (1 or 2 channels) at the source sample rate, for loudness measurement. */
    class FullBandWindow(val samples: FloatArray, val sampleRate: Int, val channels: Int)

    /**
     * Decodes the middle [LOUDNESS_WINDOW_US] without downmixing or resampling. Channels beyond the first two
     * are dropped, so multichannel loudness is approximate.
     */
    suspend fun decodeFullBand(uri: Uri): FullBandWindow {
        require(uri.scheme == "content" || uri.scheme == "file") { "Only local audio is supported" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in $uri")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = ((durationUs - LOUDNESS_WINDOW_US) / 2).coerceAtLeast(0L)
            val endUs = startUs + LOUDNESS_WINDOW_US
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(inputFormat.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var out = FloatArray(0)
            var size = 0
            var inputEnded = false
            var outputEnded = false
            var idle = 0
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                var progressed = false
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        progressed = true
                        val buffer = checkNotNull(decoder.getInputBuffer(index))
                        val n = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (n < 0 || time < 0 || time >= endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, n, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(rate in 8_000..192_000 && channels > 0) { "Unsupported PCM format" }
                    require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        "Unsupported PCM encoding $encoding"
                    }
                    out = FloatArray(rate * 2 * (LOUDNESS_WINDOW_US / 1_000_000L).toInt())
                    progressed = true
                } else if (index >= 0) {
                    progressed = true
                    check(out.isNotEmpty()) { "Decoder produced output before its format" }
                    val buffer = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val kept = minOf(channels, 2)
                    var frame = 0L
                    while (buffer.remaining() >= bytes * channels && size + kept <= out.size) {
                        val timeUs = info.presentationTimeUs + frame * 1_000_000L / rate
                        frame++
                        for (ch in 0 until channels) {
                            val value = if (bytes == 4) buffer.float else buffer.short / 32768f
                            if (ch < kept && timeUs in startUs until endUs) out[size++] = value
                        }
                    }
                    decoder.releaseOutputBuffer(index, false)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
                idle = if (progressed) 0 else idle + 1
                check(idle < 1000) { "Audio decoder stalled for $uri" }
            }
            val kept = minOf(channels, 2)
            check(size >= kept) { "No decodable PCM in $uri" }
            return FullBandWindow(out.copyOf(size - size % kept), rate, kept)
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    companion object {
        private const val LOUDNESS_WINDOW_US = 60_000_000L
        private const val WINDOW_US = 60_000_000L
        private const val EDGE_WINDOW_US = 30_000_000L
    }
}
