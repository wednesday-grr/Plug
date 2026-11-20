/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import java.util.concurrent.atomic.AtomicLong

enum class AutoMixEffect(val id: String) {
    CROSSFADE("crossfade"),
    LINEAR_FADE("fade"),
    SMOOTH_CROSSFADE("smooth"),
    BEATMATCH("beatmatch"),
    BPM_MATCHING("bpm_matching"),
    TEMPO_RAMP("tempo_ramp"),
    VINYL_BRAKE("vinyl_brake"),
    TREMOLO_FADE("tremolo_fade"),
    BASS_SWAP("bass_swap"),
    VOCAL_SWAP("vocal_swap"),
    EQ_TRANSITION("eq_transition"),
    FILTER_SWEEP("filter_sweep"),
    HIGH_PASS_INTRO("high_pass_intro"),
    RISE("rise"),
    ECHO("echo_out"),
    REVERB("reverb_tail"),
    REPLAY_GAIN("replay_gain"),
    BEAT_JUMP("beat_jump"),
    BEAT_LOOP("beat_loop"),
    HALF_TIME_DOUBLE_TIME("half_time_double_time"),
    EXPONENTIAL_FADE("exponential_fade"),
    S_CURVE_FADE("s_curve_fade"),
    LOUDNESS_MATCHING("loudness_matching"),
    AUTOMATIC_GAIN_CONTROL("agc"),
    BASS_BOOST("bass_boost"),
    LOOP_TRANSITION("loop_transition"),
    VOCAL_SEPARATION("vocal_separation"),
    INSTRUMENTAL_SEPARATION("instrumental_separation"),
    INSTRUMENTAL_DROP("instrumental_drop"),
    INSTRUMENTAL_FADE("instrumental_fade"),
    VOCAL_TO_INSTRUMENTAL("vocal_to_instrumental"),
    INSTRUMENTAL_TO_VOCAL("instrumental_to_vocal"),
    INDEPENDENT_STEM_GAIN("independent_stem_gain"),
    STEM_AUTOMIX("stem_automix"),
    ENERGY_MATCHING("energy_matching");

    companion object {
        fun fromId(id: String): AutoMixEffect? = entries.firstOrNull { it.id == id }
    }
}

enum class EffectLifecycleState {
    UNAVAILABLE,
    AVAILABLE,
    EVALUATED,
    SELECTED,
    PREPARED,
    EXECUTING,
    COMPLETED,
    FAILED,
    REJECTED,
}

enum class EffectCompatibility {
    COMPATIBLE,
    CONDITIONALLY_COMPATIBLE,
    MUTUALLY_EXCLUSIVE,
    CONFLICTING,
    NOT_SUPPORTED,
    UNKNOWN,
}

enum class EffectGainParameter {
    OUTGOING_DECK_GAIN,
    INCOMING_DECK_GAIN,
    OUTGOING_REPLAY_GAIN,
    INCOMING_REPLAY_GAIN,
    OUTGOING_BASS_GAIN,
    INCOMING_BASS_GAIN,
    OUTGOING_VOCAL_GAIN,
    INCOMING_VOCAL_GAIN,
    OUTGOING_EQ_GAIN,
    INCOMING_EQ_GAIN,
    OUTGOING_FILTER,
    INCOMING_FILTER,
    OUTGOING_ECHO,
    OUTGOING_REVERB,
    OUTGOING_PLAYBACK_SPEED,
    INCOMING_PLAYBACK_SPEED,
    OUTGOING_VOCAL_STEM_GAIN,
    OUTGOING_INSTRUMENTAL_STEM_GAIN,
    INCOMING_VOCAL_STEM_GAIN,
    INCOMING_INSTRUMENTAL_STEM_GAIN,
}

data class AutoMixEffectCapability(
    val effect: AutoMixEffect,
    val runtimeAvailable: Boolean,
    val requiredAudioSources: Int,
    val requiredStemSources: Int = 0,
    val requiresBeatGrid: Boolean = false,
    val requiresOfflinePreprocessing: Boolean = false,
    val gainParameters: Set<EffectGainParameter> = emptySet(),
) {
    init {
        require(requiredAudioSources > 0)
        require(requiredStemSources >= 0)
    }
}

object AutoMixEffectCapabilityRegistry {
    private val capabilities = AutoMixEffect.entries.associateWith(::capabilityFor)

    fun all(): List<AutoMixEffectCapability> = AutoMixEffect.entries.mapNotNull(capabilities::get)

    fun capability(effect: AutoMixEffect): AutoMixEffectCapability =
        checkNotNull(capabilities[effect])

    fun capability(id: String): AutoMixEffectCapability? =
        AutoMixEffect.fromId(id)?.let(::capability)

    private fun capabilityFor(effect: AutoMixEffect): AutoMixEffectCapability {
        val supported = effect in setOf(
            AutoMixEffect.CROSSFADE,
            AutoMixEffect.LINEAR_FADE,
            AutoMixEffect.SMOOTH_CROSSFADE,
            AutoMixEffect.BEATMATCH,
            AutoMixEffect.BPM_MATCHING,
            AutoMixEffect.TEMPO_RAMP,
            AutoMixEffect.VINYL_BRAKE,
            AutoMixEffect.TREMOLO_FADE,
            AutoMixEffect.BASS_SWAP,
            AutoMixEffect.VOCAL_SWAP,
            AutoMixEffect.EQ_TRANSITION,
            AutoMixEffect.FILTER_SWEEP,
            AutoMixEffect.HIGH_PASS_INTRO,
            AutoMixEffect.RISE,
            AutoMixEffect.ECHO,
            AutoMixEffect.REVERB,
            AutoMixEffect.REPLAY_GAIN,
        )
        val gainParameters = when (effect) {
            AutoMixEffect.CROSSFADE, AutoMixEffect.LINEAR_FADE, AutoMixEffect.SMOOTH_CROSSFADE,
            AutoMixEffect.EXPONENTIAL_FADE, AutoMixEffect.S_CURVE_FADE ->
                setOf(EffectGainParameter.OUTGOING_DECK_GAIN, EffectGainParameter.INCOMING_DECK_GAIN)
            AutoMixEffect.BASS_SWAP -> setOf(
                EffectGainParameter.OUTGOING_BASS_GAIN, EffectGainParameter.INCOMING_BASS_GAIN
            )
            AutoMixEffect.VOCAL_SWAP -> setOf(
                EffectGainParameter.OUTGOING_VOCAL_GAIN, EffectGainParameter.INCOMING_VOCAL_GAIN
            )
            AutoMixEffect.EQ_TRANSITION -> setOf(
                EffectGainParameter.OUTGOING_EQ_GAIN, EffectGainParameter.INCOMING_EQ_GAIN
            )
            AutoMixEffect.FILTER_SWEEP -> setOf(
                EffectGainParameter.OUTGOING_FILTER, EffectGainParameter.INCOMING_FILTER
            )
            AutoMixEffect.HIGH_PASS_INTRO, AutoMixEffect.RISE ->
                setOf(EffectGainParameter.INCOMING_FILTER)
            AutoMixEffect.ECHO -> setOf(EffectGainParameter.OUTGOING_ECHO)
            AutoMixEffect.REVERB -> setOf(EffectGainParameter.OUTGOING_REVERB)
            AutoMixEffect.TEMPO_RAMP -> setOf(EffectGainParameter.INCOMING_PLAYBACK_SPEED)
            AutoMixEffect.VINYL_BRAKE -> setOf(EffectGainParameter.OUTGOING_PLAYBACK_SPEED)
            AutoMixEffect.BEATMATCH, AutoMixEffect.BPM_MATCHING ->
                setOf(EffectGainParameter.INCOMING_PLAYBACK_SPEED)
            AutoMixEffect.REPLAY_GAIN -> setOf(
                EffectGainParameter.OUTGOING_REPLAY_GAIN, EffectGainParameter.INCOMING_REPLAY_GAIN
            )
            AutoMixEffect.LOUDNESS_MATCHING, AutoMixEffect.AUTOMATIC_GAIN_CONTROL,
            AutoMixEffect.ENERGY_MATCHING -> setOf(
                EffectGainParameter.OUTGOING_DECK_GAIN, EffectGainParameter.INCOMING_DECK_GAIN
            )
            AutoMixEffect.INSTRUMENTAL_DROP, AutoMixEffect.INSTRUMENTAL_FADE ->
                setOf(EffectGainParameter.OUTGOING_INSTRUMENTAL_STEM_GAIN,
                    EffectGainParameter.INCOMING_INSTRUMENTAL_STEM_GAIN)
            AutoMixEffect.VOCAL_TO_INSTRUMENTAL -> setOf(
                EffectGainParameter.OUTGOING_VOCAL_STEM_GAIN,
                EffectGainParameter.INCOMING_INSTRUMENTAL_STEM_GAIN
            )
            AutoMixEffect.INSTRUMENTAL_TO_VOCAL -> setOf(
                EffectGainParameter.OUTGOING_INSTRUMENTAL_STEM_GAIN,
                EffectGainParameter.INCOMING_VOCAL_STEM_GAIN
            )
            AutoMixEffect.INDEPENDENT_STEM_GAIN, AutoMixEffect.STEM_AUTOMIX -> setOf(
                EffectGainParameter.OUTGOING_VOCAL_STEM_GAIN,
                EffectGainParameter.OUTGOING_INSTRUMENTAL_STEM_GAIN,
                EffectGainParameter.INCOMING_VOCAL_STEM_GAIN,
                EffectGainParameter.INCOMING_INSTRUMENTAL_STEM_GAIN
            )
            else -> emptySet()
        }
        val stems = effect in setOf(
            AutoMixEffect.VOCAL_SEPARATION,
            AutoMixEffect.INSTRUMENTAL_SEPARATION,
            AutoMixEffect.INSTRUMENTAL_DROP,
            AutoMixEffect.INSTRUMENTAL_FADE,
            AutoMixEffect.VOCAL_TO_INSTRUMENTAL,
            AutoMixEffect.INSTRUMENTAL_TO_VOCAL,
            AutoMixEffect.INDEPENDENT_STEM_GAIN,
            AutoMixEffect.STEM_AUTOMIX,
        )
        val sourceCount = when (effect) {
            AutoMixEffect.CROSSFADE, AutoMixEffect.STEM_AUTOMIX -> 2
            AutoMixEffect.BEAT_JUMP, AutoMixEffect.BEAT_LOOP -> 1
            else -> 1
        }
        return AutoMixEffectCapability(
            effect = effect,
            runtimeAvailable = supported,
            requiredAudioSources = sourceCount,
            requiredStemSources = if (effect == AutoMixEffect.STEM_AUTOMIX) 4 else if (stems) 1 else 0,
            requiresBeatGrid = effect in setOf(
                AutoMixEffect.BEATMATCH, AutoMixEffect.BEAT_JUMP,
                AutoMixEffect.BEAT_LOOP, AutoMixEffect.LOOP_TRANSITION,
                AutoMixEffect.HALF_TIME_DOUBLE_TIME
            ),
            requiresOfflinePreprocessing = effect in setOf(
                AutoMixEffect.VOCAL_SEPARATION, AutoMixEffect.INSTRUMENTAL_SEPARATION
            ),
            gainParameters = gainParameters,
        )
    }
}

data class EffectEvaluation(
    val effect: AutoMixEffect,
    val capability: AutoMixEffectCapability,
    val eligible: Boolean,
    val reason: String?,
    val evidence: Map<String, Double> = emptyMap(),
    val thresholds: Map<String, Double> = emptyMap(),
    val confidence: Double? = null,
)

data class EffectSelectionResult(
    val evaluation: EffectEvaluation,
    val state: EffectLifecycleState,
    val reason: String?,
    val conflictsWith: Set<AutoMixEffect> = emptySet(),
    val priorityRank: Int? = null,
    val fallback: EffectFallback? = null,
)

data class EffectCompatibilityResult(
    val first: AutoMixEffect,
    val second: AutoMixEffect,
    val compatibility: EffectCompatibility,
    val reason: String,
)

data class EffectFallback(
    val requestedEffect: AutoMixEffect,
    val fallbackEffect: AutoMixEffect?,
    val reason: String,
    val generation: Long?,
)

fun interface EffectFallbackPolicy {
    fun fallbackFor(effect: AutoMixEffect, reason: String, generation: Long?): EffectFallback
}

object NoAutomaticEffectFallback : EffectFallbackPolicy {
    override fun fallbackFor(effect: AutoMixEffect, reason: String, generation: Long?) =
        EffectFallback(effect, null, reason, generation)
}

interface EffectPriorityPolicy {
    fun order(evaluations: List<EffectEvaluation>): List<EffectEvaluation>
    fun rank(effect: AutoMixEffect): Int? = null
}

object PreserveRequestOrderPriorityPolicy : EffectPriorityPolicy {
    override fun order(evaluations: List<EffectEvaluation>): List<EffectEvaluation> = evaluations
}

class ConfiguredEffectPriorityPolicy(
    priorities: Map<AutoMixEffect, Int>,
) : EffectPriorityPolicy {
    private val configuredPriorities = priorities.toMap()

    override fun rank(effect: AutoMixEffect): Int? = configuredPriorities[effect]

    override fun order(evaluations: List<EffectEvaluation>): List<EffectEvaluation> =
        evaluations.withIndex().sortedWith(
            compareBy<IndexedValue<EffectEvaluation>> {
                configuredPriorities[it.value.effect] ?: Int.MAX_VALUE
            }.thenBy { it.index }
        ).map { it.value }
}

object AutoMixEffectCompatibilityEvaluator {
    private val incomingFilterEffects = setOf(
        AutoMixEffect.FILTER_SWEEP, AutoMixEffect.HIGH_PASS_INTRO, AutoMixEffect.RISE
    )

    fun evaluate(first: AutoMixEffect, second: AutoMixEffect): EffectCompatibilityResult {
        if (!AutoMixEffectCapabilityRegistry.capability(first).runtimeAvailable ||
            !AutoMixEffectCapabilityRegistry.capability(second).runtimeAvailable
        ) return result(first, second, EffectCompatibility.NOT_SUPPORTED,
            "At least one effect has no runtime implementation.")

        if (first in incomingFilterEffects && second in incomingFilterEffects) {
            return result(first, second, EffectCompatibility.MUTUALLY_EXCLUSIVE,
                "Both effects require the incoming deck's single filter slot.")
        }
        if (setOf(first, second) == setOf(AutoMixEffect.BASS_SWAP, AutoMixEffect.EQ_TRANSITION) ||
            setOf(first, second) == setOf(AutoMixEffect.VOCAL_SWAP, AutoMixEffect.EQ_TRANSITION)
        ) return result(first, second, EffectCompatibility.COMPATIBLE,
            "The transition processor coordinates band ownership.")
        if (setOf(first, second) == setOf(AutoMixEffect.BPM_MATCHING, AutoMixEffect.TEMPO_RAMP)) {
            return result(first, second, EffectCompatibility.COMPATIBLE,
                "The tempo ramp can consume BPM matching as its starting playback speed.")
        }
        if (setOf(first, second) == setOf(AutoMixEffect.CROSSFADE, AutoMixEffect.REPLAY_GAIN)) {
            return result(first, second, EffectCompatibility.COMPATIBLE,
                "Per-deck ReplayGain is applied before the independent crossfade envelope.")
        }

        if (setOf(first, second) == setOf(AutoMixEffect.EXPONENTIAL_FADE, AutoMixEffect.S_CURVE_FADE) ||
            setOf(first, second) == setOf(AutoMixEffect.BEAT_LOOP, AutoMixEffect.LOOP_TRANSITION)
        ) return result(first, second, EffectCompatibility.MUTUALLY_EXCLUSIVE,
            "Both effects require exclusive ownership of the same transition or loop operation.")

        if (setOf(first, second) == setOf(AutoMixEffect.AUTOMATIC_GAIN_CONTROL, AutoMixEffect.REPLAY_GAIN) ||
            setOf(first, second) == setOf(AutoMixEffect.AUTOMATIC_GAIN_CONTROL, AutoMixEffect.LOUDNESS_MATCHING) ||
            setOf(first, second) == setOf(AutoMixEffect.LOUDNESS_MATCHING, AutoMixEffect.REPLAY_GAIN) ||
            setOf(first, second) == setOf(AutoMixEffect.STEM_AUTOMIX, AutoMixEffect.INDEPENDENT_STEM_GAIN)
        ) return result(first, second, EffectCompatibility.CONFLICTING,
            "Both effects can control overlapping gain and no shared controller is defined.")

        if (setOf(first, second) == setOf(AutoMixEffect.BEAT_JUMP, AutoMixEffect.CROSSFADE) ||
            setOf(first, second) == setOf(AutoMixEffect.STEM_AUTOMIX, AutoMixEffect.CROSSFADE)
        ) return result(first, second, EffectCompatibility.CONDITIONALLY_COMPATIBLE,
            "Compatibility depends on transition state and coordinated gain ownership.")

        val shared = AutoMixEffectCapabilityRegistry.capability(first).gainParameters intersect
            AutoMixEffectCapabilityRegistry.capability(second).gainParameters
        return if (shared.isEmpty()) {
            result(first, second, EffectCompatibility.COMPATIBLE,
                "The effects use distinct registered processing controls.")
        } else {
            result(first, second, EffectCompatibility.CONFLICTING,
                "Both effects claim ${shared.joinToString()}.")
        }
    }

    private fun result(
        first: AutoMixEffect,
        second: AutoMixEffect,
        compatibility: EffectCompatibility,
        reason: String,
    ) = EffectCompatibilityResult(first, second, compatibility, reason)
}

object AutoMixEffectArbitrator {
    fun evaluate(
        requested: List<AutoMixEffect>,
        priorityPolicy: EffectPriorityPolicy = PreserveRequestOrderPriorityPolicy,
        generation: Long? = null,
        fallbackPolicy: EffectFallbackPolicy = NoAutomaticEffectFallback,
    ): List<EffectSelectionResult> {
        val evaluations = priorityPolicy.order(requested.distinct().map { effect ->
            val capability = AutoMixEffectCapabilityRegistry.capability(effect)
            EffectEvaluation(
                effect = effect,
                capability = capability,
                eligible = capability.runtimeAvailable,
                reason = if (capability.runtimeAvailable) null else "No runtime implementation is registered.",
            )
        })
        val selected = mutableListOf<AutoMixEffect>()
        val results = mutableListOf<EffectSelectionResult>()
        for (evaluation in evaluations) {
            val rank = priorityPolicy.rank(evaluation.effect)
            if (!evaluation.eligible) {
                results += EffectSelectionResult(
                    evaluation, EffectLifecycleState.UNAVAILABLE, evaluation.reason,
                    priorityRank = rank,
                    fallback = fallbackPolicy.fallbackFor(
                        evaluation.effect, evaluation.reason ?: "Unavailable", generation
                    ),
                )
                continue
            }
            val conflicts = selected.mapNotNull { prior ->
                val compatibility = AutoMixEffectCompatibilityEvaluator.evaluate(prior, evaluation.effect)
                if (compatibility.compatibility in setOf(
                        EffectCompatibility.MUTUALLY_EXCLUSIVE,
                        EffectCompatibility.CONFLICTING,
                        EffectCompatibility.NOT_SUPPORTED
                    )
                ) prior else null
            }.toSet()
            val conditional = selected.mapNotNull { prior ->
                val compatibility = AutoMixEffectCompatibilityEvaluator.evaluate(prior, evaluation.effect)
                prior.takeIf { compatibility.compatibility == EffectCompatibility.CONDITIONALLY_COMPATIBLE }
            }.toSet()
            when {
                conflicts.isNotEmpty() -> {
                    val reason = conflicts.joinToString { prior ->
                        AutoMixEffectCompatibilityEvaluator.evaluate(prior, evaluation.effect).reason
                    }
                    results += EffectSelectionResult(
                        evaluation, EffectLifecycleState.REJECTED, reason, conflicts,
                        rank, fallbackPolicy.fallbackFor(evaluation.effect, reason, generation)
                    )
                }
                conditional.isNotEmpty() -> results += EffectSelectionResult(
                    evaluation, EffectLifecycleState.EVALUATED,
                    "Compatibility requires runtime context.", conditional, rank
                )
                else -> {
                    selected += evaluation.effect
                    results += EffectSelectionResult(
                        evaluation, EffectLifecycleState.SELECTED, null, priorityRank = rank
                    )
                }
            }
        }
        return results
    }
}

data class GainOwnership(
    val parameter: EffectGainParameter,
    val effect: AutoMixEffect,
    val generation: Long,
)

class GainOwnershipRegistry {
    private val owners = mutableMapOf<EffectGainParameter, MutableSet<GainOwnership>>()
    private var activeGeneration: Long? = null

    fun beginGeneration(generation: Long) {
        require(generation > 0)
        if (activeGeneration != generation) {
            owners.clear()
            activeGeneration = generation
        }
    }

    fun claim(effect: AutoMixEffect, generation: Long): Set<GainOwnership> {
        beginGeneration(generation)
        val capability = AutoMixEffectCapabilityRegistry.capability(effect)
        val conflicts = capability.gainParameters.flatMap { parameter ->
            owners[parameter].orEmpty().filter { owner ->
                owner.effect != effect &&
                    AutoMixEffectCompatibilityEvaluator.evaluate(owner.effect, effect).compatibility !=
                    EffectCompatibility.COMPATIBLE
            }
        }.toSet()
        check(conflicts.isEmpty()) {
            "Effect $effect cannot claim gain controls already owned by ${conflicts.map { it.effect }}"
        }
        capability.gainParameters.forEach { parameter ->
            owners.getOrPut(parameter, ::mutableSetOf).add(GainOwnership(parameter, effect, generation))
        }
        return capability.gainParameters.flatMap { owners[it].orEmpty() }.toSet()
    }

    fun releaseGeneration(generation: Long) {
        if (activeGeneration == generation) {
            owners.clear()
            activeGeneration = null
        }
    }

    fun owner(parameter: EffectGainParameter): GainOwnership? = owners[parameter]?.firstOrNull()

    fun owners(parameter: EffectGainParameter): Set<GainOwnership> = owners[parameter].orEmpty().toSet()
}

data class TransitionIdentity(
    val generation: Long,
    val outgoingMediaId: String,
    val incomingMediaId: String,
) {
    init {
        require(generation > 0)
    }
}

object TransitionGeneration {
    private val sequence = AtomicLong(0)

    fun next(): Long = sequence.incrementAndGet()

    fun isCurrent(expected: TransitionIdentity, current: TransitionIdentity?): Boolean =
        current == expected
}

data class EffectRuntimeTelemetry(
    val effect: AutoMixEffect,
    val state: EffectLifecycleState,
    val processedFrames: Long? = null,
    val sampleRate: Int? = null,
    val activeGain: Float? = null,
    val targetGain: Float? = null,
    val detail: String? = null,
)

data class EffectExecutionReport(
    val identity: TransitionIdentity,
    val selection: EffectSelectionResult,
    val runtime: EffectRuntimeTelemetry?,
    val fallback: EffectFallback?,
)

data class TransitionQualityScore(
    val tempoCompatibility: Double? = null,
    val beatGridCompatibility: Double? = null,
    val keyCompatibility: Double? = null,
    val energyCompatibility: Double? = null,
    val loudnessCompatibility: Double? = null,
    val confidence: Double? = null,
)

data class TransitionRuntimeTelemetry(
    val identity: TransitionIdentity,
    val progress: Float,
    val effects: List<EffectRuntimeTelemetry>,
) {
    init {
        require(progress.isFinite() && progress in 0f..1f)
    }

    fun isCurrent(current: TransitionIdentity?): Boolean =
        TransitionGeneration.isCurrent(identity, current)
}
