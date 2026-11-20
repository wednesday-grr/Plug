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
import org.akanework.gramophone.logic.utils.automix.BeatLoopCoordinator
import org.akanework.gramophone.logic.utils.automix.BeatLoopIntervals
import org.akanework.gramophone.logic.utils.automix.BeatPosition
import org.akanework.gramophone.logic.utils.automix.BeatQuantization
import org.akanework.gramophone.logic.utils.automix.BeatPhaseAligner
import org.akanework.gramophone.logic.utils.automix.BpmInterpretation
import org.akanework.gramophone.logic.utils.automix.BpmMatchEvaluator
import org.akanework.gramophone.logic.utils.automix.AutoMixEffect
import org.akanework.gramophone.logic.utils.automix.EffectGainParameter
import org.akanework.gramophone.logic.utils.automix.GainOwnershipRegistry
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
        assertEquals(LoopLifecycleState.ACTIVE, scheduler.activate(loop, 4_000, 1).state.lifecycle)
        assertEquals(LoopLifecycleState.PAUSED, scheduler.onPosition(2_200, false, 1).state.lifecycle)
        val boundary = scheduler.onPosition(2_200, true, 1)
        assertEquals(loop.startMs + 70L, boundary.seekPositionMs)
        assertEquals(1L, boundary.state.completedIterations)
        val multipleLoopsLate = scheduler.onPosition(4_300, true, 1)
        assertEquals(loop.startMs + 150L, multipleLoopsLate.seekPositionMs)
        assertEquals(LoopLifecycleState.CANCELLED, scheduler.cancel("test", 1).state.lifecycle)
        assertNull(scheduler.onPosition(4_000, true, 1).seekPositionMs)
    }

    @Test
    fun activeLoopRejectsBeatJumpAndRetainsLoopOwnership() {
        val region = irregularGrid().loopRegion(1_200.0, 2, 4_000)!!
        val registry = GainOwnershipRegistry()
        val coordinator = BeatLoopCoordinator(registry)
        coordinator.activate(region, 4_000, 1)
        val stateBeforeRequest = coordinator.state()

        assertFalse(coordinator.canStartExclusiveOperation())
        assertEquals(stateBeforeRequest, coordinator.state())
        assertEquals(
            setOf(1L),
            registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).map { it.generation }.toSet(),
        )
    }

    @Test
    fun pausedLoopRejectsBeatJumpAndRetainsLoopOwnership() {
        val region = irregularGrid().loopRegion(1_200.0, 2, 4_000)!!
        val registry = GainOwnershipRegistry()
        val coordinator = BeatLoopCoordinator(registry)
        coordinator.activate(region, 4_000, 11)
        val paused = coordinator.onPosition(region.endMs, playing = false, generation = 11)

        assertEquals(LoopLifecycleState.PAUSED, paused.state.lifecycle)
        assertFalse(coordinator.canStartExclusiveOperation())
        assertEquals(paused.state, coordinator.state())
        assertEquals(
            setOf(11L),
            registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).map { it.generation }.toSet(),
        )
        assertTrue(registry.owners(EffectGainParameter.OUTGOING_SEEK).isEmpty())
    }

    @Test
    fun activeLoopRejectsNewBeatLoopWithoutChangingBoundariesOrOwnership() {
        val grid = irregularGrid()
        val region = grid.loopRegion(1_200.0, 2, 4_000)!!
        val replacement = grid.loopRegion(600.0, 1, 4_000)!!
        val registry = GainOwnershipRegistry()
        val coordinator = BeatLoopCoordinator(registry)
        coordinator.activate(region, 4_000, 21)
        val stateBeforeRequest = coordinator.state()

        val result = coordinator.activate(replacement, 4_000, 22)

        assertFalse(result.accepted)
        assertEquals(stateBeforeRequest, result.state)
        assertEquals(region, coordinator.state().region)
        assertEquals(
            setOf(21L),
            registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).map { it.generation }.toSet(),
        )
    }

    @Test
    fun pausedLoopRejectsNewBeatLoopWithoutChangingBoundariesOrOwnership() {
        val grid = irregularGrid()
        val region = grid.loopRegion(1_200.0, 2, 4_000)!!
        val replacement = grid.loopRegion(600.0, 1, 4_000)!!
        val registry = GainOwnershipRegistry()
        val coordinator = BeatLoopCoordinator(registry)
        coordinator.activate(region, 4_000, 31)
        coordinator.onPosition(region.startMs, playing = false, generation = 31)
        val pausedState = coordinator.state()

        val result = coordinator.activate(replacement, 4_000, 32)

        assertFalse(result.accepted)
        assertEquals(pausedState, result.state)
        assertEquals(region, coordinator.state().region)
        assertEquals(
            setOf(31L),
            registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).map { it.generation }.toSet(),
        )
    }

    @Test
    fun pausedPlaybackWithoutLoopAllowsExclusiveBeatOperation() {
        val coordinator = BeatLoopCoordinator(GainOwnershipRegistry())
        assertTrue(coordinator.canStartExclusiveOperation())
        assertFalse(coordinator.state().ownsTimingResources)
    }

    @Test
    fun terminalLoopStatesReleaseOnlyTheirGenerationAndAllowNewOperations() {
        val region = irregularGrid().loopRegion(1_200.0, 2, 4_000)!!
        listOf(
            Triple(LoopLifecycleState.COMPLETED, "complete", 20L),
            Triple(LoopLifecycleState.CANCELLED, "cancel", 30L),
            Triple(LoopLifecycleState.FAILED, "fail", 40L),
        ).forEach { (terminal, action, firstGeneration) ->
            val registry = GainOwnershipRegistry()
            val coordinator = BeatLoopCoordinator(registry)
            coordinator.activate(region, 4_000, firstGeneration)
            val terminated = when (action) {
                "complete" -> coordinator.complete("finished", firstGeneration)
                "fail" -> coordinator.fail("playback error", firstGeneration)
                else -> coordinator.cancel("queue changed", firstGeneration)
            }
            assertEquals(terminal, terminated.state.lifecycle)
            assertTrue(coordinator.canStartExclusiveOperation())
            assertTrue(registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).isEmpty())
            assertFalse(coordinator.cancel("duplicate termination", firstGeneration).accepted)

            val nextGeneration = firstGeneration + 1
            assertTrue(coordinator.activate(region, 4_000, nextGeneration).accepted)
            assertEquals(nextGeneration, coordinator.state().generation)
        }
    }

    @Test
    fun staleCallbacksAfterCancellationCannotChangeStateOrReleaseNewLoopOwnership() {
        val region = irregularGrid().loopRegion(1_200.0, 2, 4_000)!!
        val registry = GainOwnershipRegistry()
        val coordinator = BeatLoopCoordinator(registry)
        coordinator.activate(region, 4_000, 51)
        coordinator.onPosition(region.endMs, playing = false, generation = 51)
        assertTrue(coordinator.cancel("queue replacement", 51).accepted)
        assertTrue(coordinator.activate(region, 4_000, 52).accepted)
        val newerState = coordinator.state()

        val staleTick = coordinator.onPosition(region.endMs + 1_000, true, generation = 51)
        val staleCancel = coordinator.cancel("late cancellation", generation = 51)

        assertFalse(staleTick.accepted)
        assertFalse(staleCancel.accepted)
        assertEquals(newerState, coordinator.state())
        assertEquals(
            setOf(52L),
            registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).map { it.generation }.toSet(),
        )
    }

    @Test
    fun pauseResumeReusesOneLoopAndQueueOrHandoffCancellationCleansItUp() {
        val region = irregularGrid().loopRegion(1_200.0, 2, 4_000)!!
        val registry = GainOwnershipRegistry()
        val coordinator = BeatLoopCoordinator(registry)
        coordinator.activate(region, 4_000, 61)

        repeat(3) {
            assertEquals(
                LoopLifecycleState.PAUSED,
                coordinator.onPosition(region.startMs, false, 61).state.lifecycle,
            )
        }
        val resumed = coordinator.onPosition(region.endMs + 25, true, 61)
        assertEquals(LoopLifecycleState.ACTIVE, resumed.state.lifecycle)
        assertEquals(1L, resumed.state.completedIterations)
        assertEquals(region.startMs + 25L, resumed.seekPositionMs)
        assertEquals(1, registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).size)

        val queueChange = coordinator.cancel("queue changed while paused", 61)
        assertEquals(LoopLifecycleState.CANCELLED, queueChange.state.lifecycle)
        assertTrue(registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).isEmpty())
        assertFalse(coordinator.onPosition(region.endMs, true, 61).accepted)
        assertTrue(coordinator.activate(region, 4_000, 62).accepted)
        val handoff = coordinator.cancel("deck handoff", 62)
        assertEquals(LoopLifecycleState.CANCELLED, handoff.state.lifecycle)
        assertTrue(registry.owners(EffectGainParameter.OUTGOING_LOOP_REGION).isEmpty())
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
