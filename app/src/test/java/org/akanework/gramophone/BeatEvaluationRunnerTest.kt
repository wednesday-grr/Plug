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
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Opt-in bridge for the offline evaluation script. Input files are PCM WAVs, not Android decoder
 * output; this does not establish a mapping between decoder and player timelines.
 */
class BeatEvaluationRunnerTest {
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
                    MusicalBeatTracker.ALGORITHM_VERSION
                ).joinToString("\t"))
                writer.newLine()
            }
        }
    }

    private fun readPcm(file: File): FloatArray = AudioSystem.getAudioInputStream(file).use { input ->
        val format = input.format
        require(format.encoding == AudioFormat.Encoding.PCM_SIGNED &&
            format.sampleRate == TrackDspAnalyzer.SAMPLE_RATE.toFloat() &&
            format.sampleSizeInBits == 16 && format.channels == 1 && !format.isBigEndian &&
            format.frameSize == 2) {
            "Expected 11025 Hz mono signed 16-bit little-endian PCM WAV: $file ($format)"
        }
        require(input.frameLength in 0..(TrackDspAnalyzer.SAMPLE_RATE * 60L)) {
            "Fixture must have a known length of at most 60 seconds: $file"
        }
        val bytes = input.readBytes()
        require(bytes.size.toLong() == input.frameLength * 2) { "Truncated PCM data: $file" }
        FloatArray(bytes.size / 2) { i ->
            val value = (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
            value.toShort() / 32768f
        }
    }
}
