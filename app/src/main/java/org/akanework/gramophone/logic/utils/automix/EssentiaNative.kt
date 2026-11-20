/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import androidx.annotation.Keep
import org.akanework.gramophone.BuildConfig

/**
 * Experimental native bridge. Input must already be mono PCM at 44,100 Hz.
 * The raw Essentia confidence is not comparable to the existing analyzer's confidence.
 */
@Keep
internal object EssentiaNative {
    data class RhythmResult(
        val bpm: Double,
        val rawConfidence: Double,
        val camelotKey: String,
        val keyStrength: Double,
        val beatPositionsMs: List<Double>,
    )

    fun analyze(samples: FloatArray, startMs: Long): RhythmResult {
        require(startMs >= 0)
        check(BuildConfig.HAS_ESSENTIA) { "This build does not include Essentia" }
        val result = try {
            System.loadLibrary("gramophone_essentia")
            analyzeNative(samples)
        } catch (e: LinkageError) {
            throw IllegalStateException("Essentia native library is unavailable", e)
        }
        return parse(result, startMs, samples.size)
    }

    internal fun parse(result: DoubleArray, startMs: Long, sampleCount: Int): RhythmResult {
        require(startMs >= 0 && sampleCount > 0 && result.size >= 5) { "Invalid Essentia result" }
        require(result[0].isFinite() && result[0] > 0 && result[1].isFinite()) {
            "Essentia did not return a valid BPM or confidence"
        }
        val keyIndex = result[2].toInt()
        require(result[2] == keyIndex.toDouble() && keyIndex in keyNames.indices) {
            "Essentia returned an invalid key"
        }
        val minor = when (result[3]) {
            0.0 -> false
            1.0 -> true
            else -> throw IllegalArgumentException("Essentia returned an invalid key scale")
        }
        require(result[4].isFinite()) { "Essentia returned an invalid key strength" }
        val camelotKey = checkNotNull(
            MusicalKey.parse("${keyNames[keyIndex]} ${if (minor) "minor" else "major"}")
        ).camelot
        val durationSeconds = sampleCount / 44_100.0
        val ticks = result.drop(5)
        require(ticks.all { it.isFinite() && it >= 0 && it <= durationSeconds } &&
            ticks.zipWithNext().all { (a, b) -> a < b }) { "Invalid Essentia beat timestamps" }
        return RhythmResult(
            result[0], result[1], camelotKey, result[4],
            ticks.map { startMs + it * 1000.0 },
        )
    }

    private val keyNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    @Keep
    private external fun analyzeNative(samples: FloatArray): DoubleArray
}
