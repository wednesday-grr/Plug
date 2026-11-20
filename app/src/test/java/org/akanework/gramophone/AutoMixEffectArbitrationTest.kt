/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.automix.AutoMixEffect
import org.akanework.gramophone.logic.utils.automix.AutoMixEffectArbitrator
import org.akanework.gramophone.logic.utils.automix.AutoMixEffectCapabilityRegistry
import org.akanework.gramophone.logic.utils.automix.AutoMixEffectCompatibilityEvaluator
import org.akanework.gramophone.logic.utils.automix.ConfiguredEffectPriorityPolicy
import org.akanework.gramophone.logic.utils.automix.EffectCompatibility
import org.akanework.gramophone.logic.utils.automix.EffectGainParameter
import org.akanework.gramophone.logic.utils.automix.EffectLifecycleState
import org.akanework.gramophone.logic.utils.automix.GainOwnershipRegistry
import org.akanework.gramophone.logic.utils.automix.NoAutomaticEffectFallback
import org.akanework.gramophone.logic.utils.automix.PreserveRequestOrderPriorityPolicy
import org.akanework.gramophone.logic.utils.automix.TransitionGeneration
import org.akanework.gramophone.logic.utils.automix.TransitionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixEffectArbitrationTest {
    @Test
    fun registryCoversAllEffectsAndKeepsFutureEffectsUnavailable() {
        val capabilities = AutoMixEffectCapabilityRegistry.all()
        assertEquals(AutoMixEffect.entries.toSet(), capabilities.map { it.effect }.toSet())
        assertEquals(AutoMixEffect.entries.size, capabilities.size)
        assertTrue(AutoMixEffectCapabilityRegistry.capability(AutoMixEffect.ECHO).runtimeAvailable)
        assertFalse(
            AutoMixEffectCapabilityRegistry.capability(AutoMixEffect.VOCAL_SEPARATION).runtimeAvailable
        )
    }

    @Test
    fun defaultSelectionPreservesRequestOrderAndReportsUnavailableFallbackWithoutChoosingOne() {
        val result = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.ECHO, AutoMixEffect.VOCAL_SEPARATION),
            PreserveRequestOrderPriorityPolicy,
        )
        assertEquals(EffectLifecycleState.SELECTED, result[0].state)
        assertEquals(EffectLifecycleState.UNAVAILABLE, result[1].state)
        assertNotNull(result[1].fallback)
        assertNull(result[1].fallback?.fallbackEffect)
        assertNull(result[0].priorityRank)
    }

    @Test
    fun configuredPrioritiesOnlyAffectEffectsWhenExplicitlyProvided() {
        val default = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.ECHO, AutoMixEffect.REVERB),
            PreserveRequestOrderPriorityPolicy,
        )
        val configured = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.ECHO, AutoMixEffect.REVERB),
            ConfiguredEffectPriorityPolicy(mapOf(AutoMixEffect.REVERB to 1, AutoMixEffect.ECHO to 0)),
        )
        assertEquals(listOf(AutoMixEffect.ECHO, AutoMixEffect.REVERB), default.map { it.evaluation.effect })
        assertEquals(listOf(AutoMixEffect.ECHO, AutoMixEffect.REVERB), configured.map { it.evaluation.effect })
        assertEquals(null, PreserveRequestOrderPriorityPolicy.rank(AutoMixEffect.ECHO))
        assertEquals(1, ConfiguredEffectPriorityPolicy(mapOf(AutoMixEffect.REVERB to 1))
            .rank(AutoMixEffect.REVERB))
    }

    @Test
    fun compatibilityDescribesExistingFilterAndBandCoordination() {
        assertEquals(
            EffectCompatibility.MUTUALLY_EXCLUSIVE,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.FILTER_SWEEP, AutoMixEffect.RISE
            ).compatibility,
        )
        assertEquals(
            EffectCompatibility.COMPATIBLE,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.BASS_SWAP, AutoMixEffect.EQ_TRANSITION
            ).compatibility,
        )
        assertEquals(
            EffectCompatibility.NOT_SUPPORTED,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.VOCAL_SEPARATION, AutoMixEffect.ECHO
            ).compatibility,
        )
    }

    @Test
    fun gainOwnershipRejectsConflictingOwnersAndResetsBetweenGenerations() {
        val registry = GainOwnershipRegistry()
        registry.claim(AutoMixEffect.CROSSFADE, 1)
        assertNotNull(registry.owner(EffectGainParameter.OUTGOING_DECK_GAIN))
        assertThrows(IllegalStateException::class.java) {
            registry.claim(AutoMixEffect.LINEAR_FADE, 1)
        }
        registry.beginGeneration(2)
        assertNull(registry.owner(EffectGainParameter.OUTGOING_DECK_GAIN))
    }

    @Test
    fun compatibleTempoMatchingAndRampCanShareTheIncomingSpeedPipeline() {
        val registry = GainOwnershipRegistry()
        registry.claim(AutoMixEffect.BPM_MATCHING, 1)
        registry.claim(AutoMixEffect.TEMPO_RAMP, 1)
        assertEquals(2, registry.owners(EffectGainParameter.INCOMING_PLAYBACK_SPEED).size)
        assertEquals(
            EffectCompatibility.COMPATIBLE,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.BPM_MATCHING, AutoMixEffect.TEMPO_RAMP
            ).compatibility,
        )
    }

    @Test
    fun transitionIdentityRejectsStaleGeneration() {
        val identity = TransitionIdentity(TransitionGeneration.next(), "out", "in")
        val same = identity.copy()
        val stale = identity.copy(generation = identity.generation + 1)
        assertTrue(TransitionGeneration.isCurrent(identity, same))
        assertFalse(TransitionGeneration.isCurrent(identity, stale))
        assertFalse(TransitionGeneration.isCurrent(identity, null))
        assertTrue(NoAutomaticEffectFallback.fallbackFor(AutoMixEffect.ECHO, "test", 1)
            .fallbackEffect == null)
    }
}
