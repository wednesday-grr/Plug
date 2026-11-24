package com.siw.plug

import com.siw.plug.logic.utils.automix.stems.*
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StemMixerTest {
    private class Const(val v: Float) : StemSource {
        override fun read(out: FloatArray, frames: Int): Int { out.fill(v, 0, frames * 2); return frames }
    }

    @Test fun curvesStartAndEndOnOneTrack() {
        for (e in StemEffect.values()) {
            val s = StemTransitionMath.gains(e, 0f)
            assertEquals(1.0, s.aVocal.toDouble(), 1e-4); assertEquals(1.0, s.aInstrumental.toDouble(), 1e-4)
            assertEquals(0.0, s.bVocal.toDouble() + s.bInstrumental, 1e-4)
            val t = StemTransitionMath.gains(e, 1f)
            assertEquals(0.0, t.aVocal.toDouble() + t.aInstrumental, 1e-4)
            assertEquals(1.0, t.bVocal.toDouble(), 1e-4); assertEquals(1.0, t.bInstrumental.toDouble(), 1e-4)
        }
    }

    @Test fun curvesAreBoundedAndContinuous() {
        for (e in StemEffect.values()) {
            var prev = StemTransitionMath.gains(e, 0f)
            for (i in 1..1000) {
                val g = StemTransitionMath.gains(e, i / 1000f)
                for ((x, y) in listOf(prev.aVocal to g.aVocal, prev.aInstrumental to g.aInstrumental, prev.bVocal to g.bVocal, prev.bInstrumental to g.bInstrumental)) {
                    assertTrue(y in 0f..1.0001f)
                    assertTrue("$e jump at $i", abs(x - y) < 0.1f)
                }
                prev = g
            }
        }
    }

    @Test fun vocalsNeverFullyClash() {
        for (e in StemEffect.values()) assertTrue("$e", StemTransitionMath.peakVocalOverlap(e) < 0.8f)
    }

    @Test fun limiterNeverExceedsOneAndIsTransparentBelowKnee() {
        assertEquals(0.5f, StemSoftLimiter.process(0.5f))
        for (x in listOf(0.95f, 1f, 2f, 10f, -3f)) assertTrue(abs(StemSoftLimiter.process(x)) <= 1f)
    }

    @Test fun mixerSumsFourSourcesWithoutClipping() {
        val m = StemMixer(Const(0.9f), Const(0.9f), Const(0.9f), Const(0.9f)) { StemGains(1f, 1f, 1f, 1f) }
        val out = FloatArray(1024 * 2)
        m.mix(out, 1024)
        assertEquals(4, m.lastActiveSources)
        assertTrue(out.all { abs(it) <= 1f })
    }

    @Test fun mixerReportsOnlySourcesActuallyMixed() {
        val m = StemMixer(Const(0.1f), Const(0.1f), Const(0.1f), Const(0.1f)) { StemGains(1f, 1f, 0f, 0f) }
        m.mix(FloatArray(256 * 2), 256)
        assertEquals(2, m.lastActiveSources)
    }

    @Test fun mixerSmoothsGainSteps() {
        val m = StemMixer(Const(0.5f), Const(0f), Const(0f), Const(0f)) { f -> if (f < 128) StemGains(1f, 0f, 0f, 0f) else StemGains(0f, 0f, 0f, 0f) }
        val out = FloatArray(512 * 2)
        m.mix(out, 512)
        var maxStep = 0f
        for (i in 1 until 512) maxStep = maxOf(maxStep, abs(out[i * 2] - out[(i - 1) * 2]))
        assertTrue("step $maxStep", maxStep < 0.05f)
    }

    @Test fun readerSeeksAndPadsSilence() {
        val f = File.createTempFile("stem", ".pcm")
        try {
            val b = ByteArray(8 * 4)
            for (i in 0 until 16) { val s = (i * 1000).toShort().toInt(); b[i * 2] = s.toByte(); b[i * 2 + 1] = (s shr 8).toByte() }
            f.writeBytes(b)
            StemPcmReader(f).use { r ->
                assertEquals(8L, r.totalFrames)
                r.seekToFrame(6)
                val out = FloatArray(8)
                assertEquals(2, r.read(out, 4))
                assertEquals(12000 / 32768f, out[0], 1e-6f)
                assertEquals(0f, out[4]); assertEquals(0f, out[7])
            }
        } finally { f.delete() }
    }

    private fun ev(ready: Boolean = true, lufs: Double? = -14.0, bpm: Double? = 120.0, conf: Float? = 0.9f, key: String? = "8A") =
        StemTrackEvidence(ready, lufs, bpm, conf, key)

    private fun plan(a: StemTrackEvidence, b: StemTrackEvidence, enabled: Boolean = true, eligible: Boolean = true, ms: Long = 8000, delta: Double? = null) =
        StemAutoMixPlanner.plan(StemPlanInput(a, b, ms, delta, eligible, enabled))

    @Test fun plannerFallsBackWhenEvidenceMissing() {
        assertEquals(StemPlanReason.DISABLED, plan(ev(), ev(), enabled = false).reason)
        assertEquals(StemPlanReason.DEVICE_INELIGIBLE, plan(ev(), ev(), eligible = false).reason)
        assertEquals(StemPlanReason.STEMS_MISSING, plan(ev(), ev(ready = false)).reason)
        assertEquals(StemPlanReason.TRANSITION_TOO_SHORT, plan(ev(), ev(), ms = 1000).reason)
        assertEquals(StemPlanReason.LOUDNESS_UNKNOWN, plan(ev(), ev(lufs = null)).reason)
        assertEquals(StemPlanReason.LOUDNESS_MISMATCH, plan(ev(), ev(lufs = -30.0)).reason)
        assertEquals(StemPlanReason.BEAT_EVIDENCE_MISSING, plan(ev(), ev(conf = 0.2f)).reason)
        assertEquals(StemPlanReason.TEMPO_MISMATCH, plan(ev(), ev(bpm = 128.0)).reason)
        assertEquals(StemPlanReason.KEY_INCOMPATIBLE, plan(ev(), ev(key = "2B")).reason)
        assertNull(plan(ev(), ev(bpm = null)).effect)
    }

    @Test fun plannerSelectsByEvidence() {
        assertEquals(StemEffect.INSTRUMENTAL_DROP, plan(ev(), ev(), delta = 10.0).effect)
        assertEquals(StemEffect.INSTRUMENTAL_FADE, plan(ev(), ev(key = "9A")).effect)
        assertEquals(StemEffect.INSTRUMENTAL_TO_VOCAL, plan(ev(key = null), ev(key = null)).effect)
    }

    @Test fun camelotRelations() {
        assertEquals(CamelotRelation.ADJACENT, CamelotRelation.of("12A", "1A"))
        assertEquals(CamelotRelation.SAME, CamelotRelation.of("8A", "8a"))
        assertEquals(CamelotRelation.UNKNOWN, CamelotRelation.of("x", "8A"))
    }

    @Test fun stemPlayerRendersFullTransitionAndReportsFailure() {
        val dir = java.nio.file.Files.createTempDirectory("sp").toFile()
        try {
            val pcm = ByteArray(44100 * 4) { 0x10 }
            for (n in listOf("vocals.pcm16", "accompaniment.pcm16")) File(dir, n).writeBytes(pcm)
            val a = StemEntry(StemKey("t", "c", "m", "1"), dir, 44100L, 44100, 2, "", "", 0L, 0L, 0L)
            val total = java.util.concurrent.atomic.AtomicLong()
            val done = java.util.concurrent.CountDownLatch(1)
            val out = object : StemAudioOutput {
                override fun write(samples: FloatArray, frames: Int): Int { total.addAndGet(frames.toLong()); return frames }
                override fun start() {}
                override fun release() {}
                override fun setVolume(volume: Float) {}
                override fun playedFrames() = total.get()
            }
            val p = StemPlayer({ out }, { throw AssertionError(it) })
            p.start(a, a, StemEffect.INSTRUMENTAL_FADE, 0, 0, 20000L + 220L) { done.countDown() }
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("written ${total.get()}", total.get() in 1L..19780L)

            val failed = java.util.concurrent.CountDownLatch(1)
            val bad = object : StemAudioOutput {
                override fun write(samples: FloatArray, frames: Int) = -1
                override fun start() {}
                override fun release() {}
                override fun setVolume(volume: Float) {}
                override fun playedFrames() = total.get()
            }
            StemPlayer({ bad }, { failed.countDown() }).start(a, a, StemEffect.INSTRUMENTAL_FADE, 0, 0, 20000L) {}
            assertTrue(failed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { dir.deleteRecursively() }
    }
}