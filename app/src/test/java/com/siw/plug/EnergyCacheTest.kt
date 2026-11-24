package com.siw.plug

import android.app.Application
import com.siw.plug.logic.utils.automix.AnalysisKey
import com.siw.plug.logic.utils.automix.AutoMixEffect
import com.siw.plug.logic.utils.automix.AutoMixEffectCapabilityRegistry
import com.siw.plug.logic.utils.automix.EffectGainParameter
import com.siw.plug.logic.utils.automix.EnergyAnalyzer
import com.siw.plug.logic.utils.automix.TrackAnalysisCache
import com.siw.plug.logic.utils.automix.TrackEnergy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EnergyCacheTest {
    private val rate = 22_050

    @Test
    fun missingRowIsUnavailableAndStoredRowRoundTripsAcrossInstances() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("energy-cache", 5, "content://media/energy")
        TrackAnalysisCache(context).let { cache ->
            assertNull(cache.loadEnergy(key))
            val profile = EnergyAnalyzer.analyze(DspTestSignals.sine(440.0, rate, rate * 5, 0.4f), rate, 0)
            cache.storeEnergy(key, TrackEnergy(profile, null, 123_000))
        }
        TrackAnalysisCache(context).let { reopened ->
            val loaded = reopened.loadEnergy(key)
            assertNotNull(loaded)
            assertEquals(123_000, loaded!!.durationMs)
            assertNull("a window that could not be analysed stays null, not zero", loaded.end)
            assertEquals(EnergyAnalyzer.analyze(DspTestSignals.sine(440.0, rate, rate * 5, 0.4f), rate, 0)!!.rmsDb[2],
                loaded.start!!.rmsDb[2], 0f)
            // A modified file invalidates the old row.
            assertNull(reopened.loadEnergy(key.copy(modified = 6)))
        }
    }

    @Test
    fun energyMatchingOnlyClaimsTransitionDuration() {
        val capability = AutoMixEffectCapabilityRegistry.capability(AutoMixEffect.ENERGY_MATCHING)
        assertTrue(capability.runtimeAvailable)
        assertEquals(setOf(EffectGainParameter.TRANSITION_DURATION), capability.gainParameters)
    }
}
