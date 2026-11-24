/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import com.siw.plug.logic.utils.automix.EffectCurve
import com.siw.plug.logic.utils.automix.ScheduledEffect
import com.siw.plug.logic.utils.automix.TransitionEffect
import com.siw.plug.logic.utils.automix.TransitionEffectPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransitionEffectPlanTest {
    @Test
    fun combinesPresetWithIndependentEffectsWithoutDuplicates() {
        val plan = TransitionEffectPlan.create("bass_swap", setOf("bass_swap", "vocal_swap",
            "filter_sweep", "echo_out", "reverb_tail"))
        assertEquals(listOf(TransitionEffect.BASS_SWAP, TransitionEffect.VOCAL_SWAP,
            TransitionEffect.FILTER_SWEEP, TransitionEffect.ECHO, TransitionEffect.REVERB),
            plan.effects.map { it.effect })
        assertEquals(emptyList<TransitionEffect>(), plan.rejected)
    }

    @Test
    fun eqAndOtherEffectsRespectGlobalIntensity() {
        val plan = TransitionEffectPlan.create(
            "bass_swap", setOf("eq_transition", "echo_out", "reverb_tail"), 0.4f
        )
        assertEquals(listOf(TransitionEffect.BASS_SWAP, TransitionEffect.EQ_TRANSITION,
            TransitionEffect.ECHO, TransitionEffect.REVERB), plan.effects.map { it.effect })
        plan.effects.forEach { assertEquals(0.4f, it.intensity, 0f) }
        assertThrows(IllegalArgumentException::class.java) {
            TransitionEffectPlan.create("fade", emptySet(), Float.NaN)
        }
    }

    @Test
    fun incomingFilterConflictRespectsPresetAndStablePriority() {
        val plan = TransitionEffectPlan.create("high_pass_intro", setOf("filter_sweep", "rise"))
        assertEquals(listOf(TransitionEffect.HIGH_PASS_INTRO), plan.effects.map { it.effect })
        assertEquals(listOf(TransitionEffect.FILTER_SWEEP, TransitionEffect.RISE), plan.rejected)
        val extra = TransitionEffectPlan.create("fade", setOf("rise", "filter_sweep"))
        assertEquals(listOf(TransitionEffect.FILTER_SWEEP), extra.effects.map { it.effect })
        assertEquals(listOf(TransitionEffect.RISE), extra.rejected)
    }

    @Test
    fun automationRespectsWindowCurveAndBounds() {
        val effect = ScheduledEffect(TransitionEffect.REVERB, 0.2f, 0.8f, 0.5f, EffectCurve.SMOOTH)
        assertEquals(0f, effect.progress(0.1f), 0.0001f)
        assertEquals(0.5f, effect.progress(0.5f), 0.0001f)
        assertEquals(1f, effect.progress(0.9f), 0.0001f)
        assertThrows(IllegalArgumentException::class.java) {
            ScheduledEffect(TransitionEffect.REVERB, 0.8f, 0.2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransitionEffectPlan.create("fade", setOf("invalid"))
        }
    }
}
