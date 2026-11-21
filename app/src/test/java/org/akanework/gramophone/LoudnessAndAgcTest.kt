package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.automix.AgcConfig
import org.akanework.gramophone.logic.utils.automix.AutomaticGainController
import org.akanework.gramophone.logic.utils.automix.LoudnessMatchConfig
import org.akanework.gramophone.logic.utils.automix.LoudnessMatchReason
import org.akanework.gramophone.logic.utils.automix.LoudnessMatcher
import org.akanework.gramophone.logic.utils.automix.LoudnessMeter
import org.akanework.gramophone.logic.utils.automix.LoudnessSource
import org.akanework.gramophone.logic.utils.automix.MeasuredLoudness
import org.akanework.gramophone.logic.utils.automix.TrackLoudness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class LoudnessAndAgcTest {
    private val rate = 48_000

    private fun amplitudeForRmsDb(db: Double) = (10.0.pow(db / 20.0) * Math.sqrt(2.0)).toFloat()

    private fun stereo(mono: FloatArray) = FloatArray(mono.size * 2) { mono[it / 2] }

    private fun measured(lufs: Float?, seconds: Float = 120f) =
        MeasuredLoudness(lufs, null, -1f, seconds, 24_000f)

    // --- LoudnessMeter ---

    @Test
    fun meterMatchesBs1770ReferenceForFullScale997HzStereoSine() {
        val sine = DspTestSignals.sine(997.0, rate, rate * 10)
        val result = LoudnessMeter.measure(stereo(sine), rate, 2)!!
        // BS.1770: a 0 dBFS 997 Hz sine in both channels reads 0 LUFS.
        assertEquals(0.0, result.integratedLufs!!.toDouble(), 0.15)
        assertEquals(0.0, result.samplePeakDbfs!!.toDouble(), 0.01)
        assertTrue(!result.bandLimited)
    }

    @Test
    fun meterScalesOneToOneWithLevel() {
        val loud = LoudnessMeter.measure(stereo(DspTestSignals.sine(997.0, rate, rate * 8, 0.5f)), rate, 2)!!
        val quiet = LoudnessMeter.measure(stereo(DspTestSignals.sine(997.0, rate, rate * 8, 0.125f)), rate, 2)!!
        assertEquals(12.04, (loud.integratedLufs!! - quiet.integratedLufs!!).toDouble(), 0.05)
    }

    @Test
    fun meterReturnsNullInsteadOfZeroWhenUnmeasurable() {
        assertNull(LoudnessMeter.measure(FloatArray(100), rate, 1))
        assertNull(LoudnessMeter.measure(FloatArray(rate * 2), rate, 1)!!.integratedLufs)
        assertNull(LoudnessMeter.measure(FloatArray(rate * 2), rate, 6))
    }

    @Test
    fun meterGatesOutSilentPassages() {
        val tone = DspTestSignals.sine(997.0, rate, rate * 4, 0.5f)
        val mixed = tone + FloatArray(rate * 4) + tone
        val withSilence = LoudnessMeter.measure(mixed, rate, 1)!!.integratedLufs!!
        val toneOnly = LoudnessMeter.measure(tone + tone, rate, 1)!!.integratedLufs!!
        assertEquals(toneOnly.toDouble(), withSilence.toDouble(), 0.3)
    }

    @Test
    fun lowSampleRateMeasurementIsFlaggedBandLimited() {
        val result = LoudnessMeter.measure(DspTestSignals.sine(500.0, 11_025, 11_025 * 5), 11_025, 1)!!
        assertTrue(result.bandLimited)
    }

    // --- LoudnessMatcher ---

    @Test
    fun matcherAttenuatesOnlyTheLouderIncomingTrack() {
        val plan = LoudnessMatcher.plan(
            TrackLoudness(measured(-16f)), TrackLoudness(measured(-10f)),
        )
        assertEquals(1f, plan.outgoingGain, 0f)
        assertEquals(10.0.pow(-6.0 / 20.0), plan.incomingGain.toDouble(), 1e-4)
        assertEquals(LoudnessSource.MEASURED, plan.source)
    }

    @Test
    fun matcherAttenuatesLouderOutgoingTrackAndClampsToMaxCorrection() {
        val plan = LoudnessMatcher.plan(
            TrackLoudness(measured(-6f)), TrackLoudness(measured(-20f)),
            LoudnessMatchConfig(maxCorrectionDb = 6f),
        )
        assertEquals(1f, plan.incomingGain, 0f)
        assertEquals(6f, plan.appliedCorrectionDb, 1e-4f)
        assertEquals(LoudnessMatchReason.CLAMPED_TO_MAX_CORRECTION, plan.reason)
    }

    @Test
    fun matcherLeavesSmallDifferencesAlone() {
        val plan = LoudnessMatcher.plan(TrackLoudness(measured(-14f)), TrackLoudness(measured(-14.5f)))
        assertEquals(LoudnessMatchReason.WITHIN_DEADBAND, plan.reason)
        assertTrue(!plan.applied)
    }

    @Test
    fun matcherTreatsMissingLoudnessAsUnavailableNotZero() {
        val plan = LoudnessMatcher.plan(TrackLoudness(), TrackLoudness(measured(-10f)))
        assertEquals(LoudnessMatchReason.OUTGOING_UNAVAILABLE, plan.reason)
        assertNull(plan.differenceLu)
        assertEquals(1f, plan.incomingGain, 0f)
        val gated = LoudnessMatcher.plan(TrackLoudness(measured(null)), TrackLoudness(measured(-10f)))
        assertEquals(LoudnessMatchReason.OUTGOING_UNAVAILABLE, gated.reason)
    }

    @Test
    fun matcherRejectsTooShortMeasurementAndMixedSources() {
        val short = LoudnessMatcher.plan(TrackLoudness(measured(-14f, 3f)), TrackLoudness(measured(-8f)))
        assertEquals(LoudnessMatchReason.OUTGOING_UNAVAILABLE, short.reason)
        val mixed = LoudnessMatcher.plan(
            TrackLoudness(measured(-14f)), TrackLoudness(replayGainTagDb = -6f),
        )
        assertEquals(LoudnessMatchReason.SOURCES_NOT_COMPARABLE, mixed.reason)
    }

    @Test
    fun matcherUsesReplayGainMetadataWhenBothTracksOnlyHaveTags() {
        val plan = LoudnessMatcher.plan(
            TrackLoudness(replayGainTagDb = 0f), TrackLoudness(replayGainTagDb = -6f),
        )
        // Tags imply -18 and -12 LUFS: incoming is 6 LU louder.
        assertEquals(LoudnessSource.REPLAYGAIN_METADATA, plan.source)
        assertEquals(6f, plan.appliedCorrectionDb, 1e-4f)
    }

    @Test
    fun matcherDoesNotDoubleApplyReplayGainThatPlaybackAlreadyApplies() {
        // Incoming is 6 LU louder, but ReplayGain playback already lowers it by 6 dB: nothing left to match.
        val plan = LoudnessMatcher.plan(
            TrackLoudness(measured(-16f)), TrackLoudness(measured(-10f), replayGainAppliedDb = -6f),
        )
        assertEquals(LoudnessMatchReason.WITHIN_DEADBAND, plan.reason)
        assertTrue(!plan.applied)
    }

    @Test
    fun rampedGainIsMonotonicWithoutStepsAtEnds() {
        var previous = 1f
        for (i in 0..200) {
            val g = LoudnessMatcher.rampedGain(0.5f, i / 200f)
            assertTrue(g <= previous + 1e-6f)
            assertTrue(previous - g < 0.02f)
            previous = g
        }
        assertEquals(1f, LoudnessMatcher.rampedGain(0.5f, 0f), 0f)
        assertEquals(0.5f, LoudnessMatcher.rampedGain(0.5f, 1f), 1e-6f)
    }

    // --- AutomaticGainController ---

    private fun runAgc(
        config: AgcConfig, rmsDb: Double, seconds: Int, freq: Double = 220.0,
    ): Pair<FloatArray, AutomaticGainController> {
        val agc = AutomaticGainController(rate, 1, config)
        val signal = DspTestSignals.sine(freq, rate, rate * seconds, amplitudeForRmsDb(rmsDb))
        agc.process(signal)
        return signal to agc
    }

    @Test
    fun agcRaisesQuietSignalToTarget() {
        val (out, _) = runAgc(AgcConfig(targetDbfs = -20f, maxGainDb = 15f), -30.0, 10)
        val tail = DspTestSignals.rms(out, rate * 8, rate * 10)
        assertEquals(-20.0, DspTestSignals.dbfs(tail), 0.5)
    }

    @Test
    fun agcLowersLoudSignalToTargetWithinAttenuationLimit() {
        val (out, _) = runAgc(AgcConfig(targetDbfs = -20f, maxAttenuationDb = 15f), -12.0, 10)
        assertEquals(-20.0, DspTestSignals.dbfs(DspTestSignals.rms(out, rate * 8, rate * 10)), 0.5)
        val (limited, _) = runAgc(AgcConfig(targetDbfs = -20f, maxAttenuationDb = 3f), -12.0, 10)
        assertEquals(-15.0, DspTestSignals.dbfs(DspTestSignals.rms(limited, rate * 8, rate * 10)), 0.5)
    }

    @Test
    fun agcRespectsMaxGain() {
        val (out, agc) = runAgc(AgcConfig(targetDbfs = -20f, maxGainDb = 6f), -40.0, 10)
        assertEquals(6.0, agc.currentGainDb.toDouble(), 0.2)
        assertEquals(-34.0, DspTestSignals.dbfs(DspTestSignals.rms(out, rate * 8, rate * 10)), 0.5)
    }

    @Test
    fun agcDoesNotAmplifyNoiseFloor() {
        val (out, agc) = runAgc(AgcConfig(noiseThresholdDbfs = -55f, maxGainDb = 20f), -70.0, 10)
        assertEquals(0.0, agc.currentGainDb.toDouble(), 0.05)
        assertEquals(-70.0, DspTestSignals.dbfs(DspTestSignals.rms(out, rate * 8, rate * 10)), 0.3)
    }

    @Test
    fun agcSilenceStaysSilentAndFinite() {
        val agc = AutomaticGainController(rate, 2)
        val silence = FloatArray(rate * 2)
        agc.process(silence)
        assertTrue(silence.all { it == 0f })
        assertEquals(0f, agc.currentGainDb, 0f)
    }

    @Test
    fun agcAttackIsFasterThanRelease() {
        val config = AgcConfig(targetDbfs = -20f, attackMs = 20f, releaseMs = 1000f, maxGainDb = 12f, maxAttenuationDb = 12f)
        val agc = AutomaticGainController(rate, 1, config)
        val loud = DspTestSignals.sine(220.0, rate, rate, amplitudeForRmsDb(-12.0))
        agc.process(loud) // settles near -8 dB gain
        val settledGain = agc.currentGainDb
        assertTrue(settledGain < -6f)
        // Quiet input now needs gain to rise; after 100 ms it must still be far from its target (slow release).
        val quiet = DspTestSignals.sine(220.0, rate, rate / 10, amplitudeForRmsDb(-30.0))
        agc.process(quiet)
        assertTrue("release too fast: ${agc.currentGainDb}", agc.currentGainDb < settledGain + 3f)
    }

    @Test
    fun agcGainChangesSmoothlyWithoutLargeOutputSteps() {
        val agc = AutomaticGainController(rate, 1, AgcConfig(maxGainDb = 12f))
        val signal = DspTestSignals.sine(100.0, rate, rate * 4, amplitudeForRmsDb(-35.0))
        val reference = signal.copyOf()
        agc.process(signal)
        val maxRatioJump = (1 until signal.size).maxOf { i ->
            val dIn = reference[i] - reference[i - 1]
            val dOut = signal[i] - signal[i - 1]
            kotlin.math.abs(dOut - dIn * 1.0f).let { if (kotlin.math.abs(dIn) < 1e-4f) it else 0f }
        }
        assertTrue("gain step too large: $maxRatioJump", maxRatioJump < 0.001f)
    }

    @Test
    fun agcPeakProtectionNeverExceedsCeiling() {
        val agc = AutomaticGainController(rate, 1, AgcConfig(targetDbfs = -6f, maxGainDb = 24f, peakCeiling = 0.9f))
        val quiet = DspTestSignals.sine(220.0, rate, rate * 3, 0.05f)
        val burst = DspTestSignals.sine(220.0, rate, rate / 2, 0.95f)
        val signal = quiet + burst
        agc.process(signal)
        assertTrue(DspTestSignals.peak(signal) <= 0.9f + 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun agcRejectsNoiseThresholdAboveTarget() {
        AgcConfig(targetDbfs = -30f, noiseThresholdDbfs = -20f)
    }
}
