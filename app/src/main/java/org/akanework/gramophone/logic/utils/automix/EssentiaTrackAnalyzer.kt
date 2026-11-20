/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

internal object EssentiaTrackAnalyzer {
    suspend fun analyze(window: TrackPcmDecoder.Window): TrackAnalysis {
        require(window.samples.size >= 44_100 * 5) { "Essentia needs at least five seconds of audio" }
        var energy = 0.0
        for ((i, sample) in window.samples.withIndex()) {
            if (i % 8192 == 0) currentCoroutineContext().ensureActive()
            require(sample.isFinite()) { "PCM contains non-finite samples" }
            energy += sample.toDouble() * sample
        }
        require(sqrt(energy / window.samples.size) > 0.0001) { "Audio is silent or too quiet for rhythm analysis" }
        val rhythm = EssentiaNative.analyze(window.samples, window.startMs)
        currentCoroutineContext().ensureActive()
        require(rhythm.bpm in 30.0..300.0 && rhythm.beatPositionsMs.size >= 2) {
            "Essentia could not determine a usable rhythm"
        }
        return TrackAnalysis(
            rhythm.bpm.toFloat(), rhythm.camelotKey, null, 0f, window.startMs,
            window.startMs + window.samples.size.toLong() * 1000 / 44_100,
        )
    }
}
