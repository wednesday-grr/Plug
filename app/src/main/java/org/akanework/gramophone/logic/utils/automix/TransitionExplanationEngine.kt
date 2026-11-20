/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

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
}

internal enum class EffectSelectionStatus { SELECTED, SKIPPED }
internal enum class EffectExecutionStatus { PLANNED }
internal enum class TransitionExecutionStatus { ACTIVE, COMPLETED }

internal data class BeatmatchEvidence(
    val outgoingBpm: Float?,
    val incomingBpm: Float?,
    val outgoingGridConfidence: Float?,
    val incomingGridConfidence: Float?,
    val minimumGridConfidence: Float,
    val maximumTempoDeviation: Float,
)

internal data class EffectSelectionExplanation(
    val effectId: String,
    val selectionStatus: EffectSelectionStatus,
    val reasons: List<EffectDecisionReason>,
    val executionStatus: EffectExecutionStatus?,
    val beatmatchEvidence: BeatmatchEvidence? = null,
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
)

internal object TransitionExplanationEngine {
    fun explainBeatmatch(
        aligned: Boolean,
        outgoingBpm: Float?,
        incomingBpm: Float?,
        outgoingGrid: BeatPhaseAligner.Grid?,
        incomingGrid: BeatPhaseAligner.Grid?,
    ): EffectSelectionExplanation {
        val reason = when {
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
        return EffectSelectionExplanation("beatmatch",
            if (aligned) EffectSelectionStatus.SELECTED else EffectSelectionStatus.SKIPPED,
            listOf(reason), if (aligned) EffectExecutionStatus.PLANNED else null,
            BeatmatchEvidence(outgoingBpm, incomingBpm, outgoingGrid?.confidence, incomingGrid?.confidence,
                BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE, AutomixMath.DEFAULT_MAX_TEMPO_DEVIATION))
    }

    fun explain(
        plan: TransitionEffectPlan,
        style: String,
        automaticReasons: List<EffectDecisionReason>?,
        additionalEffects: Set<String>,
    ): List<EffectSelectionExplanation> =
        (if (style != "vinyl_brake") listOf(EffectSelectionExplanation(
            "crossfade", EffectSelectionStatus.SELECTED,
            listOf(EffectDecisionReason.CROSSFADE_BASE_TRANSITION), EffectExecutionStatus.PLANNED
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
}
