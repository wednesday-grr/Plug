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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object AutomixMath {
    const val DEFAULT_MAX_TEMPO_DEVIATION = 0.15f
    data class Volumes(val outgoing: Float, val incoming: Float)

    fun automaticDurationMs(bpm: Float?): Long {
        val validBpm = bpm?.takeIf { it.isFinite() && it in 30f..300f } ?: return 5000L
        return (32f * 60_000f / validBpm).toLong().coerceIn(1000L, 30_000L)
    }

    fun equalPower(progress: Float): Volumes {
        require(progress.isFinite()) { "Crossfade progress must be finite" }
        val angle = progress.coerceIn(0f, 1f) * PI / 2.0
        return Volumes(cos(angle).toFloat().coerceIn(0f, 1f), sin(angle).toFloat().coerceIn(0f, 1f))
    }

    fun overlapHeadroom(progress: Float): Float {
        require(progress.isFinite()) { "Crossfade progress must be finite" }
        val angle = progress.coerceIn(0f, 1f) * PI
        return (1f - 0.15f * sin(angle).toFloat()).coerceIn(0.85f, 1f)
    }

    fun tempoRatio(outgoingBpm: Float?, incomingBpm: Float?, maxDeviation: Float = DEFAULT_MAX_TEMPO_DEVIATION): Float? {
        require(maxDeviation.isFinite() && maxDeviation in 0f..<1f)
        if (outgoingBpm == null || incomingBpm == null ||
            !outgoingBpm.isFinite() || !incomingBpm.isFinite() ||
            outgoingBpm <= 0f || incomingBpm <= 0f
        ) return null
        val ratio = outgoingBpm / incomingBpm
        return ratio.takeIf { it.isFinite() && abs(it - 1f) <= maxDeviation }
    }

    fun rampSpeed(matchedSpeed: Float, progress: Float): Float {
        require(matchedSpeed.isFinite() && matchedSpeed > 0f && progress.isFinite())
        val rampProgress = ((progress.coerceIn(0f, 1f) - 0.5f) * 2f).coerceIn(0f, 1f)
        return matchedSpeed + (1f - matchedSpeed) * rampProgress
    }
}
