/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.automix.BeatGrid
import org.akanework.gramophone.logic.utils.automix.BeatGridConfidence
import org.akanework.gramophone.logic.utils.automix.BeatLoopIntervals
import org.akanework.gramophone.logic.utils.automix.BeatPosition
import org.akanework.gramophone.logic.utils.automix.BeatQuantization
import org.akanework.gramophone.logic.utils.automix.BeatPhaseAligner
import org.akanework.gramophone.logic.utils.automix.BpmInterpretation
import org.akanework.gramophone.logic.utils.automix.BpmMatchEvaluator
import org.akanework.gramophone.logic.utils.automix.LoopBoundaryScheduler
import org.akanework.gramophone.logic.utils.automix.LoopLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatTimingTest {
    private fun irregularGrid(confidence: Float = 0.95f) = BeatGrid(
        confidence = BeatGridConfidence(confidence),
        positions = listOf(
            BeatPosition(0, 100.0, isDownbeat = true),
            BeatPosition(1, 600.0),
            BeatPosition(2, 1_120.0),
            BeatPosition(3, 1_610.0),
            BeatPosition(4, 2_130.0, isDownbeat = true),
            BeatPosition(5, 2_650.0),
            BeatPosition(6, 3_140.0),
        ),
        beatsPerBar = 4,
    )

    @Test
    fun quantizationUsesTimestampGridAndKeepsDownbeatInformation() {
        val grid = irregularGrid()
        assertEquals(1_120.0, grid.quantize(1_300.0, BeatQuantization.FLOOR)!!.timeMs, 0.0)
        assertEquals(1_610.0, grid.quantize(1_300.0, BeatQuantization.CEILING)!!.timeMs, 0.0)
        assertTrue(grid.phaseAt(2_130.0)!!.isDownbeatAligned)
        assertTrue(grid.hasVariableIntervals)
    }

    @Test
    fun periodicBeatPeriodAloneDoesNotInventBarDownbeats() {
        val grid = BeatGrid(
            BeatGridConfidence(0.95f),
            nominalPeriodMs = 500.0,
            phaseOffsetMs = 0.0,
            beatsPerBar = 4,
        )
        assertFalse(grid.phaseAt(2_000.0)!!.isDownbeatAligned)
        assertFalse(grid.quantize(2_000.0)!!.isDownbeat)
    }

    @Test
    fun rejectsLowConfidenceAndIncompleteBeatBoundary() {
        val lowConfidenceGrid = irregularGrid(0.79f)
        assertNull(lowConfidenceGrid.quantize(1_000.0))
        assertFalse(lowConfidenceGrid.confidence.meets())
        val grid = irregularGrid()
        assertNull(grid.loopRegion(2_650.0, 4, durationMs = 10_000))
    }

    @Test
    fun incompleteExplicitGridDoesNotInventMissingVariableBeatTimes() {
        val incomplete = BeatGrid(
            confidence = BeatGridConfidence(0.95f),
            positions = listOf(
                BeatPosition(0, 100.0),
                BeatPosition(1, 600.0),
                BeatPosition(3, 1_600.0),
            ),
            nominalPeriodMs = 500.0,
            phaseOffsetMs = 100.0,
        )
        assertNull(incomplete.timeAt(2))
        assertNull(incomplete.quantize(1_000.0, BeatQuantization.FLOOR))
        assertNull(incomplete.loopRegion(600.0, 2, 3_000))
    }

    @Test
    fun loopBoundaryUsesRealVariableBeatTimesAndDurationChecks() {
        val grid = irregularGrid()
        val loop = grid.loopRegion(positionMs = 1_200.0, beatCount = 2, durationMs = 4_000)!!
        assertEquals(1_120L, loop.startMs)
        assertEquals(2_130L, loop.endMs)
        assertEquals(1_010L, loop.durationMs)
        assertNull(grid.loopRegion(1_200.0, 16, durationMs = 4_000))
        assertEquals(setOf(1, 2, 4, 8, 16), BeatLoopIntervals.supported)
    }

    @Test
    fun beatJumpSupportsEveryIntervalAndRejectsMissingOrOutOfRangeTargets() {
        val grid = irregularGrid()
        for (interval in BeatLoopIntervals.supported) {
            val forward = grid.jumpTarget(1_120.0, interval, 1, 20_000)
            val backward = grid.jumpTarget(2_130.0, interval, -1, 20_000)
            if (interval <= 4) {
                assertEquals(2L + interval, forward!!.beatIndex)
                assertEquals(4L - interval, backward!!.beatIndex)
            } else {
                assertNull(forward)
                assertNull(backward)
            }
        }
        assertNull(irregularGrid(0.79f).jumpTarget(1_120.0, 1, 1, 20_000))
        assertNull(grid.jumpTarget(100.0, 1, -1, 4_000))
    }

    @Test
    fun schedulerWrapsOvershootAndSupportsPauseAndCancellation() {
        val grid = irregularGrid()
        val loop = grid.loopRegion(1_200.0, 2, 4_000)!!
        val scheduler = LoopBoundaryScheduler()
        assertEquals(LoopLifecycleState.ACTIVE, scheduler.activate(loop, 4_000).state.lifecycle)
        assertEquals(LoopLifecycleState.PAUSED, scheduler.onPosition(2_200, false).state.lifecycle)
        val boundary = scheduler.onPosition(2_200, true)
        assertEquals(loop.startMs + 70L, boundary.seekPositionMs)
        assertEquals(1L, boundary.state.completedIterations)
        val multipleLoopsLate = scheduler.onPosition(4_300, true)
        assertEquals(loop.startMs + 150L, multipleLoopsLate.seekPositionMs)
        assertEquals(LoopLifecycleState.CANCELLED, scheduler.cancel("test").state.lifecycle)
        assertNull(scheduler.onPosition(4_000, true).seekPositionMs)
    }

    @Test
    fun selectsHalfTimeAndDoubleTimeOnlyWhenTempoPhaseAndSpeedAreValid() {
        val grid70 = BeatPhaseAligner.Grid(60_000.0 / 70.0, 0.0, 0.95f)
        val grid140 = BeatPhaseAligner.Grid(60_000.0 / 140.0, 0.0, 0.95f)
        val half = BpmMatchEvaluator.evaluate(70f, 140f, grid70, grid140, 0.0, 0.0)
        assertEquals(BpmInterpretation.HALF_TIME, half.selected!!.interpretation)
        assertEquals(1f, half.selected!!.incomingSpeed, 0.0001f)
        assertTrue(half.candidates.single { it.interpretation == BpmInterpretation.NORMAL }
            .alignment == null)

        val double = BpmMatchEvaluator.evaluate(140f, 70f, grid140, grid70, 0.0, 0.0)
        assertEquals(BpmInterpretation.DOUBLE_TIME, double.selected!!.interpretation)
        assertEquals(1f, double.selected!!.incomingSpeed, 0.0001f)

        val grid100 = BeatPhaseAligner.Grid(600.0, 0.0, 0.95f)
        val grid120 = BeatPhaseAligner.Grid(500.0, 0.0, 0.95f)
        val outsideLimit = BpmMatchEvaluator.evaluate(100f, 120f, grid100, grid120, 0.0, 0.0)
        assertNull(outsideLimit.selected)
    }
}
