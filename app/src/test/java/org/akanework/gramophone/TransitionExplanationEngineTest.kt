/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.automix.AutomixEffectSelector
import org.akanework.gramophone.logic.utils.automix.AutomixMath
import org.akanework.gramophone.logic.utils.automix.BeatPhaseAligner
import org.akanework.gramophone.logic.utils.automix.EffectDecisionReason
import org.akanework.gramophone.logic.utils.automix.EffectExecutionStatus
import org.akanework.gramophone.logic.utils.automix.EffectSelectionStatus
import org.akanework.gramophone.logic.utils.automix.TransitionEffectPlan
import org.akanework.gramophone.logic.utils.automix.TransitionExplanationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionExplanationEngineTest {
    private val grid = BeatPhaseAligner.Grid(500.0, 0.0, 0.95f)

    @Test
    fun selectorRecordsItsActualBpmBranchesAndThresholds() {
        val missing = AutomixEffectSelector.decide(120f, null, false, null, null)
        assertEquals("fade", missing.style)
        assertEquals(listOf(EffectDecisionReason.BPM_UNAVAILABLE), missing.reasons)
        assertNull(missing.differencePercent)

        val distant = AutomixEffectSelector.decide(120f, 144f, false, null, null)
        assertEquals("fade", distant.style)
        assertEquals(EffectDecisionReason.BPM_DIFFERENCE_TOO_LARGE, distant.reasons.first())
        assertEquals(20f, distant.differencePercent!!, 0.001f)
        assertEquals(15f, distant.thresholdPercent!!, 0.001f)

        val close = AutomixEffectSelector.decide(120f, 122f, true, 0.8f, 0.8f)
        assertEquals("vocal_swap", close.style)
        assertTrue(close.reasons.contains(EffectDecisionReason.BPM_COMPATIBLE))
        assertTrue(close.reasons.contains(EffectDecisionReason.VOCAL_ENERGY_HIGH))
        assertEquals(0.55f, close.vocalThreshold!!, 0f)

        val filter = AutomixEffectSelector.decide(120f, 122f, false, null, null)
        assertEquals("filter_sweep", filter.style)
        assertTrue(filter.reasons.contains(EffectDecisionReason.BEAT_ALIGNMENT_UNAVAILABLE))
        assertEquals(6f, filter.risingThresholdPercent!!, 0.001f)
    }

    @Test
    fun beatmatchReasonsAndEvidenceReflectTheAlignmentInputs() {
        fun decision(out: Float?, incoming: Float?, a: BeatPhaseAligner.Grid?, b: BeatPhaseAligner.Grid?) =
            TransitionExplanationEngine.explainBeatmatch(false, out, incoming, a, b)

        assertEquals(EffectDecisionReason.BPM_UNAVAILABLE, decision(null, 120f, null, null).reasons.single())
        assertEquals(EffectDecisionReason.BPM_DIFFERENCE_TOO_LARGE, decision(100f, 140f, grid, grid).reasons.single())
        assertEquals(EffectDecisionReason.BEAT_GRID_UNAVAILABLE, decision(120f, 120f, grid, null).reasons.single())
        val low = decision(120f, 120f, grid, grid.copy(confidence = 0.5f))
        assertEquals(EffectDecisionReason.BEAT_GRID_LOW_CONFIDENCE, low.reasons.single())
        assertEquals(0.5f, low.beatmatchEvidence!!.incomingGridConfidence!!, 0f)
        assertEquals(BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE, low.beatmatchEvidence!!.minimumGridConfidence, 0f)
        assertEquals(AutomixMath.DEFAULT_MAX_TEMPO_DEVIATION, low.beatmatchEvidence!!.maximumTempoDeviation, 0f)
        assertEquals(EffectDecisionReason.INSUFFICIENT_ANALYSIS_DATA,
            decision(120f, 120f, grid, grid).reasons.single())
        val selected = TransitionExplanationEngine.explainBeatmatch(true, 120f, 120f, grid, grid)
        assertEquals(EffectSelectionStatus.SELECTED, selected.selectionStatus)
        assertEquals(EffectExecutionStatus.PLANNED, selected.executionStatus)
        assertEquals(EffectDecisionReason.BEAT_GRID_COMPATIBLE, selected.reasons.single())
        assertNull(decision(null, 120f, null, null).beatmatchEvidence!!.outgoingBpm)
    }

    @Test
    fun explainsEachScheduledAndActuallyRejectedEffectWithoutInventingOtherDecisions() {
        val plan = TransitionEffectPlan.create("high_pass_intro",
            setOf("bass_swap", "filter_sweep", "echo_out", "reverb_tail"))
        val effects = TransitionExplanationEngine.explain(plan, "high_pass_intro", null,
            setOf("bass_swap", "filter_sweep", "echo_out", "reverb_tail"))
        assertEquals(6, effects.size)
        assertEquals(listOf(EffectDecisionReason.USER_PRESET),
            effects.single { it.effectId == "high_pass_intro" }.reasons)
        for (effect in effects.filter { it.selectionStatus == EffectSelectionStatus.SELECTED }) {
            assertTrue(effect.reasons.isNotEmpty())
            assertEquals(EffectExecutionStatus.PLANNED, effect.executionStatus)
        }
        val rejected = effects.single { it.effectId == "filter_sweep" }
        assertEquals(EffectSelectionStatus.SKIPPED, rejected.selectionStatus)
        assertEquals(listOf(EffectDecisionReason.FILTER_SLOT_CONFLICT), rejected.reasons)
        assertNull(rejected.executionStatus)
        assertFalse(effects.any { it.effectId == "vocal_swap" || it.effectId == "rise" })
    }

    @Test
    fun automaticPrimaryRetainsDecisionReasonsAndRequiresAtLeastOne() {
        val decision = AutomixEffectSelector.decide(120f, 124f, true, 0.2f, 0.2f)
        val plan = TransitionEffectPlan.create(decision.style, setOf("echo_out"))
        val effects = TransitionExplanationEngine.explain(plan, decision.style, decision.reasons, setOf("echo_out"))
        assertEquals(decision.reasons, effects.single { it.effectId == decision.style }.reasons)
        assertEquals(listOf(EffectDecisionReason.USER_ADDITIONAL_EFFECT), effects.last().reasons)
        assertThrows(IllegalStateException::class.java) {
            TransitionExplanationEngine.explain(plan, decision.style, emptyList(), setOf("echo_out"))
        }
    }
}
