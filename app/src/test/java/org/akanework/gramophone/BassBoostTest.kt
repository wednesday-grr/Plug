package org.akanework.gramophone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.akanework.gramophone.logic.utils.automix.AutoMixEffect
import org.akanework.gramophone.logic.utils.automix.AutoMixEffectCompatibilityEvaluator
import org.akanework.gramophone.logic.utils.automix.BassBoostAudioProcessor
import org.akanework.gramophone.logic.utils.automix.BassBoostConfig
import org.akanework.gramophone.logic.utils.automix.BassBoostFilter
import org.akanework.gramophone.logic.utils.automix.BassBoostSettings
import org.akanework.gramophone.logic.utils.automix.EffectCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BassBoostTest {
    private val rate = 48_000

    private fun boosted(freq: Double, config: BassBoostConfig, amp: Float = 0.1f, mix: Float = 1f): Pair<FloatArray, FloatArray> {
        val input = DspTestSignals.sine(freq, rate, rate * 2, amp)
        val out = input.copyOf()
        val f = BassBoostFilter(rate, 1, config)
        f.setTargetMix(mix)
        f.process(out, 0, out.size)
        return input to out
    }

    private fun gainDb(freq: Double, config: BassBoostConfig, amp: Float = 0.1f): Double {
        val (i, o) = boosted(freq, config, amp)
        val from = rate
        return DspTestSignals.dbfs(DspTestSignals.rms(o, from, o.size)) -
            DspTestSignals.dbfs(DspTestSignals.rms(i, from, i.size))
    }

    @Test
    fun lowFrequenciesAreBoostedByConfiguredGainAndHighsAreUntouched() {
        val config = BassBoostConfig(frequencyHz = 100f, maxGainDb = 6f)
        assertEquals(6.0, gainDb(30.0, config), 0.5)
        assertEquals(0.0, gainDb(5_000.0, config), 0.2)
        // Half-gain at the corner frequency for a shelf.
        assertEquals(3.0, gainDb(100.0, config), 0.6)
    }

    @Test
    fun analyticResponseMatchesMeasuredResponse() {
        val config = BassBoostConfig(frequencyHz = 120f, maxGainDb = 8f)
        val f = BassBoostFilter(rate, 1, config)
        f.setTargetMix(1f)
        for (freq in listOf(40.0, 120.0, 400.0, 3000.0)) {
            val analytic = DspTestSignals.dbfs(f.magnitudeAt(freq, 1.0))
            assertEquals("at $freq", analytic, gainDb(freq, config), 0.3)
        }
    }

    @Test
    fun zeroMixIsExactPassThroughAndIdle() {
        val (i, o) = boosted(60.0, BassBoostConfig(), mix = 0f)
        assertTrue(i.contentEquals(o))
        assertTrue(BassBoostFilter(rate, 1).isIdle)
    }

    @Test
    fun mixAutomationIsSmoothWithNoGainStepsOrClicks() {
        val f = BassBoostFilter(rate, 1, BassBoostConfig(maxGainDb = 9f))
        val signal = DspTestSignals.sine(60.0, rate, rate, 0.2f)
        val out = signal.copyOf()
        f.setTargetMix(1f)
        f.process(out, 0, out.size / 2)
        f.setTargetMix(0f)
        f.process(out, out.size / 2, out.size - out.size / 2)
        val diff = FloatArray(out.size) { out[it] - signal[it] }
        // The wet/dry difference may not jump: bounded per-sample change.
        assertTrue(DspTestSignals.maxStep(diff) < 0.02f)
        assertTrue(f.currentMix < 0.01f)
    }

    @Test
    fun limiterPreventsClippingOnLoudBass() {
        val config = BassBoostConfig(frequencyHz = 100f, maxGainDb = 12f, ceiling = 0.98f)
        val (_, out) = boosted(50.0, config, amp = 0.9f)
        assertTrue("peak ${DspTestSignals.peak(out)}", DspTestSignals.peak(out) <= 0.98f + 1e-4f)
    }

    @Test
    fun limiterIsTransparentForQuietMaterial() {
        val config = BassBoostConfig(maxGainDb = 6f)
        assertEquals(6.0, gainDb(40.0, config, amp = 0.05f), 0.5)
    }

    @Test
    fun stereoChannelsAreProcessedIndependently() {
        val f = BassBoostFilter(rate, 2)
        f.setTargetMix(1f)
        val mono = DspTestSignals.sine(50.0, rate, rate, 0.1f)
        val interleaved = FloatArray(mono.size * 2) { if (it % 2 == 0) mono[it / 2] else 0f }
        f.process(interleaved, 0, interleaved.size)
        val right = FloatArray(mono.size) { interleaved[it * 2 + 1] }
        assertEquals(0f, DspTestSignals.peak(right), 0f)
        val left = FloatArray(mono.size) { interleaved[it * 2] }
        assertTrue(DspTestSignals.peak(left) > 0.1f)
    }

    @Test
    fun shareWeightedOverlapDoesNotStackBassBeyondSingleTrackBoost() {
        val config = BassBoostConfig(maxGainDb = 9f)
        val a = DspTestSignals.sine(60.0, rate, rate, 0.1f)
        val fa = BassBoostFilter(rate, 1, config).also { it.setTargetMix(0.5f) }
        val fb = BassBoostFilter(rate, 1, config).also { it.setTargetMix(0.5f) }
        val oa = a.copyOf().also { fa.process(it, 0, it.size) }
        val ob = a.copyOf().also { fb.process(it, 0, it.size) }
        // Each deck at half the original level, as in a 50/50 linear overlap.
        val sum = FloatArray(a.size) { 0.5f * oa[it] + 0.5f * ob[it] }
        val single = BassBoostFilter(rate, 1, config).also { it.setTargetMix(1f) }
        val reference = a.copyOf().also { single.process(it, 0, it.size) }
        val from = rate / 2
        val sumDb = DspTestSignals.dbfs(DspTestSignals.rms(sum, from, sum.size))
        val refDb = DspTestSignals.dbfs(DspTestSignals.rms(reference, from, reference.size))
        assertTrue("overlap $sumDb vs single $refDb", sumDb <= refDb)
    }

    @Test
    fun settingsValidateRanges() {
        assertThrows(IllegalArgumentException::class.java) { BassBoostSettings(true, 13, 100) }
        assertThrows(IllegalArgumentException::class.java) { BassBoostSettings(true, 4, 30) }
        val c = BassBoostSettings.coerced(true, 99, 5)
        assertEquals(12, c.gainDb)
        assertEquals(40, c.frequencyHz)
    }

    @Test
    fun processorBoostsPcmFloatAndBypassesWhenMixIsZero() {
        val p = BassBoostAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val signal = DspTestSignals.sine(50.0, rate, rate * 2, 0.1f)
        fun run(sig: FloatArray): FloatArray {
            val buf = ByteBuffer.allocateDirect(sig.size * 4).order(ByteOrder.nativeOrder())
            sig.forEach(buf::putFloat)
            buf.flip()
            p.queueInput(buf)
            val out = p.output.order(ByteOrder.nativeOrder())
            return FloatArray(out.remaining() / 4) { out.float }
        }
        assertTrue(signal.contentEquals(run(signal)))
        p.configureBoost(6, 100)
        p.setMix(1f)
        val out = run(signal)
        val gain = DspTestSignals.dbfs(DspTestSignals.rms(out, rate, out.size)) -
            DspTestSignals.dbfs(DspTestSignals.rms(signal, rate, signal.size))
        assertEquals(6.0, gain, 0.6)
        p.setMix(0f)
        run(signal)
        assertTrue(signal.contentEquals(run(signal)))
    }

    @Test
    fun arbitrationTreatsBassBoostAsConditionallyCompatibleWithSwapAndLoudness() {
        fun compat(b: AutoMixEffect) =
            AutoMixEffectCompatibilityEvaluator.evaluate(AutoMixEffect.BASS_BOOST, b).compatibility
        assertEquals(EffectCompatibility.CONDITIONALLY_COMPATIBLE, compat(AutoMixEffect.BASS_SWAP))
        assertEquals(EffectCompatibility.CONDITIONALLY_COMPATIBLE, compat(AutoMixEffect.LOUDNESS_MATCHING))
        assertEquals(EffectCompatibility.COMPATIBLE, compat(AutoMixEffect.CROSSFADE))
    }
}
