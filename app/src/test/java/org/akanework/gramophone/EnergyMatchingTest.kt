package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.automix.EnergyAnalyzer
import org.akanework.gramophone.logic.utils.automix.EnergyMatchConfig
import org.akanework.gramophone.logic.utils.automix.EnergyMatchReason
import org.akanework.gramophone.logic.utils.automix.EnergyMatcher
import org.akanework.gramophone.logic.utils.automix.EnergyProfile
import org.akanework.gramophone.logic.utils.automix.TrackEnergy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyMatchingTest {
    private val rate = 22_050

    private fun profile(freq: Double, amp: Double, seconds: Int = 20, startMs: Long = 0): EnergyProfile {
        val sig = DspTestSignals.sine(freq, rate, rate * seconds, amp.toFloat())
        return EnergyAnalyzer.analyze(sig, rate, startMs)!!
    }

    @Test
    fun rmsLevelMatchesAnalyticValue() {
        val p = profile(1000.0, 0.5)
        val s = p.summarize(2000, 15000)!!
        // Sine RMS = amp/sqrt2 -> -9.03 dBFS
        assertEquals(-9.03f, s.rmsDb!!, 0.3f)
        assertEquals(1f, s.coverage, 0f)
    }

    @Test
    fun bandsSeparateByFrequency() {
        val low = profile(80.0, 0.5).summarize(2000, 15000)!!
        val mid = profile(1000.0, 0.5).summarize(2000, 15000)!!
        val high = profile(8000.0, 0.5).summarize(2000, 15000)!!
        assertTrue(low.lowDb!! > low.midDb!! + 15 && low.lowDb!! > low.highDb!! + 30)
        assertTrue(mid.midDb!! > mid.lowDb!! + 15 && mid.midDb!! > mid.highDb!! + 15)
        assertTrue(high.highDb!! > high.midDb!! + 15 && high.highDb!! > high.lowDb!! + 30)
    }

    @Test
    fun silenceIsExcludedNotZero() {
        val sig = FloatArray(rate * 20)
        val loud = DspTestSignals.sine(1000.0, rate, rate * 5, 0.5f)
        System.arraycopy(loud, 0, sig, 0, loud.size)
        val p = EnergyAnalyzer.analyze(sig, rate, 0)!!
        val s = p.summarize(0, 20000)!!
        assertTrue(s.coverage < 0.4f)
        assertEquals(-9.03f, s.rmsDb!!, 0.5f)
        val allSilent = EnergyAnalyzer.analyze(FloatArray(rate * 5), rate, 0)!!.summarize(0, 5000)!!
        assertNull(allSilent.rmsDb)
    }

    @Test
    fun trendReflectsRisingLevel() {
        val sig = DspTestSignals.sine(1000.0, rate, rate * 20, 1.0f)
        for (i in sig.indices) sig[i] *= (0.05f + 0.95f * i / sig.size)
        val s = EnergyAnalyzer.analyze(sig, rate, 0)!!.summarize(0, 20000)!!
        assertTrue(s.trendDbPerSecond!! > 0.5f)
    }

    @Test
    fun tooShortWindowIsUnavailable() {
        assertNull(EnergyAnalyzer.analyze(FloatArray(rate / 2), rate, 0))
    }

    @Test
    fun serialisationRoundTripsAndRejectsCorruption() {
        val p = profile(500.0, 0.3, 5)
        val back = EnergyProfile.fromBytes(p.toBytes())!!
        assertEquals(p.frameCount, back.frameCount)
        assertEquals(p.rmsDb[3], back.rmsDb[3], 0f)
        assertEquals(p.startMs, back.startMs)
        assertNull(EnergyProfile.fromBytes(p.toBytes().copyOf(20)))
        assertNull(EnergyProfile.fromBytes(ByteArray(3)))
    }

    private fun track(amp: Double): TrackEnergy {
        val p = profile(1000.0, amp, 20)
        return TrackEnergy(p, p, 200_000)
    }

    @Test
    fun largeRiseExtendsTransition() {
        val plan = EnergyMatcher.plan(track(0.02), track(0.5))
        assertEquals(EnergyMatchReason.EXTENDED_FOR_RISE, plan.reason)
        assertTrue(plan.durationScale > 1f && plan.durationScale <= 1.5f)
        assertTrue(plan.differenceDb!! > 20f)
    }

    @Test
    fun smallDifferenceLeavesTransitionAlone() {
        val plan = EnergyMatcher.plan(track(0.3), track(0.35))
        assertEquals(EnergyMatchReason.WITHIN_THRESHOLD, plan.reason)
        assertEquals(1f, plan.durationScale, 0f)
        assertNotNull(plan.differenceDb)
    }

    @Test
    fun dropToleratesMoreThanRise() {
        // ~7.5 dB difference: over the 6 dB rise threshold, under the 9 dB drop threshold.
        val rise = EnergyMatcher.plan(track(0.1), track(0.1 * 2.37))
        val drop = EnergyMatcher.plan(track(0.1 * 2.37), track(0.1))
        assertTrue(rise.applied)
        assertTrue(!drop.applied)
    }

    @Test
    fun missingOrLowConfidenceDataIsUnavailable() {
        assertEquals(EnergyMatchReason.OUTGOING_UNAVAILABLE, EnergyMatcher.plan(null as TrackEnergy?, track(0.3)).reason)
        assertEquals(EnergyMatchReason.INCOMING_UNAVAILABLE,
            EnergyMatcher.plan(track(0.3), TrackEnergy(null, null, 1000)).reason)
        val mostlySilent = FloatArray(rate * 20)
        System.arraycopy(DspTestSignals.sine(1000.0, rate, rate * 3, 0.5f), 0, mostlySilent, 0, rate * 3)
        val sparse = EnergyAnalyzer.analyze(mostlySilent, rate, 0)!!
        val plan = EnergyMatcher.plan(track(0.3), TrackEnergy(sparse, sparse, 1000))
        assertEquals(EnergyMatchReason.LOW_CONFIDENCE, plan.reason)
        assertNull(plan.differenceDb)
        assertEquals(1f, plan.durationScale, 0f)
    }

    @Test
    fun configIsValidated() {
        runCatching { EnergyMatchConfig(maxDurationScale = 0.5f) }.let { assertTrue(it.isFailure) }
    }
}
