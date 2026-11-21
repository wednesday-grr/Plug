/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.automix.FadeCurveSettings
import org.akanework.gramophone.logic.utils.automix.GainSlew
import org.akanework.gramophone.logic.utils.automix.TransitionVolumeCurves
import org.akanework.gramophone.logic.utils.automix.VolumeCurve
import org.akanework.gramophone.logic.utils.automix.complementaryFadeGains
import org.akanework.gramophone.logic.utils.automix.VolumeEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeCurveTest {
    private val curves = listOf(
        VolumeCurve.Linear, VolumeCurve.EqualPower,
        VolumeCurve.Exponential(4f), VolumeCurve.Exponential(-4f), VolumeCurve.Exponential(0f),
        VolumeCurve.SCurve(0f), VolumeCurve.SCurve(8f), VolumeCurve.SCurve(20f),
    )

    @Test
    fun allCurvesHitExactEndpointsAndAreMonotonic() {
        for (c in curves) {
            assertEquals("$c start", 0f, c.fadeIn(0f), 0f)
            assertEquals("$c end", 1f, c.fadeIn(1f), 1e-6f)
            var prev = -1f
            for (i in 0..1000) {
                val g = c.fadeIn(i / 1000f)
                assertTrue("$c not monotonic at $i", g >= prev)
                assertTrue(g in 0f..1f)
                prev = g
            }
        }
    }

    @Test
    fun exponentialMatchesClosedFormAndIsSlowAtStart() {
        val k = 4f
        val c = VolumeCurve.Exponential(k)
        val expected = ((Math.exp(k * 0.5) - 1) / (Math.exp(k.toDouble()) - 1)).toFloat()
        assertEquals(expected, c.fadeIn(0.5f), 1e-6f)
        assertTrue(c.fadeIn(0.5f) < 0.5f)
        assertTrue(VolumeCurve.Exponential(-4f).fadeIn(0.5f) > 0.5f)
    }

    @Test
    fun sCurveIsPointSymmetricAndSteeperInMiddle() {
        val c = VolumeCurve.SCurve(8f)
        assertEquals(0.5f, c.fadeIn(0.5f), 1e-6f)
        for (t in listOf(0.1f, 0.25f, 0.4f)) {
            assertEquals(1f, c.fadeIn(t) + c.fadeIn(1f - t), 1e-5f)
        }
        val edge = c.fadeIn(0.1f) - c.fadeIn(0f)
        val mid = c.fadeIn(0.55f) - c.fadeIn(0.45f)
        assertTrue(mid > edge)
    }

    @Test
    fun sCurveSlopeIsContinuousNoGainJumps() {
        val c = VolumeCurve.SCurve(10f)
        val step = 1f / 4800f
        var maxJump = 0f
        for (i in 1..4800) maxJump = maxOf(maxJump, c.fadeIn(i * step) - c.fadeIn((i - 1) * step))
        assertTrue("max per-step gain jump $maxJump", maxJump < 0.01f)
    }

    @Test
    fun outgoingAndIncomingEnvelopesAreIndependent() {
        val t = TransitionVolumeCurves.of(
            outgoing = VolumeCurve.Exponential(3f),
            incoming = VolumeCurve.SCurve(8f),
            outgoingWindow = 0.2f..0.8f,
            incomingWindow = 0f..0.5f,
        )
        assertEquals(1f, t.gains(0f).outgoing, 0f)
        assertEquals(1f, t.gains(0.2f).outgoing, 1e-6f)
        assertEquals(0f, t.gains(0.8f).outgoing, 1e-6f)
        assertEquals(1f, t.gains(0.5f).incoming, 1e-6f)
        assertEquals(1f, t.gains(1f).incoming, 1e-6f)
        assertEquals(0f, t.gains(0f).incoming, 0f)
    }

    @Test
    fun fadeOutIsMirrorOfFadeIn() {
        val out = VolumeEnvelope(VolumeCurve.Exponential(5f), fadesIn = false)
        val inn = VolumeEnvelope(VolumeCurve.Exponential(5f), fadesIn = true)
        for (i in 0..20) assertEquals(inn.gain(1f - i / 20f), out.gain(i / 20f), 1e-6f)
    }

    @Test
    fun invalidParametersAreRejected() {
        assertThrowsIae { VolumeCurve.Exponential(Float.NaN) }
        assertThrowsIae { VolumeCurve.Exponential(100f) }
        assertThrowsIae { VolumeCurve.SCurve(-1f) }
        assertThrowsIae { VolumeEnvelope(VolumeCurve.Linear, true, 0.6f, 0.4f) }
    }

    @Test
    fun appliedFadeOnRealSignalHasNoClicks() {
        val sr = 48_000
        val sig = DspTestSignals.sine(440.0, sr, sr)
        val gain = VolumeCurve.SCurve(8f)
        val out = DspTestSignals.applyGain(sig) { gain.fadeIn(it / (sr - 1f)) }
        val unfaded = DspTestSignals.maxStep(sig)
        assertTrue(DspTestSignals.maxStep(out) <= unfaded * 1.01f)
        assertEquals(0f, out[0], 0f)
    }

    @Test
    fun gainSlewLimitsStepAndConverges() {
        val s = GainSlew(0.1f, initial = 1f)
        assertEquals(0.9f, s.next(0f), 1e-6f)
        repeat(20) { s.next(0f) }
        assertEquals(0f, s.current, 0f)
        assertEquals(0.1f, s.next(1f), 1e-6f)
    }

    private fun assertThrowsIae(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    @Test
    fun complementaryFadeGainsSumToOneAndAreMonotonic() {
        val curves = listOf(
            VolumeCurve.Exponential(FadeCurveSettings.exponentialStrength(50)),
            VolumeCurve.SCurve(FadeCurveSettings.sCurveSteepness(50)),
        )
        for (curve in curves) {
            var previousOut = 1f
            for (i in 0..100) {
                val g = complementaryFadeGains(curve, i / 100f)
                assertEquals(1f, g.outgoing + g.incoming, 1e-5f)
                assertTrue(g.outgoing <= previousOut + 1e-5f)
                previousOut = g.outgoing
            }
            assertEquals(1f, complementaryFadeGains(curve, 0f).outgoing, 1e-4f)
            assertEquals(0f, complementaryFadeGains(curve, 1f).outgoing, 1e-4f)
        }
    }
}
