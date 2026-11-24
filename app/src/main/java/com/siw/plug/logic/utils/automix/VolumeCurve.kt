/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

import kotlin.math.abs
import kotlin.math.exp

/**
 * Normalised fade-in shape: gain(0) == 0, gain(1) == 1, monotonic on [0, 1].
 * Fade-out is the time mirror of the same shape, see [VolumeEnvelope].
 * These are volume curves only and are unrelated to the EQ EffectCurve.
 */
sealed interface VolumeCurve {
    fun fadeIn(t: Float): Float

    data object Linear : VolumeCurve {
        override fun fadeIn(t: Float) = clamp01(t)
    }

    /** Equal-power (constant power for uncorrelated material). */
    data object EqualPower : VolumeCurve {
        override fun fadeIn(t: Float) =
            kotlin.math.sin(clamp01(t) * Math.PI / 2.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * (e^(k t) - 1) / (e^k - 1). k > 0 starts slowly (late fade-in),
     * k < 0 starts quickly, |k| ~ 0 degenerates to linear without dividing by zero.
     */
    data class Exponential(val strength: Float = 4f) : VolumeCurve {
        init {
            require(strength.isFinite() && abs(strength) <= MAX_STRENGTH) {
                "Exponential strength must be finite and within +-$MAX_STRENGTH"
            }
        }

        override fun fadeIn(t: Float): Float {
            val x = clamp01(t)
            if (abs(strength) < 1e-4f) return x
            val k = strength.toDouble()
            return ((exp(k * x) - 1.0) / (exp(k) - 1.0)).toFloat().coerceIn(0f, 1f)
        }

        companion object {
            const val MAX_STRENGTH = 12f
        }
    }

    /**
     * Logistic S-curve renormalised so that gain(0) == 0 and gain(1) == 1.
     * steepness = 0 is linear; larger values give a flatter start/end with a steeper middle.
     */
    data class SCurve(val steepness: Float = 8f) : VolumeCurve {
        init {
            require(steepness.isFinite() && steepness in 0f..MAX_STEEPNESS) {
                "S-curve steepness must be within 0..$MAX_STEEPNESS"
            }
        }

        override fun fadeIn(t: Float): Float {
            val x = clamp01(t)
            if (steepness < 1e-4f) return x
            val s = steepness.toDouble()
            fun logistic(v: Double) = 1.0 / (1.0 + exp(-s * (v - 0.5)))
            val lo = logistic(0.0)
            val hi = logistic(1.0)
            return ((logistic(x.toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        }

        companion object {
            const val MAX_STEEPNESS = 20f
        }
    }

    companion object {
        internal fun clamp01(t: Float): Float {
            require(!t.isNaN()) { "Progress must not be NaN" }
            return t.coerceIn(0f, 1f)
        }
    }
}

/**
 * One source's gain envelope over overall transition progress: the fade occupies
 * [start, end] of the transition, holding 0/1 outside it.
 */
data class VolumeEnvelope(
    val curve: VolumeCurve,
    val fadesIn: Boolean,
    val start: Float = 0f,
    val end: Float = 1f,
) {
    init {
        require(start in 0f..1f && end in 0f..1f && end > start) {
            "Envelope window must satisfy 0 <= start < end <= 1"
        }
    }

    fun gain(progress: Float): Float {
        val local = ((VolumeCurve.clamp01(progress) - start) / (end - start)).coerceIn(0f, 1f)
        return if (fadesIn) curve.fadeIn(local) else curve.fadeIn(1f - local)
    }
}

/** Independent outgoing/incoming envelopes, so each source may use its own shape and window. */
data class TransitionVolumeCurves(
    val outgoing: VolumeEnvelope,
    val incoming: VolumeEnvelope,
) {
    init {
        require(!outgoing.fadesIn && incoming.fadesIn)
    }

    fun gains(progress: Float) = AutomixMath.Volumes(outgoing.gain(progress), incoming.gain(progress))

    companion object {
        fun of(
            outgoing: VolumeCurve,
            incoming: VolumeCurve = outgoing,
            outgoingWindow: ClosedFloatingPointRange<Float> = 0f..1f,
            incomingWindow: ClosedFloatingPointRange<Float> = 0f..1f,
        ) = TransitionVolumeCurves(
            VolumeEnvelope(outgoing, false, outgoingWindow.start, outgoingWindow.endInclusive),
            VolumeEnvelope(incoming, true, incomingWindow.start, incomingWindow.endInclusive),
        )
    }
}

/** Maps the user-facing 0..100 curve intensity (0 = linear) to curve parameters. */
object FadeCurveSettings {
    const val DEFAULT_INTENSITY_PERCENT = 50

    fun exponentialStrength(percent: Int): Float = percent.coerceIn(0, 100) / 100f * 8f

    fun sCurveSteepness(percent: Int): Float = percent.coerceIn(0, 100) / 100f * 16f
}

/**
 * Complementary single-curve fade: outgoing = curve mirrored, incoming = 1 - outgoing, so the
 * two gains always sum to 1 and the transition has no hole or bump from the curve itself.
 */
fun complementaryFadeGains(curve: VolumeCurve, progress: Float): AutomixMath.Volumes {
    val outgoing = curve.fadeIn(1f - VolumeCurve.clamp01(progress))
    return AutomixMath.Volumes(outgoing, (1f - outgoing).coerceIn(0f, 1f))
}

/**
 * Per-block gain smoothing: moves current toward target by at most maxStepPerBlock,
 * so abrupt target changes (cancellation, effect hand-off) never click.
 */
class GainSlew(private val maxStepPerBlock: Float, initial: Float = 1f) {
    init {
        require(maxStepPerBlock > 0f && maxStepPerBlock.isFinite())
    }

    var current: Float = initial
        private set

    fun next(target: Float): Float {
        require(target.isFinite()) { "Target gain must be finite" }
        val delta = target - current
        current = if (abs(delta) <= maxStepPerBlock) target else current + maxStepPerBlock * kotlin.math.sign(delta)
        return current
    }
}
