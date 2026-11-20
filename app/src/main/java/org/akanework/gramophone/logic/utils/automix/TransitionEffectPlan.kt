/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

internal enum class TransitionEffect(val id: String) {
    BASS_SWAP("bass_swap"),
    VOCAL_SWAP("vocal_swap"),
    EQ_TRANSITION("eq_transition"),
    FILTER_SWEEP("filter_sweep"),
    HIGH_PASS_INTRO("high_pass_intro"),
    RISE("rise"),
    ECHO("echo_out"),
    REVERB("reverb_tail"),
}

internal enum class EffectCurve {
    LINEAR, SMOOTH;

    fun apply(value: Float): Float = when (this) {
        LINEAR -> value
        SMOOTH -> value * value * (3f - 2f * value)
    }
}

internal data class ScheduledEffect(
    val effect: TransitionEffect,
    val start: Float = 0f,
    val end: Float = 1f,
    val intensity: Float = 1f,
    val curve: EffectCurve = EffectCurve.LINEAR,
) {
    init {
        require(start.isFinite() && end.isFinite() && start >= 0f && end <= 1f && start < end)
        require(intensity.isFinite() && intensity in 0f..1f)
    }

    fun progress(position: Float): Float {
        require(position.isFinite())
        return curve.apply(((position - start) / (end - start)).coerceIn(0f, 1f))
    }
}

internal data class TransitionEffectPlan(
    val effects: List<ScheduledEffect>,
    val rejected: List<TransitionEffect>,
) {
    companion object {
        val selectableIds = setOf("bass_swap", "vocal_swap", "eq_transition", "filter_sweep",
            "high_pass_intro", "rise", "echo_out", "reverb_tail")

        fun create(style: String, additional: Set<String>, intensity: Float = 1f): TransitionEffectPlan {
            require(intensity.isFinite() && intensity in 0f..1f)
            require(additional.all { it in selectableIds })
            val primary = TransitionEffect.entries.firstOrNull { it.id == style }
            val requested = listOfNotNull(primary) + TransitionEffect.entries.filter {
                it.id in additional && it != primary
            }
            val arbitration = AutoMixEffectArbitrator.evaluate(
                requested.map { checkNotNull(AutoMixEffect.fromId(it.id)) }
            ).associateBy { it.evaluation.effect }
            val accepted = mutableListOf<ScheduledEffect>()
            val rejected = mutableListOf<TransitionEffect>()
            for (effect in requested) {
                val selection = checkNotNull(arbitration[checkNotNull(AutoMixEffect.fromId(effect.id))])
                if (selection.state != EffectLifecycleState.SELECTED) {
                    rejected += effect
                    continue
                }
                accepted.add(when (effect) {
                    TransitionEffect.BASS_SWAP, TransitionEffect.VOCAL_SWAP ->
                        ScheduledEffect(effect, start = 0.5f, intensity = intensity, curve = EffectCurve.SMOOTH)
                    TransitionEffect.EQ_TRANSITION ->
                        ScheduledEffect(effect, intensity = intensity, curve = EffectCurve.SMOOTH)
                    else -> ScheduledEffect(effect, intensity = intensity)
                })
            }
            return TransitionEffectPlan(accepted.toList(), rejected.toList())
        }
    }
}
