/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

import com.siw.plug.logic.utils.automix.stems.StemAvailability

internal enum class EffectDecisionReason {
    USER_PRESET,
    USER_ADDITIONAL_EFFECT,
    CROSSFADE_BASE_TRANSITION,
    BPM_UNAVAILABLE,
    BPM_DIFFERENCE_TOO_LARGE,
    BPM_COMPATIBLE,
    BPM_MODERATE_DIFFERENCE,
    BPM_DIFFERENCE_WITHIN_RANGE,
    BPM_RISING,
    BPM_NOT_RISING_FAST,
    BEAT_ALIGNMENT_UNAVAILABLE,
    VOCAL_ENERGY_HIGH,
    VOCAL_THRESHOLD_NOT_MET_OR_UNAVAILABLE,
    FILTER_SLOT_CONFLICT,
    BEAT_GRID_COMPATIBLE,
    BEAT_GRID_UNAVAILABLE,
    BEAT_GRID_LOW_CONFIDENCE,
    INSUFFICIENT_ANALYSIS_DATA,
    BPM_HALF_TIME_SELECTED,
    BPM_DOUBLE_TIME_SELECTED,
    BPM_CANDIDATE_OUT_OF_RANGE,
    LOOP_GRID_UNAVAILABLE,
    LOOP_FALLBACK_CROSSFADE,
    LOUDNESS_MATCH_APPLIED,
    LOUDNESS_DATA_UNAVAILABLE,
    LOUDNESS_WITHIN_DEADBAND,
    LOUDNESS_SOURCES_NOT_COMPARABLE,
    LOOP_TRANSITION_READY,
    BEAT_JUMP_REJECTED,
    BEAT_LOOP_REJECTED,
    ENERGY_MATCH_APPLIED,
    ENERGY_WITHIN_THRESHOLD,
    ENERGY_DATA_UNAVAILABLE,
    STEM_TRANSITION_APPLIED,
    STEM_TRANSITION_NOT_APPLIED,
    STEM_TRANSITION_FAILED,
}

internal enum class EffectSelectionStatus { SELECTED, SKIPPED }
internal enum class EffectExecutionStatus { PLANNED, PREPARED, EXECUTING, COMPLETED, FAILED }
internal enum class TransitionExecutionStatus { ACTIVE, COMPLETED }

internal data class BeatmatchEvidence(
    val outgoingBpm: Float?,
    val incomingBpm: Float?,
    val outgoingGridConfidence: Float?,
    val incomingGridConfidence: Float?,
    val minimumGridConfidence: Float,
    val maximumTempoDeviation: Float,
    val interpretation: String = BpmInterpretation.NORMAL.name,
    val selectedSpeed: Float? = null,
    val effectiveIncomingBpm: Float? = null,
    val pitchPreserved: Boolean = true,
)

internal data class EffectSelectionExplanation(
    val effectId: String,
    val selectionStatus: EffectSelectionStatus,
    val reasons: List<EffectDecisionReason>,
    val executionStatus: EffectExecutionStatus?,
    val beatmatchEvidence: BeatmatchEvidence? = null,
    val processedFrames: Long? = null,
    val sampleRate: Int? = null,
    val executionDetail: String? = null,
    val tempoInterpretation: String? = null,
    val tempoRatio: Float? = null,
    val effectiveIncomingBpm: Float? = null,
    val pitchPreserved: Boolean? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val timingLimitation: String? = null,
)

internal data class TransitionAnalysisSnapshot(
    val generation: Long,
    val outgoingId: String,
    val incomingId: String,
    val outgoingIndex: Int,
    val incomingIndex: Int,
    val outgoingTitle: String?,
    val incomingTitle: String?,
    val durationMs: Long,
    val transitionStyle: String,
    val transitionReasons: List<EffectDecisionReason>,
    val selectorDifferencePercent: Float?,
    val selectorThresholdPercent: Float?,
    val selectorRisingPercent: Float?,
    val selectorRisingThresholdPercent: Float?,
    val selectorVocalThreshold: Float?,
    val status: TransitionExecutionStatus,
    val analyzer: String?,
    val outgoingBpm: Float?,
    val incomingBpm: Float?,
    val outgoingGridConfidence: Float?,
    val incomingGridConfidence: Float?,
    val outgoingKey: String?,
    val incomingKey: String?,
    val outgoingVocalEnergy: Float?,
    val incomingVocalEnergy: Float?,
    val effects: List<EffectSelectionExplanation>,
    val outgoingLufs: Float? = null,
    val incomingLufs: Float? = null,
    val outgoingStems: StemAvailability? = null,
    val incomingStems: StemAvailability? = null,
)

internal object TransitionExplanationEngine {
    fun updateExecution(
        snapshot: TransitionAnalysisSnapshot,
        identity: TransitionIdentity,
        telemetry: Map<String, EffectRuntimeTelemetry>,
    ): TransitionAnalysisSnapshot? {
        if (!TransitionGeneration.isCurrent(snapshot.identity, identity)) return null
        return snapshot.copy(effects = snapshot.effects.map { explanation ->
            if (explanation.selectionStatus != EffectSelectionStatus.SELECTED) return@map explanation
            val runtime = telemetry[explanation.effectId] ?: return@map explanation
            explanation.copy(
                executionStatus = runtime.state.toExecutionStatus(),
                processedFrames = runtime.processedFrames,
                sampleRate = runtime.sampleRate,
                executionDetail = runtime.detail,
            )
        })
    }

    fun explainBeatmatch(
        aligned: Boolean,
        outgoingBpm: Float?,
        incomingBpm: Float?,
        outgoingGrid: BeatPhaseAligner.Grid?,
        incomingGrid: BeatPhaseAligner.Grid?,
        evaluation: BpmMatchEvaluation? = null,
    ): EffectSelectionExplanation {
        val selected = evaluation?.selected
        val reason = when {
            selected?.interpretation == BpmInterpretation.HALF_TIME ->
                EffectDecisionReason.BPM_HALF_TIME_SELECTED
            selected?.interpretation == BpmInterpretation.DOUBLE_TIME ->
                EffectDecisionReason.BPM_DOUBLE_TIME_SELECTED
            aligned -> EffectDecisionReason.BEAT_GRID_COMPATIBLE
            AutomixMath.tempoRatio(outgoingBpm, incomingBpm) == null ->
                if (outgoingBpm == null || incomingBpm == null || outgoingBpm <= 0f || incomingBpm <= 0f ||
                    !outgoingBpm.isFinite() || !incomingBpm.isFinite()
                ) EffectDecisionReason.BPM_UNAVAILABLE
                else EffectDecisionReason.BPM_DIFFERENCE_TOO_LARGE
            outgoingGrid == null || incomingGrid == null -> EffectDecisionReason.BEAT_GRID_UNAVAILABLE
            outgoingGrid.confidence < BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE ||
                incomingGrid.confidence < BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE ->
                EffectDecisionReason.BEAT_GRID_LOW_CONFIDENCE
            else -> EffectDecisionReason.INSUFFICIENT_ANALYSIS_DATA
        }
        val effectId = if (selected == null || selected.interpretation == BpmInterpretation.NORMAL) {
            "beatmatch"
        } else {
            AutoMixEffect.HALF_TIME_DOUBLE_TIME.id
        }
        return EffectSelectionExplanation(effectId,
            if (aligned) EffectSelectionStatus.SELECTED else EffectSelectionStatus.SKIPPED,
            listOf(reason), if (aligned) EffectExecutionStatus.PLANNED else null,
            BeatmatchEvidence(outgoingBpm, incomingBpm, outgoingGrid?.confidence, incomingGrid?.confidence,
                BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE, AutomixMath.DEFAULT_MAX_TEMPO_DEVIATION,
                selected?.interpretation?.name ?: BpmInterpretation.NORMAL.name,
                selected?.incomingSpeed, selected?.effectiveIncomingBpm, pitchPreserved = true),
            tempoInterpretation = selected?.interpretation?.name ?: BpmInterpretation.NORMAL.name,
            tempoRatio = selected?.incomingSpeed,
            effectiveIncomingBpm = selected?.effectiveIncomingBpm,
            pitchPreserved = true,
        )
    }

    fun explain(
        plan: TransitionEffectPlan,
        style: String,
        automaticReasons: List<EffectDecisionReason>?,
        additionalEffects: Set<String>,
    ): List<EffectSelectionExplanation> =
        (if (style != "vinyl_brake") listOf(EffectSelectionExplanation(
            if (style == "exponential_fade" || style == "s_curve_fade") style else "crossfade",
            EffectSelectionStatus.SELECTED,
            listOf(
                if (style == "exponential_fade" || style == "s_curve_fade")
                    EffectDecisionReason.USER_PRESET else EffectDecisionReason.CROSSFADE_BASE_TRANSITION
            ), EffectExecutionStatus.PLANNED
        )) else emptyList()) + plan.effects.map { scheduled ->
            val effect = scheduled.effect
            val reasons = if (effect.id == style) {
                automaticReasons ?: listOf(EffectDecisionReason.USER_PRESET)
            } else {
                check(effect.id in additionalEffects)
                listOf(EffectDecisionReason.USER_ADDITIONAL_EFFECT)
            }
            check(reasons.isNotEmpty())
            EffectSelectionExplanation(effect.id, EffectSelectionStatus.SELECTED, reasons,
                EffectExecutionStatus.PLANNED)
        } + plan.rejected.map {
            EffectSelectionExplanation(it.id, EffectSelectionStatus.SKIPPED,
                listOf(EffectDecisionReason.FILTER_SLOT_CONFLICT), null)
        }

    private fun EffectLifecycleState.toExecutionStatus(): EffectExecutionStatus? = when (this) {
        EffectLifecycleState.AVAILABLE,
        EffectLifecycleState.EVALUATED,
        EffectLifecycleState.SELECTED,
        EffectLifecycleState.UNAVAILABLE,
        EffectLifecycleState.REJECTED -> null
        EffectLifecycleState.PREPARED -> EffectExecutionStatus.PREPARED
        EffectLifecycleState.EXECUTING -> EffectExecutionStatus.EXECUTING
        EffectLifecycleState.COMPLETED -> EffectExecutionStatus.COMPLETED
        EffectLifecycleState.FAILED -> EffectExecutionStatus.FAILED
    }
}

internal val TransitionAnalysisSnapshot.identity: TransitionIdentity
    get() = TransitionIdentity(generation, outgoingId, incomingId)
