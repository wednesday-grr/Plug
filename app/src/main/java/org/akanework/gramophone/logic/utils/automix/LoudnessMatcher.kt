/*
 *     Copyright (C) 2026 Gramophone contributors
 *
 *     This file is part of Gramophone.
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import kotlin.math.abs
import kotlin.math.pow

enum class LoudnessSource { MEASURED, REPLAYGAIN_METADATA }

/** One track's loudness knowledge. Unknown values are null, never zero. */
data class TrackLoudness(
    val measured: MeasuredLoudness? = null,
    /** ReplayGain tag in dB (reference -18 LUFS); describes the file, not what playback applies. */
    val replayGainTagDb: Float? = null,
    /** Gain in dB that the ReplayGain stage is actually applying to this track right now. */
    val replayGainAppliedDb: Float = 0f,
)

data class LoudnessMatchConfig(
    val maxCorrectionDb: Float = 6f,
    /** Differences smaller than this are left alone to avoid pointless gain changes. */
    val deadbandDb: Float = 1f,
    /** Measured data from fewer seconds than this is not trusted. */
    val minMeasuredSeconds: Float = 20f,
    /** Allow metadata-only comparisons (less reliable than measurement). */
    val allowMetadataFallback: Boolean = true,
) {
    init {
        require(maxCorrectionDb in 0f..12f && deadbandDb in 0f..6f && minMeasuredSeconds >= 0f)
    }
}

enum class LoudnessMatchReason {
    APPLIED, WITHIN_DEADBAND, OUTGOING_UNAVAILABLE, INCOMING_UNAVAILABLE, SOURCES_NOT_COMPARABLE,
    CLAMPED_TO_MAX_CORRECTION,
}

data class LoudnessMatchPlan(
    /** Linear factors in (0, 1]; matching only ever attenuates, so it cannot clip. */
    val outgoingGain: Float,
    val incomingGain: Float,
    val outgoingEffectiveLufs: Float?,
    val incomingEffectiveLufs: Float?,
    val differenceLu: Float?,
    val appliedCorrectionDb: Float,
    val source: LoudnessSource?,
    val reason: LoudnessMatchReason,
) {
    val applied: Boolean get() = appliedCorrectionDb != 0f

    companion object {
        fun none(reason: LoudnessMatchReason) =
            LoudnessMatchPlan(1f, 1f, null, null, null, 0f, null, reason)
    }
}

/**
 * Plans transition-time loudness matching. It attenuates only the louder side toward the quieter one,
 * working on loudness as playback will actually produce it (source loudness plus the ReplayGain gain
 * already applied), so ReplayGain is never applied twice and positive gain is never required.
 */
object LoudnessMatcher {
    private const val REPLAYGAIN_REFERENCE_LUFS = -18f

    private fun sourceLufs(track: TrackLoudness, config: LoudnessMatchConfig): Pair<Float, LoudnessSource>? {
        val measured = track.measured
        val integrated = measured?.integratedLufs
        if (integrated != null && measured.measuredSeconds >= config.minMeasuredSeconds) {
            return integrated to LoudnessSource.MEASURED
        }
        val tag = track.replayGainTagDb
        if (config.allowMetadataFallback && tag != null && tag.isFinite()) {
            return (REPLAYGAIN_REFERENCE_LUFS - tag) to LoudnessSource.REPLAYGAIN_METADATA
        }
        return null
    }

    fun plan(
        outgoing: TrackLoudness, incoming: TrackLoudness, config: LoudnessMatchConfig = LoudnessMatchConfig(),
    ): LoudnessMatchPlan {
        val out = sourceLufs(outgoing, config) ?: return LoudnessMatchPlan.none(LoudnessMatchReason.OUTGOING_UNAVAILABLE)
        val inc = sourceLufs(incoming, config) ?: return LoudnessMatchPlan.none(LoudnessMatchReason.INCOMING_UNAVAILABLE)
        // Metadata and measurement are on different scales; refuse to compare them directly.
        if (out.second != inc.second) {
            return LoudnessMatchPlan.none(LoudnessMatchReason.SOURCES_NOT_COMPARABLE)
        }
        val outEffective = out.first + outgoing.replayGainAppliedDb
        val incEffective = inc.first + incoming.replayGainAppliedDb
        val diff = incEffective - outEffective
        if (abs(diff) < config.deadbandDb) {
            return LoudnessMatchPlan(1f, 1f, outEffective, incEffective, diff, 0f, out.second,
                LoudnessMatchReason.WITHIN_DEADBAND)
        }
        val clamped = abs(diff) > config.maxCorrectionDb
        val correctionDb = minOf(abs(diff), config.maxCorrectionDb)
        val factor = 10f.pow(-correctionDb / 20f)
        val reason = if (clamped) LoudnessMatchReason.CLAMPED_TO_MAX_CORRECTION else LoudnessMatchReason.APPLIED
        return if (diff > 0f) {
            LoudnessMatchPlan(1f, factor, outEffective, incEffective, diff, correctionDb, out.second, reason)
        } else {
            LoudnessMatchPlan(factor, 1f, outEffective, incEffective, diff, correctionDb, out.second, reason)
        }
    }

    /** Outgoing track reaches its planned attenuation by mid-transition. */
    fun outgoingTrim(plannedGain: Float, progress: Float): Float = rampedGain(plannedGain, progress * 2f)

    /**
     * Incoming track enters at its planned attenuation (inaudible, as its fade gain is near zero) and is
     * restored to unity during the second half, so it plays at its native level after handoff.
     */
    fun incomingTrim(plannedGain: Float, progress: Float): Float {
        val restore = 1f - rampedGain(0f, (progress - 0.5f) * 2f)
        return plannedGain + (1f - plannedGain) * restore
    }

    /** Smoothstep ramp from unity to the planned gain, so no step is introduced at transition start. */
    fun rampedGain(targetGain: Float, progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val s = p * p * (3f - 2f * p)
        return 1f + (targetGain - 1f) * s
    }
}
