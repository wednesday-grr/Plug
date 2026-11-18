/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/** Returns original indices, so equal or duplicate media items remain distinct. */
object SmartQueueSorter {
    data class Metadata(val bpm: Float?, val camelotKey: String?)

    fun bpmScore(from: Float?, to: Float?): Double? {
        if (from == null || to == null || !from.isFinite() || !to.isFinite() ||
            from <= 0f || to <= 0f) return null
        val ratio = to.toDouble() / from
        val distance = listOf(ratio, ratio / 2.0, ratio * 2.0)
            .minOf { abs(ln(it)) }
        return (1.0 - distance / ln(1.06) * 0.25).coerceIn(0.0, 1.0)
    }

    fun keyScore(from: String?, to: String?): Double? {
        val a = from?.let(MusicalKey::parse) ?: return null
        val b = to?.let(MusicalKey::parse) ?: return null
        val d = abs(a.camelotNumber - b.camelotNumber)
        val distance = min(d, 12 - d)
        if (distance == 0) return if (a.minor == b.minor) 1.0 else 0.75
        return (if (a.minor == b.minor) 0.9 else 0.65) - 0.12 * (distance - 1)
    }

    fun order(metadata: List<Metadata>, currentIndex: Int): List<Int> {
        require(currentIndex in metadata.indices)
        val remaining = (currentIndex + 1 until metadata.size).toMutableList()
        val result = (0..currentIndex).toMutableList()
        var bpm = metadata[currentIndex].bpm
        var key = metadata[currentIndex].camelotKey
        while (remaining.isNotEmpty()) {
            val chosen = remaining.maxBy { index ->
                val candidate = metadata[index]
                val tempo = bpmScore(bpm, candidate.bpm)
                val harmony = keyScore(key, candidate.camelotKey)
                val count = listOfNotNull(tempo, harmony).size
                // Unknown tracks do not displace transitions with comparable information.
                if (count == 0) -1.0 else (tempo ?: 0.0) + (harmony ?: 0.0) +
                    count * 0.001
            }
            remaining.remove(chosen)
            result.add(chosen)
            metadata[chosen].bpm?.let { if (it.isFinite() && it > 0) bpm = it }
            metadata[chosen].camelotKey?.let { if (MusicalKey.parse(it) != null) key = it }
        }
        return result
    }
}
