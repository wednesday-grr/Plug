/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.logic.utils.automix

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

data class BeatGridConfidence(val value: Float) {
    init {
        require(value.isFinite() && value in 0f..1f)
    }

    fun meets(minimum: Float = BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE): Boolean =
        value >= minimum
}

data class BeatPosition(
    val beatIndex: Long,
    val timeMs: Double,
    val isDownbeat: Boolean = false,
) {
    init {
        require(timeMs.isFinite() && timeMs >= 0.0)
    }
}

data class BeatPhase(
    val beatIndex: Long,
    val fraction: Double,
    val isDownbeatAligned: Boolean,
) {
    init {
        require(fraction.isFinite() && fraction in 0.0..1.0)
    }
}

enum class BeatQuantization { NEAREST, FLOOR, CEILING }

data class BeatGrid(
    val confidence: BeatGridConfidence,
    val positions: List<BeatPosition> = emptyList(),
    val nominalPeriodMs: Double? = null,
    val phaseOffsetMs: Double? = null,
    val beatsPerBar: Int? = null,
) {
    init {
        require(positions.zipWithNext().all { (first, second) ->
            first.beatIndex < second.beatIndex && first.timeMs < second.timeMs
        })
        require(nominalPeriodMs == null || nominalPeriodMs.isFinite() && nominalPeriodMs > 0.0)
        require(phaseOffsetMs == null || phaseOffsetMs.isFinite())
        require((nominalPeriodMs == null) == (phaseOffsetMs == null))
        require(beatsPerBar == null || beatsPerBar > 0)
    }

    val hasVariableIntervals: Boolean
        get() = positions.zipWithNext().map { it.second.timeMs - it.first.timeMs }
            .distinctBy { (it * 1000.0).roundToLong() }.size > 1

    fun phaseAt(positionMs: Double, minimumConfidence: Float = BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE): BeatPhase? {
        if (!positionMs.isFinite() || positionMs < 0.0 || !confidence.meets(minimumConfidence)) return null
        val indexed = indexAtOrBefore(positionMs) ?: return null
        val beatTime = timeAt(indexed) ?: return null
        val nextTime = timeAt(indexed + 1) ?: return null
        if (nextTime <= beatTime) return null
        val fraction = ((positionMs - beatTime) / (nextTime - beatTime)).coerceIn(0.0, 1.0)
        return BeatPhase(indexed, fraction, isDownbeat(indexed) && fraction == 0.0)
    }

    fun quantize(
        positionMs: Double,
        mode: BeatQuantization = BeatQuantization.NEAREST,
        minimumConfidence: Float = BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE,
    ): BeatPosition? {
        if (!positionMs.isFinite() || positionMs < 0.0 || !confidence.meets(minimumConfidence)) return null
        val index = when (mode) {
            BeatQuantization.NEAREST -> nearestIndex(positionMs)
            BeatQuantization.FLOOR -> indexAtOrBefore(positionMs)
            BeatQuantization.CEILING -> indexAtOrAfter(positionMs)
        } ?: return null
        val time = timeAt(index) ?: return null
        return BeatPosition(index, time, isDownbeat(index))
    }

    fun jumpTarget(
        positionMs: Double,
        beatCount: Int,
        direction: Int,
        durationMs: Long,
        minimumConfidence: Float = BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE,
    ): BeatPosition? {
        require(beatCount in BeatLoopIntervals.supported)
        require(direction == -1 || direction == 1)
        if (durationMs <= 0) return null
        val current = quantize(positionMs, BeatQuantization.NEAREST, minimumConfidence) ?: return null
        val targetIndex = current.beatIndex + direction * beatCount
        val targetTime = timeAt(targetIndex) ?: return null
        if (targetTime < 0.0 || targetTime >= durationMs) return null
        return BeatPosition(targetIndex, targetTime, isDownbeat(targetIndex))
    }

    fun timeAt(beatIndex: Long): Double? {
        positions.firstOrNull { it.beatIndex == beatIndex }?.let { return it.timeMs }
        val period = nominalPeriodMs ?: return null
        if (positions.any { it.beatIndex < beatIndex } &&
            positions.any { it.beatIndex > beatIndex }
        ) return null
        val anchor = positions.minByOrNull { abs(it.beatIndex - beatIndex) }
        if (anchor != null) return anchor.timeMs + (beatIndex - anchor.beatIndex) * period
        val phase = phaseOffsetMs ?: return null
        return phase + beatIndex * period
    }

    fun loopRegion(
        positionMs: Double,
        beatCount: Int,
        durationMs: Long,
        minimumConfidence: Float = BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE,
    ): LoopRegion? {
        require(beatCount in BeatLoopIntervals.supported)
        if (durationMs <= 0 || !confidence.meets(minimumConfidence)) return null
        val start = quantize(positionMs, BeatQuantization.FLOOR, minimumConfidence) ?: return null
        if (!hasContinuousBoundaries(start.beatIndex, start.beatIndex + beatCount)) return null
        val endTime = timeAt(start.beatIndex + beatCount) ?: return null
        val startMs = start.timeMs.roundToLong()
        val endMs = endTime.roundToLong()
        if (startMs < 0 || endMs <= startMs || endMs > durationMs) return null
        return LoopRegion(startMs, endMs, start.beatIndex, beatCount)
    }

    fun loopRegionEndingBefore(
        earliestStartMs: Long,
        latestEndMs: Long,
        beatCount: Int,
        durationMs: Long,
        minimumConfidence: Float = BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE,
    ): LoopRegion? {
        require(beatCount in BeatLoopIntervals.supported)
        if (earliestStartMs < 0 || latestEndMs > durationMs || latestEndMs <= earliestStartMs ||
            !confidence.meets(minimumConfidence)
        ) return null
        val endBeat = quantize(latestEndMs.toDouble(), BeatQuantization.FLOOR, minimumConfidence) ?: return null
        val startIndex = endBeat.beatIndex - beatCount
        if (!hasContinuousBoundaries(startIndex, endBeat.beatIndex)) return null
        val start = timeAt(startIndex)?.roundToLong() ?: return null
        val end = timeAt(endBeat.beatIndex)?.roundToLong() ?: return null
        if (start < earliestStartMs || end <= start || end > durationMs) return null
        return LoopRegion(start, end, startIndex, beatCount)
    }

    private fun hasContinuousBoundaries(startBeatIndex: Long, endBeatIndex: Long): Boolean =
        positions.isEmpty() || (startBeatIndex..endBeatIndex).all { timeAt(it) != null }

    private fun isDownbeat(beatIndex: Long): Boolean {
        val anchor = positions.firstOrNull { it.isDownbeat } ?: return false
        val bar = beatsPerBar ?: return beatIndex == anchor.beatIndex
        return (beatIndex - anchor.beatIndex) % bar.toLong() == 0L
    }

    private fun nearestIndex(positionMs: Double): Long? {
        val explicit = positions.minByOrNull { abs(it.timeMs - positionMs) }
        if (explicit != null) {
            val period = nominalPeriodMs
            val first = positions.first()
            val last = positions.last()
            if (positionMs in first.timeMs..last.timeMs) {
                val before = positions.lastOrNull { it.timeMs <= positionMs }
                val after = positions.firstOrNull { it.timeMs >= positionMs }
                if (before != null && after != null && after.beatIndex - before.beatIndex > 1) return null
                return explicit.beatIndex
            }
            if (period == null) return null
        }
        val period = nominalPeriodMs ?: return explicit?.beatIndex
        val phase = phaseOffsetMs ?: return null
        return ((positionMs - phase) / period).roundToLong()
    }

    private fun indexAtOrBefore(positionMs: Double): Long? {
        val explicit = positions.lastOrNull { it.timeMs <= positionMs }
        val after = positions.firstOrNull { it.timeMs > positionMs }
        if (explicit != null && after != null) {
            return explicit.beatIndex.takeIf { after.beatIndex - explicit.beatIndex == 1L }
        }
        val period = nominalPeriodMs ?: return explicit?.beatIndex
        val phase = phaseOffsetMs ?: return null
        if (positions.isNotEmpty() && explicit == null) {
            val first = positions.first()
            return first.beatIndex + floor((positionMs - first.timeMs) / period).toLong()
        }
        if (positions.isNotEmpty() && after == null) {
            val last = positions.last()
            return last.beatIndex + floor((positionMs - last.timeMs) / period).toLong()
        }
        return floor((positionMs - phase) / period).toLong()
    }

    private fun indexAtOrAfter(positionMs: Double): Long? {
        val explicit = positions.firstOrNull { it.timeMs >= positionMs }
        val before = positions.lastOrNull { it.timeMs < positionMs }
        if (explicit != null && before != null) {
            return explicit.beatIndex.takeIf { explicit.beatIndex - before.beatIndex == 1L }
        }
        val period = nominalPeriodMs ?: return explicit?.beatIndex
        val phase = phaseOffsetMs ?: return null
        if (positions.isNotEmpty() && explicit == null) {
            val last = positions.last()
            val floorIndex = last.beatIndex + floor((positionMs - last.timeMs) / period).toLong()
            return if (timeAt(floorIndex)?.let { it >= positionMs } == true) floorIndex else floorIndex + 1
        }
        if (positions.isNotEmpty() && before == null) {
            val first = positions.first()
            val floorIndex = first.beatIndex + floor((positionMs - first.timeMs) / period).toLong()
            return if (timeAt(floorIndex)?.let { it >= positionMs } == true) floorIndex else floorIndex + 1
        }
        val floorIndex = floor((positionMs - phase) / period).toLong()
        return if (timeAt(floorIndex)?.let { it >= positionMs } == true) floorIndex else floorIndex + 1
    }
}

object BeatLoopIntervals {
    val supported = setOf(1, 2, 4, 8, 16)
}

data class LoopRegion(
    val startMs: Long,
    val endMs: Long,
    val startBeatIndex: Long,
    val beatCount: Int,
) {
    init {
        require(startMs >= 0 && endMs > startMs && beatCount in BeatLoopIntervals.supported)
    }

    val durationMs: Long get() = endMs - startMs
    val startBoundary: LoopBoundary get() = LoopBoundary(startMs, startBeatIndex, LoopBoundary.Type.START)
    val endBoundary: LoopBoundary get() = LoopBoundary(endMs, startBeatIndex + beatCount, LoopBoundary.Type.END)
}

data class LoopBoundary(val positionMs: Long, val beatIndex: Long, val type: Type) {
    enum class Type { START, END }
}

enum class LoopLifecycleState { INACTIVE, ACTIVE, PAUSED, COMPLETED, CANCELLED, FAILED, REJECTED }

data class LoopState(
    val lifecycle: LoopLifecycleState,
    val region: LoopRegion? = null,
    val completedIterations: Long = 0,
    val reason: String? = null,
    val generation: Long? = null,
) {
    val ownsTimingResources: Boolean
        get() = lifecycle == LoopLifecycleState.ACTIVE || lifecycle == LoopLifecycleState.PAUSED
}

data class LoopExecutionResult(
    val state: LoopState,
    val seekPositionMs: Long? = null,
    val timingAccuracy: String = LoopBoundaryScheduler.TIMING_LIMITATION,
    val accepted: Boolean = true,
)

class LoopBoundaryScheduler {
    private var state = LoopState(LoopLifecycleState.INACTIVE)

    fun state(): LoopState = state
    val ownsTimingResources: Boolean get() = state.ownsTimingResources

    fun activate(region: LoopRegion, durationMs: Long, generation: Long): LoopExecutionResult {
        require(generation > 0)
        if (ownsTimingResources) return LoopExecutionResult(state, accepted = false)
        if (region.endMs > durationMs) {
            state = LoopState(
                LoopLifecycleState.REJECTED,
                reason = "Loop exceeds track duration.",
                generation = generation,
            )
        } else {
            state = LoopState(LoopLifecycleState.ACTIVE, region, generation = generation)
        }
        return LoopExecutionResult(state, accepted = state.ownsTimingResources)
    }

    fun onPosition(
        positionMs: Long,
        playing: Boolean,
        generation: Long?,
    ): LoopExecutionResult {
        if (generation == null || generation != state.generation || !ownsTimingResources) {
            return LoopExecutionResult(state, accepted = false)
        }
        val region = state.region
        if (region == null) return LoopExecutionResult(state, accepted = false)
        if (!playing) {
            state = state.copy(lifecycle = LoopLifecycleState.PAUSED)
            return LoopExecutionResult(state)
        }
        state = state.copy(lifecycle = LoopLifecycleState.ACTIVE)
        if (positionMs < region.endMs) return LoopExecutionResult(state)
        state = state.copy(completedIterations = state.completedIterations + 1)
        val overshoot = (positionMs - region.endMs) % region.durationMs
        return LoopExecutionResult(state, region.startMs + overshoot)
    }

    fun cancel(reason: String, generation: Long?): LoopExecutionResult =
        terminate(LoopLifecycleState.CANCELLED, reason, generation)

    fun complete(reason: String, generation: Long?): LoopExecutionResult =
        terminate(LoopLifecycleState.COMPLETED, reason, generation)

    fun fail(reason: String, generation: Long?): LoopExecutionResult =
        terminate(LoopLifecycleState.FAILED, reason, generation)

    private fun terminate(
        terminalState: LoopLifecycleState,
        reason: String,
        generation: Long?,
    ): LoopExecutionResult {
        if (generation == null || generation != state.generation || !ownsTimingResources) {
            return LoopExecutionResult(state, accepted = false)
        }
        state = state.copy(lifecycle = terminalState, reason = reason)
        return LoopExecutionResult(state)
    }

    companion object {
        const val TIMING_LIMITATION =
            "Boundary checked on the application looper (25 ms while looping) and performed by Media3 seek; not sample-accurate."
    }
}

class BeatLoopCoordinator(private val gainOwnership: GainOwnershipRegistry) {
    private val scheduler = LoopBoundaryScheduler()
    private var ownedGeneration: Long? = null

    @Synchronized
    fun state(): LoopState = scheduler.state()

    @get:Synchronized
    val ownsTimingResources: Boolean
        get() = scheduler.ownsTimingResources

    @Synchronized
    fun canStartExclusiveOperation(): Boolean = !scheduler.ownsTimingResources

    @Synchronized
    fun activate(region: LoopRegion, durationMs: Long, generation: Long): LoopExecutionResult {
        val result = scheduler.activate(region, durationMs, generation)
        if (!result.accepted) return result
        try {
            gainOwnership.claim(AutoMixEffect.BEAT_LOOP, generation)
            ownedGeneration = generation
            return result
        } catch (error: RuntimeException) {
            scheduler.fail("Beat Loop ownership acquisition failed.", generation)
            throw error
        }
    }

    @Synchronized
    fun onPosition(positionMs: Long, playing: Boolean, generation: Long?): LoopExecutionResult =
        scheduler.onPosition(positionMs, playing, generation)

    @Synchronized
    fun cancel(reason: String, generation: Long?): LoopExecutionResult =
        terminate(scheduler.cancel(reason, generation), generation)

    @Synchronized
    fun complete(reason: String, generation: Long?): LoopExecutionResult =
        terminate(scheduler.complete(reason, generation), generation)

    @Synchronized
    fun fail(reason: String, generation: Long?): LoopExecutionResult =
        terminate(scheduler.fail(reason, generation), generation)

    private fun terminate(result: LoopExecutionResult, generation: Long?): LoopExecutionResult {
        if (result.accepted && ownedGeneration == generation) {
            gainOwnership.releaseGeneration(checkNotNull(generation))
            ownedGeneration = null
        }
        return result
    }
}

data class BeatEffectTelemetry(
    val effectId: String,
    val available: Boolean,
    val evaluated: Boolean,
    val selected: Boolean,
    val state: EffectLifecycleState,
    val gridConfidence: Float? = null,
    val beatPositionMs: Long? = null,
    val beatIndex: Long? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val tempoInterpretation: String? = null,
    val tempoRatio: Float? = null,
    val reason: String? = null,
    val timingLimitation: String? = null,
)

enum class BpmInterpretation { NORMAL, HALF_TIME, DOUBLE_TIME }

data class BpmMatchCandidate(
    val interpretation: BpmInterpretation,
    val effectiveIncomingBpm: Float,
    val incomingSpeed: Float,
    val alignment: BeatPhaseAligner.Alignment?,
    val selected: Boolean,
    val reason: String,
)

data class BpmMatchEvaluation(val candidates: List<BpmMatchCandidate>, val selected: BpmMatchCandidate?)

object BpmMatchEvaluator {
    fun evaluate(
        outgoingBpm: Float?,
        incomingBpm: Float?,
        outgoingGrid: BeatPhaseAligner.Grid?,
        incomingGrid: BeatPhaseAligner.Grid?,
        outgoingPositionMs: Double,
        incomingPositionMs: Double,
        maximumDeviation: Float = AutomixMath.DEFAULT_MAX_TEMPO_DEVIATION,
        minimumConfidence: Float = BeatPhaseAligner.DEFAULT_MINIMUM_CONFIDENCE,
        pitchPreserving: Boolean = true,
    ): BpmMatchEvaluation {
        require(maximumDeviation.isFinite() && maximumDeviation in 0f..<1f)
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f)
        if (!pitchPreserving || outgoingBpm == null || incomingBpm == null ||
            !outgoingBpm.isFinite() || !incomingBpm.isFinite() || outgoingBpm <= 0f || incomingBpm <= 0f
        ) return BpmMatchEvaluation(emptyList(), null)

        val candidates = BpmInterpretation.entries.map { interpretation ->
            val multiplier = when (interpretation) {
                BpmInterpretation.NORMAL -> 1f
                BpmInterpretation.HALF_TIME -> 0.5f
                BpmInterpretation.DOUBLE_TIME -> 2f
            }
            val effectiveBpm = incomingBpm * multiplier
            val speed = outgoingBpm / effectiveBpm
            val adjustedGrid = incomingGrid?.let {
                val periodScale = 1.0 / multiplier
                it.copy(beatPeriodMs = it.beatPeriodMs * periodScale)
            }
            val alignment = if (outgoingGrid?.confidence?.let { it >= minimumConfidence } == true &&
                adjustedGrid?.confidence?.let { it >= minimumConfidence } == true &&
                speed.isFinite() && kotlin.math.abs(speed - 1f) <= maximumDeviation
            ) BeatPhaseAligner.align(
                outgoingBpm, effectiveBpm, outgoingGrid, adjustedGrid,
                outgoingPositionMs, incomingPositionMs, minimumConfidence,
            ) else null
            val reason = when {
                !speed.isFinite() || kotlin.math.abs(speed - 1f) > maximumDeviation ->
                    "Required speed is outside the existing ${(maximumDeviation * 100).toInt()}% limit."
                outgoingGrid == null || incomingGrid == null -> "Beat grid unavailable."
                outgoingGrid.confidence < minimumConfidence || incomingGrid.confidence < minimumConfidence ->
                    "Beat-grid confidence is below the configured minimum."
                alignment == null -> "Beat phases are not compatible."
                else -> "Tempo ratio and beat phase are compatible."
            }
            BpmMatchCandidate(interpretation, effectiveBpm, speed, alignment, false, reason)
        }
        val selected = candidates.filter { it.alignment != null }
            .minWithOrNull(compareBy<BpmMatchCandidate> { abs(it.incomingSpeed - 1f) }
                .thenByDescending { minOf(outgoingGrid?.confidence ?: 0f, incomingGrid?.confidence ?: 0f) }
                .thenBy { if (it.interpretation == BpmInterpretation.NORMAL) 0 else 1 })
        val marked = candidates.map { it.copy(
            selected = it.interpretation == selected?.interpretation,
            reason = if (it.interpretation == selected?.interpretation) it.reason else it.reason,
        ) }
        return BpmMatchEvaluation(marked, selected?.let { candidate ->
            marked.first { it.interpretation == candidate.interpretation }
        })
    }
}
