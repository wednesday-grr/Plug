package com.siw.plug

import android.app.Application
import android.content.Context
import com.siw.plug.logic.utils.automix.AutoMixEffect
import com.siw.plug.logic.utils.automix.AutoMixEffectArbitrator
import com.siw.plug.logic.utils.automix.AutoMixEffectCapabilityRegistry
import com.siw.plug.logic.utils.automix.AutoMixEffectCompatibilityEvaluator
import com.siw.plug.logic.utils.automix.AutomixSettingsStore
import com.siw.plug.logic.utils.automix.ConfiguredEffectPriorityPolicy
import com.siw.plug.logic.utils.automix.EffectCompatibility
import com.siw.plug.logic.utils.automix.EffectLifecycleState
import com.siw.plug.logic.utils.automix.stemTransitionEffects
import com.siw.plug.logic.utils.automix.stems.StemAvailability
import com.siw.plug.logic.utils.automix.stems.StemEntry
import com.siw.plug.logic.utils.automix.stems.StemFailure
import com.siw.plug.logic.utils.automix.stems.StemKey
import com.siw.plug.logic.utils.automix.stems.StemStatus
import com.siw.plug.ui.components.stemAvailabilityLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class PhaseHTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("phase-h", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun stemTransitionsPersistAndDefaultOff() {
        val p = prefs()
        val store = AutomixSettingsStore(p)
        assertFalse(store.settings.value.stemTransitionsEnabled)
        p.edit().putBoolean("automix_stem_transitions", true).commit()
        store.onPreferenceChanged("automix_stem_transitions")
        assertTrue(store.settings.value.stemTransitionsEnabled)
        assertTrue(AutomixSettingsStore(p).settings.value.stemTransitionsEnabled)
    }

    @Test
    fun enablingStemsDoesNotChangeOtherSettings() {
        val p = prefs()
        val before = AutomixSettingsStore(p).settings.value
        p.edit().putBoolean("automix_stem_transitions", true).commit()
        assertEquals(before.copy(stemTransitionsEnabled = true), AutomixSettingsStore(p).settings.value)
    }

    @Test
    fun stemStatusMapsToDisplayAvailability() {
        val entry = StemEntry(StemKey("t", "h", "m", "v"), File("x"), 1, 44100, 2, "a", "b", 1, 1, 1)
        assertEquals(StemAvailability.READY, StemAvailability.of(StemStatus.Ready(entry)))
        assertEquals(StemAvailability.PROCESSING, StemAvailability.of(StemStatus.Queued))
        assertEquals(StemAvailability.PROCESSING, StemAvailability.of(StemStatus.Processing(10)))
        assertEquals(StemAvailability.FAILED, StemAvailability.of(StemStatus.Failed(StemFailure.MODEL_MISSING, null)))
        assertEquals(StemAvailability.NOT_CACHED, StemAvailability.of(StemStatus.NotRequested))
        assertEquals(StemAvailability.NOT_CACHED, StemAvailability.of(StemStatus.Cancelled))
        assertEquals(StemAvailability.NOT_CACHED, StemAvailability.of(StemStatus.Invalid("bad")))
    }

    @Test
    fun everyAvailabilityHasItsOwnLabel() {
        val labels = StemAvailability.entries.map(::stemAvailabilityLabelRes)
        assertEquals(StemAvailability.entries.size, labels.toSet().size)
    }

    @Test
    fun compatibilityMatrixIsTotalSymmetricAndDeterministic() {
        for (a in AutoMixEffect.entries) for (b in AutoMixEffect.entries) {
            val ab = AutoMixEffectCompatibilityEvaluator.evaluate(a, b)
            val ba = AutoMixEffectCompatibilityEvaluator.evaluate(b, a)
            assertEquals("$a/$b not symmetric", ab.compatibility, ba.compatibility)
            assertEquals(ab, AutoMixEffectCompatibilityEvaluator.evaluate(a, b))
            if (!AutoMixEffectCapabilityRegistry.capability(a).runtimeAvailable ||
                !AutoMixEffectCapabilityRegistry.capability(b).runtimeAvailable
            ) assertEquals("$a/$b", EffectCompatibility.NOT_SUPPORTED, ab.compatibility)
        }
    }

    @Test
    fun stemEffectsExcludeEachOtherAndNeedFourSources() {
        for (a in stemTransitionEffects) {
            assertEquals(4, AutoMixEffectCapabilityRegistry.capability(a).requiredStemSources)
            for (b in stemTransitionEffects - a) {
                assertEquals(EffectCompatibility.MUTUALLY_EXCLUSIVE,
                    AutoMixEffectCompatibilityEvaluator.evaluate(a, b).compatibility)
            }
        }
    }

    @Test
    fun fallbackCrossfadeSurvivesWhenStemEffectRejected() {
        val results = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.CROSSFADE, AutoMixEffect.INSTRUMENTAL_DROP, AutoMixEffect.VOCAL_TO_INSTRUMENTAL))
        val crossfade = results.first { it.evaluation.effect == AutoMixEffect.CROSSFADE }
        assertNotEquals(EffectLifecycleState.REJECTED, crossfade.state)
        assertNotEquals(EffectLifecycleState.UNAVAILABLE, crossfade.state)
        val stemSelected = results.filter {
            it.evaluation.effect in stemTransitionEffects && it.state != EffectLifecycleState.REJECTED
        }
        assertEquals(1, stemSelected.size)
    }

    @Test
    fun configuredPriorityMakesOutcomeIndependentOfRequestOrder() {
        val policy = ConfiguredEffectPriorityPolicy(mapOf(
            AutoMixEffect.INSTRUMENTAL_FADE to 1, AutoMixEffect.INSTRUMENTAL_DROP to 2))
        val one = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.INSTRUMENTAL_DROP, AutoMixEffect.INSTRUMENTAL_FADE), policy)
        val two = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.INSTRUMENTAL_FADE, AutoMixEffect.INSTRUMENTAL_DROP), policy)
        fun winners(r: List<com.siw.plug.logic.utils.automix.EffectSelectionResult>) =
            r.filter { it.state != EffectLifecycleState.REJECTED }.map { it.evaluation.effect }
        assertEquals(listOf(AutoMixEffect.INSTRUMENTAL_FADE), winners(one))
        assertEquals(winners(one), winners(two))
    }

    @Test
    fun unavailableEffectsAreNeverReportedAsAvailable() {
        for (effect in listOf(AutoMixEffect.VOCAL_SEPARATION, AutoMixEffect.INSTRUMENTAL_SEPARATION,
            AutoMixEffect.INDEPENDENT_STEM_GAIN)) {
            assertFalse(AutoMixEffectCapabilityRegistry.capability(effect).runtimeAvailable)
        }
    }
}
