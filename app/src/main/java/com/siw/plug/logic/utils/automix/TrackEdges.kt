/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

import kotlin.math.sqrt

data class TrackEdges(
    val durationMs: Long,
    val leadingSilenceMs: Long,
    val trailingSilenceMs: Long,
    val introPointMs: Long?,
    val outroSkipMs: Long?,
    val startGrid: BeatPhaseAligner.Grid? = null,
    val endGrid: BeatPhaseAligner.Grid? = null,
    val startWindowEndMs: Long = 0,
    val endWindowStartMs: Long = 0,
)

object TrackEdgeAnalyzer {
    private const val BLOCK_MS = 100
    private const val SILENCE_RMS = 0.003
    private const val MIN_SILENCE_BLOCKS = 3

    fun analyze(start: FloatArray, end: FloatArray, durationMs: Long): TrackEdges? {
        if (durationMs <= 0 || start.isEmpty() || end.isEmpty() ||
            start.any { !it.isFinite() } || end.any { !it.isFinite() }
        ) return null
        val startLevels = levels(start)
        val endLevels = levels(end)
        if (startLevels.isEmpty() || endLevels.isEmpty()) return null
        val leading = startLevels.takeWhile { it < SILENCE_RMS }.size
        val trailing = endLevels.reversed().takeWhile { it < SILENCE_RMS }.size
        if (leading == startLevels.size || trailing == endLevels.size) return null
        val leadingMs = if (leading >= MIN_SILENCE_BLOCKS) leading * BLOCK_MS.toLong() else 0L
        val trailingMs = if (trailing >= MIN_SILENCE_BLOCKS) trailing * BLOCK_MS.toLong() else 0L
        // A pronounced onset is a candidate, not proof of a downbeat.
        val onset = (maxOf(leading + 1, 1) until startLevels.size).maxByOrNull {
            startLevels[it] - startLevels[it - 1]
        }?.takeIf { startLevels[it] >= 0.02 && startLevels[it] - startLevels[it - 1] >= 0.02 }
        val outro = (1 until endLevels.size - trailing).maxByOrNull {
            endLevels[it - 1] - endLevels[it]
        }?.takeIf { endLevels[it - 1] >= 0.02 && endLevels[it - 1] - endLevels[it] >= 0.02 }
        val endWindowMs = endLevels.size * BLOCK_MS.toLong()
        return TrackEdges(
            durationMs, leadingMs, trailingMs,
            onset?.let { it * BLOCK_MS.toLong() },
            outro?.let { endWindowMs - it * BLOCK_MS.toLong() },
        )
    }

    fun startPosition(edges: TrackEdges?, limitMs: Long): Long {
        if (edges == null) return 0L
        val cap = minOf(limitMs.coerceAtLeast(0), edges.durationMs / 10)
        return maxOf(edges.leadingSilenceMs.coerceAtMost(cap),
            edges.introPointMs?.takeIf { it <= cap } ?: 0L)
    }

    fun endPosition(edges: TrackEdges?, durationMs: Long, limitMs: Long): Long {
        if (edges == null || durationMs <= 0 || kotlin.math.abs(edges.durationMs - durationMs) > 1000)
            return durationMs
        val cap = minOf(limitMs.coerceAtLeast(0), durationMs / 10)
        val skip = maxOf(edges.trailingSilenceMs.coerceAtMost(cap),
            edges.outroSkipMs?.takeIf { it <= cap } ?: 0L)
        return durationMs - skip
    }

    private fun levels(samples: FloatArray): List<Double> {
        val blocks = samples.size.toLong() * 10 / TrackDspAnalyzer.SAMPLE_RATE
        return (0 until blocks.toInt()).map { block ->
            val start = block * TrackDspAnalyzer.SAMPLE_RATE / 10
            val end = (block + 1) * TrackDspAnalyzer.SAMPLE_RATE / 10
            var energy = 0.0
            for (i in start until end) {
                energy += samples[i].toDouble() * samples[i]
            }
            sqrt(energy / (end - start))
        }
    }
}
