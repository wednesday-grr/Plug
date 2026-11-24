/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug

import com.siw.plug.logic.utils.automix.AutoMixEffect
import com.siw.plug.logic.utils.automix.AutoMixEffectArbitrator
import com.siw.plug.logic.utils.automix.AutoMixEffectCapabilityRegistry
import com.siw.plug.logic.utils.automix.AutoMixEffectCompatibilityEvaluator
import com.siw.plug.logic.utils.automix.ConfiguredEffectPriorityPolicy
import com.siw.plug.logic.utils.automix.EffectCompatibility
import com.siw.plug.logic.utils.automix.EffectGainParameter
import com.siw.plug.logic.utils.automix.EffectLifecycleState
import com.siw.plug.logic.utils.automix.GainOwnershipRegistry
import com.siw.plug.logic.utils.automix.NoAutomaticEffectFallback
import com.siw.plug.logic.utils.automix.PreserveRequestOrderPriorityPolicy
import com.siw.plug.logic.utils.automix.TransitionGeneration
import com.siw.plug.logic.utils.automix.TransitionIdentity
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
            listOf(AutoMixEffect.RISE, AutoMixEffect.FILTER_SWEEP),
            PreserveRequestOrderPriorityPolicy,
        )
        val configured = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.RISE, AutoMixEffect.FILTER_SWEEP),
            ConfiguredEffectPriorityPolicy(
                mapOf(AutoMixEffect.RISE to 1, AutoMixEffect.FILTER_SWEEP to 0)
            ),
        )
        assertEquals(
            listOf(AutoMixEffect.RISE, AutoMixEffect.FILTER_SWEEP),
            default.map { it.evaluation.effect },
        )
        assertEquals(
            listOf(AutoMixEffect.FILTER_SWEEP, AutoMixEffect.RISE),
            configured.map { it.evaluation.effect },
        )
        assertEquals(EffectLifecycleState.SELECTED, default.first().state)
        assertEquals(EffectLifecycleState.REJECTED, default.last().state)
        assertEquals(EffectLifecycleState.SELECTED, configured.first().state)
        assertEquals(EffectLifecycleState.REJECTED, configured.last().state)
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
        registry.releaseGeneration(2)
        assertThrows(IllegalStateException::class.java) {
            registry.claim(AutoMixEffect.CROSSFADE, 1)
        }
        assertThrows(IllegalStateException::class.java) {
            registry.claim(AutoMixEffect.CROSSFADE, 2)
        }
    }

    @Test
    fun unavailableEffectsCannotAcquireGainOwnershipAndCoordinatedRiseCanShareCrossfade() {
        val registry = GainOwnershipRegistry()
        assertThrows(IllegalStateException::class.java) {
            registry.claim(AutoMixEffect.VOCAL_SEPARATION, 1)
        }

        registry.claim(AutoMixEffect.CROSSFADE, 2)
        registry.claim(AutoMixEffect.RISE, 2)
        assertEquals(
            setOf(AutoMixEffect.CROSSFADE, AutoMixEffect.RISE),
            registry.owners(EffectGainParameter.INCOMING_DECK_GAIN).map { it.effect }.toSet(),
        )

        val vinylRegistry = GainOwnershipRegistry()
        vinylRegistry.claim(AutoMixEffect.VINYL_BRAKE, 1)
        assertEquals(
            AutoMixEffect.VINYL_BRAKE,
            vinylRegistry.owner(EffectGainParameter.INCOMING_DECK_GAIN)?.effect,
        )
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
    fun phaseTwoCompatibilityEncodesLoopAndTempoExclusions() {
        assertEquals(
            EffectCompatibility.MUTUALLY_EXCLUSIVE,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.BEAT_JUMP, AutoMixEffect.BEAT_LOOP
            ).compatibility,
        )
        assertEquals(
            EffectCompatibility.MUTUALLY_EXCLUSIVE,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.BEAT_LOOP, AutoMixEffect.LOOP_TRANSITION
            ).compatibility,
        )
        assertEquals(
            EffectCompatibility.COMPATIBLE,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.LOOP_TRANSITION, AutoMixEffect.CROSSFADE
            ).compatibility,
        )
        assertEquals(
            EffectCompatibility.MUTUALLY_EXCLUSIVE,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.BEATMATCH, AutoMixEffect.HALF_TIME_DOUBLE_TIME
            ).compatibility,
        )
        assertEquals(
            EffectCompatibility.CONDITIONALLY_COMPATIBLE,
            AutoMixEffectCompatibilityEvaluator.evaluate(
                AutoMixEffect.BEAT_JUMP, AutoMixEffect.CROSSFADE
            ).compatibility,
        )
    }

    @Test
    fun coordinatedLoopTransitionCanShareTheCrossfadeDeckGainEnvelope() {
        val registry = GainOwnershipRegistry()
        registry.claim(AutoMixEffect.CROSSFADE, 100)
        registry.claim(AutoMixEffect.LOOP_TRANSITION, 100)
        assertEquals(
            setOf(AutoMixEffect.CROSSFADE, AutoMixEffect.LOOP_TRANSITION),
            registry.owners(EffectGainParameter.OUTGOING_DECK_GAIN).map { it.effect }.toSet(),
        )
        assertNotNull(registry.owner(EffectGainParameter.OUTGOING_LOOP_REGION))
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
