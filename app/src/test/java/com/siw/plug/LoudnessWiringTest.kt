package com.siw.plug

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.siw.plug.logic.utils.automix.AgcAudioProcessor
import com.siw.plug.logic.utils.automix.AnalysisKey
import com.siw.plug.logic.utils.automix.AutoMixEffect
import com.siw.plug.logic.utils.automix.AutoMixEffectCompatibilityEvaluator
import com.siw.plug.logic.utils.automix.EffectCompatibility
import com.siw.plug.logic.utils.automix.EffectGainParameter
import com.siw.plug.logic.utils.automix.GainOwnershipRegistry
import com.siw.plug.logic.utils.automix.LoudnessMatcher
import com.siw.plug.logic.utils.automix.MeasuredLoudness
import com.siw.plug.logic.utils.automix.TrackAnalysisCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class LoudnessWiringTest {
    private val rate = 48_000

    @Test
    fun outgoingTrimReachesPlanByMidTransitionAndIncomingIsRestoredByTheEnd() {
        val plan = 0.5f
        assertEquals(1f, LoudnessMatcher.outgoingTrim(plan, 0f), 1e-6f)
        assertEquals(plan, LoudnessMatcher.outgoingTrim(plan, 0.5f), 1e-6f)
        assertEquals(plan, LoudnessMatcher.outgoingTrim(plan, 1f), 1e-6f)
        assertEquals(plan, LoudnessMatcher.incomingTrim(plan, 0f), 1e-6f)
        assertEquals(plan, LoudnessMatcher.incomingTrim(plan, 0.5f), 1e-6f)
        assertEquals(1f, LoudnessMatcher.incomingTrim(plan, 1f), 1e-6f)
        var previous = LoudnessMatcher.incomingTrim(plan, 0.5f)
        for (i in 51..100) {
            val value = LoudnessMatcher.incomingTrim(plan, i / 100f)
            assertTrue(value >= previous)
            previous = value
        }
    }

    @Test
    fun loudnessMatchingUsesSeparateGainParametersAndCoexistsWithFadesAndTempo() {
        val registry = GainOwnershipRegistry()
        registry.claim(AutoMixEffect.S_CURVE_FADE, 1)
        registry.claim(AutoMixEffect.LOUDNESS_MATCHING, 1)
        assertEquals(
            AutoMixEffect.LOUDNESS_MATCHING, registry.owner(EffectGainParameter.INCOMING_LEVEL_TRIM)?.effect
        )
        assertEquals(
            AutoMixEffect.S_CURVE_FADE, registry.owner(EffectGainParameter.INCOMING_DECK_GAIN)?.effect
        )
    }

    @Test
    fun replayGainInteractionIsConditionalButAgcStillConflicts() {
        fun compat(a: AutoMixEffect, b: AutoMixEffect) =
            AutoMixEffectCompatibilityEvaluator.evaluate(a, b).compatibility
        assertEquals(EffectCompatibility.CONDITIONALLY_COMPATIBLE,
            compat(AutoMixEffect.LOUDNESS_MATCHING, AutoMixEffect.REPLAY_GAIN))
        assertEquals(EffectCompatibility.CONFLICTING,
            compat(AutoMixEffect.AUTOMATIC_GAIN_CONTROL, AutoMixEffect.REPLAY_GAIN))
        assertEquals(EffectCompatibility.CONFLICTING,
            compat(AutoMixEffect.AUTOMATIC_GAIN_CONTROL, AutoMixEffect.LOUDNESS_MATCHING))
    }

    private fun run(p: AgcAudioProcessor, signal: FloatArray): FloatArray {
        val input = ByteBuffer.allocateDirect(signal.size * 4).order(ByteOrder.nativeOrder())
        signal.forEach(input::putFloat)
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder())
        return FloatArray(out.remaining() / 4) { out.float }
    }

    private fun agc(): AgcAudioProcessor = AgcAudioProcessor().also {
        it.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT))
        it.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    @Test
    fun disabledAgcIsBitExactPassThrough() {
        val signal = DspTestSignals.sine(440.0, rate, rate, 0.05f)
        val out = run(agc(), signal)
        assertTrue(signal.contentEquals(out))
    }

    @Test
    fun enabledAgcRaisesQuietMaterialTowardTargetWithoutExceedingTheCeiling() {
        val p = agc()
        p.setEnabled(true)
        val signal = DspTestSignals.sine(440.0, rate, rate * 10, 0.03f)
        val out = FloatArray(signal.size)
        var offset = 0
        while (offset < signal.size) {
            val chunk = run(p, signal.copyOfRange(offset, minOf(signal.size, offset + 4800)))
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        val before = DspTestSignals.dbfs(DspTestSignals.rms(signal, rate * 8, rate * 10))
        val after = DspTestSignals.dbfs(DspTestSignals.rms(out, rate * 8, rate * 10))
        assertTrue("expected gain, got ${after - before} dB", after - before > 3.0)
        assertTrue(out.all { kotlin.math.abs(it) <= 0.98f + 1e-4f })
        assertNotNull(p.currentGainDb)
    }

    @Test
    fun flushResetsAgcState() {
        val p = agc()
        p.setEnabled(true)
        run(p, DspTestSignals.sine(440.0, rate, rate * 3, 0.03f))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertNull(p.currentGainDb)
    }

    @Test
    fun loudnessCacheDistinguishesMissingFromMeasuredButGatedOut() {
        val cache = TrackAnalysisCache(RuntimeEnvironment.getApplication())
        val key = AnalysisKey("id-1", 100L, "content://media/1")
        assertNull(cache.loadLoudness(key))
        val measured = MeasuredLoudness(-14.2f, -11.0f, -0.5f, 60f, 22_050f)
        cache.storeLoudness(key, measured)
        assertEquals(measured, cache.loadLoudness(key))
        val gated = AnalysisKey("id-2", 100L, "content://media/2")
        val gatedOut = MeasuredLoudness(null, null, null, 60f, 22_050f)
        cache.storeLoudness(gated, gatedOut)
        val loaded = cache.loadLoudness(gated)
        assertNotNull(loaded)
        assertNull(loaded!!.integratedLufs)
        // A modified file invalidates the old row.
        val modified = key.copy(modified = 200L)
        assertNull(cache.loadLoudness(modified))
        cache.storeLoudness(modified, measured.copy(integratedLufs = -9f))
        assertNotEquals(measured, cache.loadLoudness(modified))
        assertNull(cache.loadLoudness(key))
    }
}
