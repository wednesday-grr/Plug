/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.utils.automix.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

/**
 * Opt-in bridge for the offline evaluation script. Input files are PCM WAVs, not Android decoder
 * output; this does not establish a mapping between decoder and player timelines.
 */
class BeatEvaluationRunnerTest {
    @Test
    fun resamplingPreservesKnownSourceTimestamp() {
        val sourceRate = 44_100
        val impulseFrame = sourceRate
        val frameCount = sourceRate * 2
        val pcm = ByteArray(frameCount * 4)
        repeat(2) { channel ->
            val offset = (impulseFrame * 2 + channel) * 2
            pcm[offset] = 0xff.toByte()
            pcm[offset + 1] = 0x7f
        }
        val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sourceRate.toFloat(),
            16, 2, 4, sourceRate.toFloat(), false)
        val file = File.createTempFile("beat-timeline-", ".wav")
        try {
            AudioInputStream(ByteArrayInputStream(pcm), format, frameCount.toLong()).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
            }
            val samples = readPcm(file)
            val peakFrame = samples.indices.maxByOrNull { abs(samples[it]) } ?: error("No samples")
            val expectedFrame = TrackDspAnalyzer.SAMPLE_RATE
            assertTrue("Resampling shifted the known one-second impulse by ${peakFrame - expectedFrame} frames",
                abs(peakFrame - expectedFrame) <= 1)
        } finally {
            file.delete()
        }
    }

    @Test
    fun evaluateFixtures() = runBlocking {
        val manifest = System.getenv("BEAT_EVAL_INPUT")
        val output = System.getenv("BEAT_EVAL_OUTPUT")
        assumeTrue("Set BEAT_EVAL_INPUT and BEAT_EVAL_OUTPUT to run evaluation", manifest != null && output != null)
        val rows = File(manifest!!).readLines().filter { it.isNotBlank() }
        val timeline = SourceTimeline(null, TimelineOrigin.UNKNOWN, TimelineMapping.UNKNOWN,
            TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN, TimingFact.UNKNOWN)
        File(output!!).bufferedWriter().use { writer ->
            rows.forEachIndexed { index, path ->
                val samples = readPcm(File(path))
                val started = System.nanoTime()
                val result = TrackDspAnalyzer.analyzeWithBeatTracking(samples, 0, timeline)
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                val tracking = result.tracking
                writer.write(listOf(index, samples.size, elapsed, result.analysis.bpm ?: "",
                    tracking.selected?.name ?: "", tracking.ambiguous,
                    tracking.hypotheses.firstOrNull { it.interpretation == tracking.selected }?.bpm ?: "",
                    tracking.grid?.events?.joinToString(",") { it.sourceTimeUs.toString() } ?: "unavailable",
                    MusicalBeatTracker.ALGORITHM_VERSION,
                    tracking.hypotheses.joinToString(";") {
                        "${it.interpretation.name},${it.bpm},${it.support}"
                    },
                    TrackDspAnalyzer.BEAT_ANALYSIS_CONFIG_VERSION,
                ).joinToString("\t"))
                writer.newLine()
            }
        }
    }

    private fun readPcm(file: File): FloatArray = AudioSystem.getAudioInputStream(file).use { input ->
        val sourceFormat = input.format
        require(sourceFormat.encoding == AudioFormat.Encoding.PCM_SIGNED &&
            sourceFormat.sampleSizeInBits == 16 && sourceFormat.channels in 1..2 &&
            !sourceFormat.isBigEndian && sourceFormat.frameSize == sourceFormat.channels * 2 &&
            sourceFormat.sampleRate.toInt() in setOf(11_025, 44_100) && input.frameLength >= 0) {
            "Expected known-length 11025 or 44100 Hz mono/stereo signed 16-bit little-endian PCM WAV: " +
                "$file ($sourceFormat)"
        }
        val bytes = input.readBytes()
        require(bytes.size.toLong() == input.frameLength * sourceFormat.frameSize) {
            "Truncated PCM data: $file"
        }
        val sourceRate = sourceFormat.sampleRate.toInt()
        val sourceFrames = bytes.size / sourceFormat.frameSize
        val downsampleFactor = sourceRate / TrackDspAnalyzer.SAMPLE_RATE
        val outputFrames = (sourceFrames / downsampleFactor)
            .coerceAtMost(TrackDspAnalyzer.SAMPLE_RATE * 60)
        val channels = sourceFormat.channels
        FloatArray(outputFrames) { outputFrame ->
            if (downsampleFactor == 1) {
                monoSample(bytes, outputFrame, channels).toFloat()
            } else {
                val centerFrame = outputFrame * downsampleFactor
                RESAMPLE_COEFFICIENTS.indices.sumOf { tap ->
                    val sourceFrame = centerFrame + tap - RESAMPLE_CENTER
                    if (sourceFrame in 0 until sourceFrames) {
                        monoSample(bytes, sourceFrame, channels) * RESAMPLE_COEFFICIENTS[tap]
                    } else 0.0
                }.toFloat()
            }
        }
    }

    private fun monoSample(bytes: ByteArray, frame: Int, channels: Int): Double {
        val offset = frame * channels * 2
        fun channelSample(channel: Int): Double {
            val index = offset + channel * 2
            val value = (bytes[index].toInt() and 0xff) or
                ((bytes[index + 1].toInt() and 0xff) shl 8)
            return value.toShort() / 32768.0
        }
        return if (channels == 1) channelSample(0)
        else (channelSample(0) + channelSample(1)) * 0.5
    }

    private companion object {
        const val RESAMPLE_TAPS = 33
        const val RESAMPLE_CENTER = RESAMPLE_TAPS / 2
        val RESAMPLE_COEFFICIENTS = DoubleArray(RESAMPLE_TAPS) { index ->
            val distance = index - RESAMPLE_CENTER
            val normalized = distance.toDouble() / 4
            val ideal = if (distance == 0) 0.25
            else 0.25 * kotlin.math.sin(Math.PI * normalized) /
                (Math.PI * normalized)
            val window = 0.5 - 0.5 * kotlin.math.cos(
                2 * Math.PI * index / (RESAMPLE_TAPS - 1),
            )
            ideal * window
        }.also { coefficients ->
            val sum = coefficients.sum()
            coefficients.indices.forEach { coefficients[it] /= sum }
        }
    }
}
