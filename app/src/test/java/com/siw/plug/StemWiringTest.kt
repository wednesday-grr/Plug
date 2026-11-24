package com.siw.plug

import com.siw.plug.logic.utils.automix.AutoMixEffect
import com.siw.plug.logic.utils.automix.AutoMixEffectArbitrator
import com.siw.plug.logic.utils.automix.AutoMixEffectCapabilityRegistry
import com.siw.plug.logic.utils.automix.AutoMixEffectCompatibilityEvaluator
import com.siw.plug.logic.utils.automix.EffectCompatibility
import com.siw.plug.logic.utils.automix.EffectLifecycleState
import com.siw.plug.logic.utils.automix.stemTransitionEffects
import com.siw.plug.logic.utils.automix.stems.StemEffect
import com.siw.plug.logic.utils.automix.stems.StemHandOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StemWiringTest {
    private val edge = 13_230L

    @Test
    fun handOverIsUnavailableBeforeAudibleOutput() {
        assertNull(StemHandOver.deckGains(0, 100_000, edge))
        assertNull(StemHandOver.deckGains(10, 0, edge))
    }

    @Test
    fun handOverSilencesDecksInTheMiddle() {
        val mid = StemHandOver.deckGains(50_000, 100_000, edge)!!
        assertEquals(0f, mid.outgoing, 0f)
        assertEquals(0f, mid.incoming, 0f)
    }

    @Test
    fun handOverRampsDecksAtTheEdges() {
        val start = StemHandOver.deckGains(edge / 2, 100_000, edge)!!
        assertEquals(0.5f, start.outgoing, 0.01f)
        assertEquals(0f, start.incoming, 0f)
        val end = StemHandOver.deckGains(100_000 - edge / 2, 100_000, edge)!!
        assertEquals(0f, end.outgoing, 0f)
        assertEquals(0.5f, end.incoming, 0.01f)
        assertEquals(1f, StemHandOver.deckGains(100_000, 100_000, edge)!!.incoming, 0f)
    }

    @Test
    fun handOverIsPowerNeutralAtEdges() {
        // Deck and stem gains sum to 1 over the lead-in so the level is continuous (linear hand-over).
        for (played in 1L..edge step 997L) {
            val deck = StemHandOver.deckGains(played, 100_000, edge)!!.outgoing
            val stem = (played.toFloat() / edge).coerceIn(0f, 1f)
            assertEquals(1f, deck + stem, 1e-5f)
        }
    }

    @Test
    fun stemTransitionsAreRuntimeAvailableWithFourSources() {
        for (effect in stemTransitionEffects) {
            val capability = AutoMixEffectCapabilityRegistry.capability(effect)
            assertTrue(effect.id, capability.runtimeAvailable)
            assertEquals(4, capability.requiredStemSources)
            assertEquals(4, capability.requiredAudioSources)
        }
        assertFalse(AutoMixEffectCapabilityRegistry.capability(AutoMixEffect.VOCAL_SEPARATION).runtimeAvailable)
        assertFalse(AutoMixEffectCapabilityRegistry.capability(AutoMixEffect.INDEPENDENT_STEM_GAIN).runtimeAvailable)
    }

    @Test
    fun stemEffectIdsMatchRegistry() {
        for (effect in StemEffect.entries) {
            assertNotNull(effect.id, AutoMixEffect.fromId(effect.id))
            assertTrue(AutoMixEffect.fromId(effect.id) in stemTransitionEffects)
        }
    }

    @Test
    fun onlyOneStemTransitionCanBeSelected() {
        val results = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.INSTRUMENTAL_DROP, AutoMixEffect.VOCAL_TO_INSTRUMENTAL)
        )
        assertEquals(EffectLifecycleState.SELECTED, results[0].state)
        assertEquals(EffectLifecycleState.REJECTED, results[1].state)
    }

    @Test
    fun stemsConflictWithDeckProcessorsButNotLoudnessMatching() {
        val effect = AutoMixEffect.INSTRUMENTAL_FADE
        for (other in listOf(
            AutoMixEffect.BASS_BOOST, AutoMixEffect.AUTOMATIC_GAIN_CONTROL, AutoMixEffect.VINYL_BRAKE,
            AutoMixEffect.ECHO, AutoMixEffect.BASS_SWAP, AutoMixEffect.LOOP_TRANSITION, AutoMixEffect.BEAT_LOOP,
            AutoMixEffect.TREMOLO_FADE,
        )) {
            assertEquals(other.id, EffectCompatibility.MUTUALLY_EXCLUSIVE,
                AutoMixEffectCompatibilityEvaluator.evaluate(effect, other).compatibility)
            assertEquals(other.id, EffectCompatibility.MUTUALLY_EXCLUSIVE,
                AutoMixEffectCompatibilityEvaluator.evaluate(other, effect).compatibility)
        }
        for (other in listOf(AutoMixEffect.LOUDNESS_MATCHING, AutoMixEffect.REPLAY_GAIN, AutoMixEffect.ENERGY_MATCHING)) {
            assertEquals(EffectCompatibility.COMPATIBLE,
                AutoMixEffectCompatibilityEvaluator.evaluate(effect, other).compatibility)
        }
        assertEquals(EffectCompatibility.CONDITIONALLY_COMPATIBLE,
            AutoMixEffectCompatibilityEvaluator.evaluate(effect, AutoMixEffect.CROSSFADE).compatibility)
    }

    @Test
    fun stemAutomixPlannerPairsWithEveryStemTransition() {
        for (effect in stemTransitionEffects) {
            assertEquals(EffectCompatibility.COMPATIBLE,
                AutoMixEffectCompatibilityEvaluator.evaluate(AutoMixEffect.STEM_AUTOMIX, effect).compatibility)
        }
    }

    @Test
    fun stemTransitionsDefaultToOff() {
        assertFalse(com.siw.plug.logic.utils.automix.AutomixSettings(true, true, 8).stemTransitionsEnabled)
    }
}
