/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.siw.plug.logic.utils.automix

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioBecomingNoisyManager
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.extractor.mp3.Mp3Extractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.siw.plug.logic.utils.ReplayGainAudioProcessor
import com.siw.plug.logic.utils.ReplayGainUtil
import com.siw.plug.logic.utils.CircularShuffleOrder
import com.siw.plug.logic.utils.PostAmpAudioOutputProvider
import androidx.media3.common.Format
import com.siw.plug.logic.utils.exoplayer.PlugRenderFactory
import com.siw.plug.logic.utils.exoplayer.PlugExtractorsFactory
import com.siw.plug.logic.utils.exoplayer.PlugMediaSourceFactory
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

internal object AutomixEffectSelector {
    private const val CLOSE_BPM_DIFFERENCE = 0.06f
    private const val MODERATE_BPM_DIFFERENCE = 0.12f
    private const val MAX_BPM_DIFFERENCE = 0.15f
    private const val HIGH_VOCAL_ENERGY = 0.55f

    data class Decision(
        val style: String,
        val reasons: List<EffectDecisionReason>,
        val differencePercent: Float? = null,
        val thresholdPercent: Float? = null,
        val risingPercent: Float? = null,
        val risingThresholdPercent: Float? = null,
        val vocalThreshold: Float? = null,
    )

    fun select(
        outgoingBpm: Float?,
        incomingBpm: Float?,
        phaseConfident: Boolean,
        outgoingVocalEnergy: Float?,
        incomingVocalEnergy: Float?,
    ): String = decide(outgoingBpm, incomingBpm, phaseConfident, outgoingVocalEnergy,
        incomingVocalEnergy).style

    fun decide(
        outgoingBpm: Float?,
        incomingBpm: Float?,
        phaseConfident: Boolean,
        outgoingVocalEnergy: Float?,
        incomingVocalEnergy: Float?,
    ): Decision {
        val out = outgoingBpm?.takeIf { it.isFinite() && it in 30f..300f }
        val incoming = incomingBpm?.takeIf { it.isFinite() && it in 30f..300f }
        if (out == null || incoming == null) return Decision("fade", listOf(EffectDecisionReason.BPM_UNAVAILABLE))
        val difference = kotlin.math.abs(incoming / out - 1f)
        if (difference > MAX_BPM_DIFFERENCE) return Decision("fade",
            listOf(EffectDecisionReason.BPM_DIFFERENCE_TOO_LARGE), difference * 100f, MAX_BPM_DIFFERENCE * 100f)
        if (difference <= CLOSE_BPM_DIFFERENCE && phaseConfident) {
            return if ((outgoingVocalEnergy ?: 0f) >= HIGH_VOCAL_ENERGY &&
                (incomingVocalEnergy ?: 0f) >= HIGH_VOCAL_ENERGY
            ) Decision("vocal_swap", listOf(EffectDecisionReason.BPM_COMPATIBLE,
                EffectDecisionReason.VOCAL_ENERGY_HIGH), difference * 100f, CLOSE_BPM_DIFFERENCE * 100f,
                vocalThreshold = HIGH_VOCAL_ENERGY)
            else Decision("bass_swap", listOf(EffectDecisionReason.BPM_COMPATIBLE,
                EffectDecisionReason.VOCAL_THRESHOLD_NOT_MET_OR_UNAVAILABLE),
                difference * 100f, CLOSE_BPM_DIFFERENCE * 100f, vocalThreshold = HIGH_VOCAL_ENERGY)
        }
        return if (difference <= MODERATE_BPM_DIFFERENCE) {
            val rising = incoming > out * 1.06f
            Decision(if (rising) "rise" else "filter_sweep",
                listOf(EffectDecisionReason.BPM_MODERATE_DIFFERENCE,
                    if (rising) EffectDecisionReason.BPM_RISING else EffectDecisionReason.BPM_NOT_RISING_FAST) +
                    if (!phaseConfident) listOf(EffectDecisionReason.BEAT_ALIGNMENT_UNAVAILABLE) else emptyList(),
                difference * 100f, MODERATE_BPM_DIFFERENCE * 100f,
                (incoming / out - 1f) * 100f, (1.06f - 1f) * 100f)
        } else Decision("echo_out", listOf(EffectDecisionReason.BPM_DIFFERENCE_WITHIN_RANGE),
            difference * 100f, MODERATE_BPM_DIFFERENCE * 100f)
    }
}

/**
 * Standalone dual-deck player. All public methods and callbacks use the application looper.
 * The owner must supply the actual next queue item and update it after queue edits.
 */
class AutomixPlayer private constructor(
    private val decks: Decks,
    private val forwarding: SwappableDeckPlayer,
) : ForwardingPlayer(forwarding) {
    constructor(
        context: Context,
        replayGainSettings: ReplayGainAudioProcessor.Settings,
        playbackLooper: Looper? = null,
        onFormatChanged: (Boolean, Format?) -> Unit = { _, _ -> },
        onSinkCreated: (Boolean, PostAmpAudioOutputProvider) -> Unit = { _, _ -> },
        analysisCache: TrackAnalysisCache? = null,
    ) : this(createDecks(context, replayGainSettings, playbackLooper, onFormatChanged, onSinkCreated, analysisCache))

    private constructor(decks: Decks) : this(decks, SwappableDeckPlayer(decks.a))

    private class Decks(
        val context: Context,
        val a: ExoPlayer,
        val b: ExoPlayer,
        val factoryA: PlugRenderFactory,
        val factoryB: PlugRenderFactory,
        val capture: Capture,
        val analysisCache: TrackAnalysisCache?,
    )

    private class Capture {
        @Volatile
        var listener: ((Boolean, PcmTransientAudioProcessor.Transient) -> Unit)? = null
    }

    companion object {
        private const val TAG = "AutomixPlayer"
        private const val PREBUFFER_LEAD_MS = 5000L
        private const val TICK_MS = 25L
        private const val END_MARGIN_MS = 150L
        private const val VINYL_BRAKE_DURATION_MS = 1000L
        private const val VINYL_BRAKE_MIN_SPEED = 0.15f
        private const val ECHO_OUT_WET_MIX = 0.65f
        private const val ECHO_OUT_DRY_MUTE_START = 0.65f
        private const val ECHO_OUT_FEEDBACK = 0.45f
        private const val DEFAULT_ECHO_DELAY_MS = 375f
        private const val VOCAL_SWAP_ATTENUATION = 1f
        private const val FILTER_OUT_START_HZ = 20f
        private const val FILTER_OUT_END_HZ = 3500f
        private const val FILTER_IN_START_HZ = 300f
        private const val FILTER_IN_END_HZ = 20_000f
        private const val LOOP_TRANSITION_BEATS = 4
        private const val BEAT_SEEK_TIMING_LIMITATION =
            "Media3 seek is not sample-accurate; decoder and renderer seek latency may vary."

        private fun createDecks(
            context: Context,
            settings: ReplayGainAudioProcessor.Settings,
            playbackLooper: Looper?,
            onFormatChanged: (Boolean, Format?) -> Unit,
            onSinkCreated: (Boolean, PostAmpAudioOutputProvider) -> Unit,
            analysisCache: TrackAnalysisCache?,
        ): Decks {
            val app = context.applicationContext
            val capture = Capture()
            val sessionIdA = Util.generateAudioSessionId(app)
            check(sessionIdA > 0) { "Unable to generate an audio session ID for deck A" }
            var sessionIdB = C.AUDIO_SESSION_ID_UNSET
            repeat(8) {
                if (sessionIdB == C.AUDIO_SESSION_ID_UNSET || sessionIdB == sessionIdA) {
                    sessionIdB = Util.generateAudioSessionId(app)
                }
            }
            check(sessionIdB > 0 && sessionIdB != sessionIdA) {
                "Unable to generate independent audio session IDs for Automix decks"
            }
            fun factory(isA: Boolean) = PlugRenderFactory(
                app, { onFormatChanged(isA, it) }, { onSinkCreated(isA, it) }, pcmMixing = true,
                onPcmTransient = { capture.listener?.invoke(isA, it) },
            ).apply {
                replayGainAudioProcessor.applySettings(settings)
                setEnableHighResolutionPcmOutput(false)
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }
            val aFactory = factory(true)
            val bFactory = factory(false)
            fun player(factory: PlugRenderFactory): ExoPlayer {
                val builder = ExoPlayer.Builder(app, factory, PlugMediaSourceFactory(
                    DefaultDataSource.Factory(app),
                    PlugExtractorsFactory().apply {
                        setConstantBitrateSeekingEnabled(true)
                        setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
                    }
                ))
                    .setAudioAttributes(AudioAttributes.DEFAULT, false)
                    .setHandleAudioBecomingNoisy(false)
                    .setWakeMode(C.WAKE_MODE_LOCAL)
                if (playbackLooper != null) builder.setPlaybackLooper(playbackLooper)
                return builder.build()
            }
            val a = player(aFactory)
            val b = try {
                player(bFactory)
            } catch (e: RuntimeException) {
                a.release()
                throw e
            }
            try {
                a.setAudioSessionId(sessionIdA)
                b.setAudioSessionId(sessionIdB)
            } catch (e: RuntimeException) {
                b.release()
                a.release()
                throw e
            }
            return Decks(app, a, b, aFactory, bFactory, capture, analysisCache)
        }
    }

    private val handler = Handler(decks.a.applicationLooper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
    private val transition = DeckTransition()
    private var nextItem: MediaItem? = null
    internal val pendingNextMediaItem: MediaItem? get() = nextItem
    private var preparedItem: MediaItem? = null
    private data class QueueSnapshot(
        val items: List<MediaItem>,
        val shuffleOrder: List<Int>,
        val shuffleEnabled: Boolean,
        val repeatMode: Int,
        val nextIndex: Int,
    )
    private var preparedQueue: QueueSnapshot? = null
    private var enabled = false
    private var beatmatchEnabled = true
    private var transitionStyle = "auto"
    private var activeTransitionStyle = "auto"
    private var additionalEffects: Set<String> = emptySet()
    private var effectIntensity = 100
    private var loopTransitionBeats = LOOP_TRANSITION_BEATS
    private var fadeCurveIntensity = FadeCurveSettings.DEFAULT_INTENSITY_PERCENT
    private var activeEffectPlan = TransitionEffectPlan.create("smooth", emptySet())
    private var lastEffectIds: List<String> = emptyList()
    private val mutableTransitionExplanation = MutableStateFlow<TransitionAnalysisSnapshot?>(null)
    internal val transitionExplanation = mutableTransitionExplanation.asStateFlow()
    private val mutableRuntimeTelemetry = MutableStateFlow<TransitionRuntimeTelemetry?>(null)
    internal val runtimeTelemetry = mutableRuntimeTelemetry.asStateFlow()
    private var explanationQueue: QueueSnapshot? = null
    private val gainOwnershipRegistry = GainOwnershipRegistry()
    private var gainOwnershipGeneration: Long? = null
    private val observedExecutionEffects = mutableSetOf<AutoMixEffect>()
    private val beatLoopCoordinator = BeatLoopCoordinator(gainOwnershipRegistry)
    @Volatile
    private var beatEffectTelemetry: BeatEffectTelemetry? = null
    private var loopTransitionRegion: LoopRegion? = null
    private var loopTransitionReplayed = false
    private var loopTransitionFallbackReason: EffectDecisionReason? = null
    private var bpmMatchEvaluation: BpmMatchEvaluation? = null
    private var pendingBeatJumpTarget: Long? = null
    private var activeTransitionBeatmatched = false
    private var lastTransitionStyle: String? = null
    private var lastTransitionBeatmatched = false
    private var vinylBrakeTransition = false
    private var echoDelayMs = DEFAULT_ECHO_DELAY_MS
    private var activeFadeDurationMs = 0L
    private var transitionProgress = 0f
    private var loudnessMatchingEnabled = false
    private var agcEnabled = false
    private var bassBoost = BassBoostSettings.OFF
    private var energyMatchingEnabled = false
    private var energyPlanKey: Pair<String, String>? = null
    private var energyPlan: EnergyMatchPlan? = null
    private var loudnessPlan: LoudnessMatchPlan? = null
    private var loudnessPlanOutgoingIsDeckA = true
    private var transitionStartActiveFrames = 0L
    private var transitionStartStandbyFrames = 0L
    private var durationMs = 5000L
    private var automaticDuration = false
    private var maxSkipMs = 15_000L
    private var tremoloBpm = 120f
    private var userVolume = 1f
    private var unmuteVolume = 1f
    private var focusVolume = 1f
    private var activeGain = 1f
    private var standbyGain = 0f
    private var desiredPlay = false
    private var focusAllowed = true
    private var handleFocus = false
    private var focusRequested = false
    private var focusRequest: AudioFocusRequestCompat? = null
    private var released = false
    private var matchedSpeed = 1f
    var lastHandoffElapsedRealtimeMs: Long? = null
        private set
    private var pendingStartPositionMs: Long? = null
    private var pendingBeatmatchExplanation: EffectSelectionExplanation? = null
    private val samplesA = ArrayDeque<Long>()
    private val samplesB = ArrayDeque<Long>()
    private var samplesPeriodA: Any? = null
    private var samplesPeriodB: Any? = null
    var onActiveDeckChanged: ((ExoPlayer, ExoPlayer) -> Unit)? = null

    private val active: ExoPlayer get() = if (transition.activeDeck == DeckTransition.Deck.A) decks.a else decks.b
    private val standby: ExoPlayer get() = if (transition.standbyDeck == DeckTransition.Deck.A) decks.a else decks.b
    val activeExoPlayer: ExoPlayer get() = active
    internal fun deckAudioSessionIds(): Pair<Int, Int> = decks.a.audioSessionId to decks.b.audioSessionId
    val wantsPlayback: Boolean get() = desiredPlay
    private val activeProcessor: ReplayGainAudioProcessor
        get() = if (transition.activeDeck == DeckTransition.Deck.A)
            decks.factoryA.replayGainAudioProcessor else decks.factoryB.replayGainAudioProcessor
    val activeReplayGainProcessor: ReplayGainAudioProcessor get() = activeProcessor
    private val standbyProcessor: ReplayGainAudioProcessor
        get() = if (transition.standbyDeck == DeckTransition.Deck.A)
            decks.factoryA.replayGainAudioProcessor else decks.factoryB.replayGainAudioProcessor
    private val activeDjFilter: DjTransitionAudioProcessor
        get() = if (transition.activeDeck == DeckTransition.Deck.A)
            decks.factoryA.djTransitionAudioProcessor else decks.factoryB.djTransitionAudioProcessor
    private val standbyDjFilter: DjTransitionAudioProcessor
        get() = if (transition.standbyDeck == DeckTransition.Deck.A)
            decks.factoryA.djTransitionAudioProcessor else decks.factoryB.djTransitionAudioProcessor

    fun applyReplayGainSettings(settings: ReplayGainAudioProcessor.Settings): Boolean {
        assertThread()
        val first = decks.factoryA.replayGainAudioProcessor.applySettings(settings)
        val second = decks.factoryB.replayGainAudioProcessor.applySettings(settings)
        applyVolumes()
        return first && second
    }

    fun setReplayGainMode(mode: ReplayGainUtil.Mode, doNotNotifyListener: Boolean): Boolean {
        assertThread()
        val first = decks.factoryA.replayGainAudioProcessor.setMode(mode, doNotNotifyListener)
        val second = decks.factoryB.replayGainAudioProcessor.setMode(mode, doNotNotifyListener)
        applyVolumes()
        return first && second
    }

    fun addDeckAnalyticsListener(listenerA: androidx.media3.exoplayer.analytics.AnalyticsListener,
                                 listenerB: androidx.media3.exoplayer.analytics.AnalyticsListener) {
        assertThread()
        decks.a.addAnalyticsListener(listenerA)
        decks.b.addAnalyticsListener(listenerB)
    }

    private val audioManager = AudioManagerCompat.getAudioManager(decks.context)
    private val noisyManager = AudioBecomingNoisyManager(
        decks.context, decks.a.playbackLooper, decks.a.applicationLooper,
        { if (!released && desiredPlay) pause() }, Clock.DEFAULT
    )
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (!released && focusRequested) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> { focusAllowed = true; focusVolume = 1f }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    desiredPlay = false
                    focusAllowed = false
                    abandonFocus()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusAllowed = false
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusVolume = 0.2f
            }
            applyVolumes()
            syncPlayback()
        }
    }

    init {
        check(decks.a.applicationLooper == decks.b.applicationLooper)
        decks.capture.listener = { isA, event ->
            handler.post { recordTransient(isA, event) }
        }
        val listenerA = deckListener(decks.a)
        val listenerB = deckListener(decks.b)
        decks.a.addListener(listenerA)
        decks.b.addListener(listenerB)
        noisyManager.setEnabled(true)
        decks.b.volume = 0f
        applyVolumes()
        scope.launch {
            while (isActive) {
                tick()
                delay(if ((enabled && nextItem != null) ||
                    beatLoopCoordinator.ownsTimingResources
                ) TICK_MS else 250L)
            }
        }
    }

    private fun assertThread() {
        check(Looper.myLooper() == applicationLooper) { "AutomixPlayer must be accessed on its application looper" }
        check(!released) { "AutomixPlayer has been released" }
    }

    val isCrossfading: Boolean get() = transition.phase == DeckTransition.Phase.FADING
    internal fun currentTransitionExplanation(): TransitionAnalysisSnapshot? {
        val snapshot = mutableTransitionExplanation.value ?: return null
        if (explanationQueue != queueSnapshot() ||
            snapshot.analyzer != decks.analysisCache?.selectedAnalyzer
        ) {
            mutableTransitionExplanation.value = null
            explanationQueue = null
            return null
        }
        val expectedIndex = if (snapshot.status == TransitionExecutionStatus.ACTIVE)
            snapshot.outgoingIndex else snapshot.incomingIndex
        val expectedId = if (snapshot.status == TransitionExecutionStatus.ACTIVE)
            snapshot.outgoingId else snapshot.incomingId
        if (active.currentMediaItemIndex != expectedIndex || active.currentMediaItem?.mediaId != expectedId) {
            mutableTransitionExplanation.value = null
            explanationQueue = null
            return null
        }
        return snapshot
    }
    val isAutomixEnabled: Boolean get() = enabled
    val isBeatmatchEnabled: Boolean get() = beatmatchEnabled
    val transitionEffect: String?
        get() = if (isCrossfading) activeTransitionStyle else lastTransitionStyle
    val transitionEffects: List<String>
        get() = if (isCrossfading) activeEffectPlan.effects.map { it.effect.id } else lastEffectIds
    val isTransitionBeatmatched: Boolean
        get() = if (isCrossfading) activeTransitionBeatmatched else lastTransitionBeatmatched
    val upcomingMediaItem: MediaItem? get() = nextItem?.takeIf { enabled }
    val upcomingStandbyPlayer: ExoPlayer?
        get() = upcomingMediaItem?.takeIf { standby.currentMediaItem == it }?.let { standby }
    val upcomingPlaybackSpeed: Float
        get() = upcomingStandbyPlayer?.playbackParameters?.speed ?: matchedSpeed
    internal val currentBeatEffectTelemetry: BeatEffectTelemetry?
        get() = beatEffectTelemetry

    fun jumpBeats(beatCount: Int, direction: Int): BeatEffectTelemetry {
        assertThread()
        require(beatCount in BeatLoopIntervals.supported)
        require(direction == -1 || direction == 1)
        val metadata = audioMetadata(active)
        val grid = beatGrid(metadata, active)
        val current = active.currentPosition
        val position = grid?.quantize(current.toDouble(), BeatQuantization.NEAREST)
        val duration = active.duration
        val target = grid?.jumpTarget(current.toDouble(), beatCount, direction, duration)
        val reason = when {
            transition.phase != DeckTransition.Phase.IDLE ->
                "Beat Jump is unavailable while a transition is being prepared or executed."
            !beatLoopCoordinator.canStartExclusiveOperation() ->
                "Beat Jump is incompatible with an active Beat Loop."
            grid == null || position == null -> "A confident beat grid is unavailable."
            target == null || duration == C.TIME_UNSET ->
                "The requested beat position is outside the track."
            else -> null
        }
        if (reason != null) return publishBeatTelemetry(
            BeatEffectTelemetry(
                AutoMixEffect.BEAT_JUMP.id, true, true, false, EffectLifecycleState.REJECTED,
                grid?.confidence?.value, reason = reason,
                timingLimitation = BEAT_SEEK_TIMING_LIMITATION,
            )
        )
        val generation = TransitionGeneration.next()
        val claim = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.BEAT_JUMP),
            generation = generation,
        ).single()
        if (claim.state != EffectLifecycleState.SELECTED) return publishBeatTelemetry(
            BeatEffectTelemetry(
                AutoMixEffect.BEAT_JUMP.id, true, true, false, claim.state,
                grid?.confidence?.value, reason = claim.reason,
                timingLimitation = BEAT_SEEK_TIMING_LIMITATION,
            )
        )
        gainOwnershipRegistry.claim(AutoMixEffect.BEAT_JUMP, generation)
        return try {
            val targetMs = target!!.timeMs.toLong()
            pendingBeatJumpTarget = targetMs
            val telemetry = publishBeatTelemetry(
                BeatEffectTelemetry(
                    AutoMixEffect.BEAT_JUMP.id, true, true, true, EffectLifecycleState.EXECUTING,
                    grid!!.confidence.value, targetMs,
                    beatIndex = target.beatIndex,
                    reason = "Media3 seek requested at beat index ${target.beatIndex}; awaiting position discontinuity.",
                    timingLimitation = BEAT_SEEK_TIMING_LIMITATION,
                )
            )
            active.seekTo(targetMs)
            telemetry
        } finally {
            gainOwnershipRegistry.releaseGeneration(generation)
        }
    }

    fun startBeatLoop(beatCount: Int): BeatEffectTelemetry {
        assertThread()
        require(beatCount in BeatLoopIntervals.supported)
        val metadata = audioMetadata(active)
        val grid = beatGrid(metadata, active)
        val region = grid?.loopRegion(
            active.currentPosition.toDouble(), beatCount, active.duration
        )
        val reason = when {
            transition.phase != DeckTransition.Phase.IDLE ->
                "Beat Loop is unavailable while a transition is being prepared or executed."
            active.currentMediaItem == null -> "No active track."
            active.duration == C.TIME_UNSET || active.duration <= 0 -> "Track duration is unavailable."
            !beatLoopCoordinator.canStartExclusiveOperation() ->
                "A Beat Loop is already active or paused."
            region == null -> "A valid, confident beat-grid loop is unavailable within the track."
            else -> null
        }
        if (reason != null) return publishBeatTelemetry(
            BeatEffectTelemetry(
                AutoMixEffect.BEAT_LOOP.id, true, true, false, EffectLifecycleState.REJECTED,
                grid?.confidence?.value, reason = reason,
                timingLimitation = LoopBoundaryScheduler.TIMING_LIMITATION,
            )
        )
        val generation = TransitionGeneration.next()
        val selection = AutoMixEffectArbitrator.evaluate(
            listOf(AutoMixEffect.BEAT_LOOP), generation = generation
        ).single()
        if (selection.state != EffectLifecycleState.SELECTED) return publishBeatTelemetry(
            BeatEffectTelemetry(
                AutoMixEffect.BEAT_LOOP.id, true, true, false, selection.state,
                grid?.confidence?.value, reason = selection.reason,
                timingLimitation = LoopBoundaryScheduler.TIMING_LIMITATION,
            )
        )
        val started = beatLoopCoordinator.activate(checkNotNull(region), active.duration, generation)
        if (!started.accepted || started.state.lifecycle != LoopLifecycleState.ACTIVE) {
            return publishBeatTelemetry(
                BeatEffectTelemetry(
                    AutoMixEffect.BEAT_LOOP.id, true, true, false, EffectLifecycleState.REJECTED,
                    grid?.confidence?.value,
                    reason = started.state.reason ?: "A Beat Loop already owns the timing resources.",
                    timingLimitation = started.timingAccuracy,
                )
            )
        }
        active.seekTo(checkNotNull(region).startMs)
        return publishBeatTelemetry(
            BeatEffectTelemetry(
                AutoMixEffect.BEAT_LOOP.id, true, true, true, EffectLifecycleState.PREPARED,
                grid?.confidence?.value, beatIndex = region.startBeatIndex,
                loopStartMs = region.startMs, loopEndMs = region.endMs,
                reason = "Loop armed for ${region.beatCount} beats; Media3 seek boundaries are approximate.",
                timingLimitation = started.timingAccuracy,
            )
        )
    }

    fun clearBeatLoop(reason: String = "Beat Loop stopped."): BeatEffectTelemetry {
        return terminateBeatLoop(reason, failed = false)
    }

    private fun terminateBeatLoop(reason: String, failed: Boolean): BeatEffectTelemetry {
        assertThread()
        val previous = beatLoopCoordinator.state()
        val result = if (failed) {
            beatLoopCoordinator.fail(reason, previous.generation)
        } else {
            beatLoopCoordinator.cancel(reason, previous.generation)
        }
        return publishBeatTelemetry(
            BeatEffectTelemetry(
                AutoMixEffect.BEAT_LOOP.id, true, true, previous.region != null,
                when {
                    failed && result.accepted -> EffectLifecycleState.FAILED
                    result.accepted || !previous.ownsTimingResources -> EffectLifecycleState.COMPLETED
                    else -> EffectLifecycleState.FAILED
                },
                beatEffectTelemetry?.gridConfidence,
                loopStartMs = previous.region?.startMs,
                loopEndMs = previous.region?.endMs,
                reason = result.state.reason ?: reason,
                timingLimitation = result.timingAccuracy,
            )
        )
    }

    private fun beatGrid(
        metadata: ResolvedTrackAudioMetadata,
        player: ExoPlayer,
    ): BeatGrid? {
        val existing = metadata.beatGrid
            ?: BeatPhaseAligner.estimate(metadata.bpm, transientSamples(player).toList())
            ?: return null
        if (!existing.confidence.isFinite() || existing.confidence !in 0f..1f ||
            !existing.beatPeriodMs.isFinite() || existing.beatPeriodMs <= 0 ||
            !existing.phaseOffsetMs.isFinite()
        ) return null
        return BeatGrid(
            BeatGridConfidence(existing.confidence),
            nominalPeriodMs = existing.beatPeriodMs,
            phaseOffsetMs = existing.phaseOffsetMs,
        )
    }

    private fun transientSamples(player: ExoPlayer): ArrayDeque<Long> =
        if (player === decks.a) samplesA else samplesB

    private fun publishBeatTelemetry(value: BeatEffectTelemetry): BeatEffectTelemetry {
        beatEffectTelemetry = value
        return value
    }

    fun setNextMediaItem(mediaItem: MediaItem?) {
        assertThread()
        if (nextItem == mediaItem &&
            (preparedQueue == null || preparedQueue == queueSnapshot())
        ) return
        cancelTransition()
        nextItem = mediaItem
    }

    private fun queueSnapshot(): QueueSnapshot {
        val order = active.shuffleOrder
        val shuffled = ArrayList<Int>(order.length)
        var index = order.firstIndex
        while (index != C.INDEX_UNSET && shuffled.size < order.length) {
            shuffled.add(index)
            index = order.getNextIndex(index)
        }
        return QueueSnapshot(
            (0 until active.mediaItemCount).map(active::getMediaItemAt),
            shuffled, active.shuffleModeEnabled, active.repeatMode, active.nextMediaItemIndex
        )
    }

    fun updateAutomixConfig(
        enabled: Boolean,
        beatmatchEnabled: Boolean,
        durationSec: Int,
        transitionStyle: String = "auto",
        automaticDuration: Boolean = false,
        maxSkipSeconds: Int = 15,
        additionalEffects: Set<String> = emptySet(),
        effectIntensityPercent: Int = 100,
        loopTransitionBeats: Int = LOOP_TRANSITION_BEATS,
        fadeCurveIntensityPercent: Int = FadeCurveSettings.DEFAULT_INTENSITY_PERCENT,
        loudnessMatchingEnabled: Boolean = false,
        agcEnabled: Boolean = false,
        bassBoost: BassBoostSettings = BassBoostSettings.OFF,
        energyMatchingEnabled: Boolean = false,
    ) {
        assertThread()
        require(fadeCurveIntensityPercent in 0..100)
        require(loopTransitionBeats in BeatLoopIntervals.supported)
        require(durationSec in 1..30)
        require(maxSkipSeconds in 0..30)
        require(additionalEffects.all { it in TransitionEffectPlan.selectableIds })
        require(effectIntensityPercent in 0..100)
        require(transitionStyle in setOf(
            "auto", "smooth", "vinyl_brake", "bass_swap", "filter_sweep", "echo_out",
            "high_pass_intro", "reverb_tail", "tremolo_fade", "fade", "rise", "loop_transition",
            "exponential_fade", "s_curve_fade"
        ))
        if (this.enabled != enabled || this.beatmatchEnabled != beatmatchEnabled ||
            durationMs != durationSec * 1000L || this.transitionStyle != transitionStyle ||
            this.automaticDuration != automaticDuration || maxSkipMs != maxSkipSeconds * 1000L ||
            this.additionalEffects !=             additionalEffects || effectIntensity != effectIntensityPercent ||
                        this.loopTransitionBeats != loopTransitionBeats ||
                        fadeCurveIntensity != fadeCurveIntensityPercent ||
                        this.loudnessMatchingEnabled != loudnessMatchingEnabled ||
                        this.agcEnabled != agcEnabled ||
                        this.energyMatchingEnabled != energyMatchingEnabled
                    ) cancelTransition()
                    this.loudnessMatchingEnabled = loudnessMatchingEnabled
                    this.agcEnabled = agcEnabled
                    this.bassBoost = bassBoost
                    this.energyMatchingEnabled = energyMatchingEnabled
                    this.fadeCurveIntensity = fadeCurveIntensityPercent
                    this.loopTransitionBeats = loopTransitionBeats
        this.enabled = enabled
        this.beatmatchEnabled = beatmatchEnabled
        durationMs = durationSec * 1000L
        this.transitionStyle = transitionStyle
        this.automaticDuration = automaticDuration
        maxSkipMs = maxSkipSeconds * 1000L
        this.additionalEffects = additionalEffects.toSet()
        effectIntensity = effectIntensityPercent
        applyVolumes()
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        assertThread()
        require(!handleAudioFocus || audioAttributes.usage == C.USAGE_MEDIA ||
            audioAttributes.usage == C.USAGE_GAME) {
            "Automatic audio focus requires media or game usage"
        }
        abandonFocus()
        handleFocus = handleAudioFocus
        focusRequest = if (handleAudioFocus) AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .build() else null
        decks.a.setAudioAttributes(audioAttributes, false)
        decks.b.setAudioAttributes(audioAttributes, false)
        focusAllowed = !desiredPlay || requestFocus()
        if (!focusAllowed) {
            desiredPlay = false
            Log.w(TAG, "Audio focus request denied after changing audio attributes")
        }
        syncPlayback()
    }

    override fun setVolume(volume: Float) {
        assertThread()
        require(volume.isFinite() && volume in 0f..1f)
        userVolume = volume
        if (volume > 0f) unmuteVolume = volume
        forwarding.setExposedVolume(volume)
        applyVolumes()
    }

    override fun getVolume(): Float = userVolume
    override fun mute() = setVolume(0f)
    override fun unmute() = setVolume(unmuteVolume)

    override fun play() = setPlayWhenReady(true)
    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        assertThread()
        desiredPlay = playWhenReady
        if (playWhenReady) {
            val waitingForFocus = focusRequested && !focusAllowed
            if (!waitingForFocus) focusAllowed = requestFocus()
            if (!focusAllowed && !waitingForFocus) {
                desiredPlay = false
                Log.w(TAG, "Audio focus request denied")
            }
        } else {
            abandonFocus()
            focusAllowed = true
        }
        syncPlayback()
    }

    private fun requestFocus(): Boolean {
        if (!handleFocus || focusRequested) return !handleFocus || focusAllowed
        val request = checkNotNull(focusRequest)
        val granted = AudioManagerCompat.requestAudioFocus(audioManager, request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequested = granted
        return granted
    }

    private fun abandonFocus() {
        if (focusRequested) {
            focusRequested = false
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, checkNotNull(focusRequest))
        }
        focusVolume = 1f
        applyVolumes()
    }

    private fun syncPlayback() {
        val playing = desiredPlay && focusAllowed
        if (transition.phase == DeckTransition.Phase.FADING) {
            if (!playing) transition.pause(SystemClock.elapsedRealtime())
            standby.playWhenReady = playing
        }
        active.playWhenReady = playing
    }

    override fun prepare() {
        assertThread()
        active.prepare()
    }

    override fun stop() {
        assertThread()
        desiredPlay = false
        cancelTransition()
        active.stop()
        abandonFocus()
    }

    override fun release() {
        if (released) return
        assertThread()
        cancelTransition()
        released = true
        noisyManager.setEnabled(false)
        decks.capture.listener = null
        scope.cancel()
        abandonFocus()
        standby.release()
        forwarding.release()
    }

    override fun seekTo(positionMs: Long) {
        assertThread()
        cancelTransition()
        super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        assertThread()
        cancelTransition()
        if (mediaItemIndex != active.currentMediaItemIndex) {
            mutableTransitionExplanation.value = null
            explanationQueue = null
            nextItem = null
        }
        super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekToDefaultPosition() {
        assertThread()
        cancelTransition()
        super.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        assertThread()
        cancelTransition()
        if (mediaItemIndex != active.currentMediaItemIndex) {
            mutableTransitionExplanation.value = null
            explanationQueue = null
            nextItem = null
        }
        super.seekToDefaultPosition(mediaItemIndex)
    }

    override fun seekBack() { assertThread(); cancelTransition(); super.seekBack() }
    override fun seekForward() { assertThread(); cancelTransition(); super.seekForward() }
    override fun seekToNext() { invalidateNext(); super.seekToNext() }
    override fun seekToPrevious() { invalidateNext(); super.seekToPrevious() }
    override fun seekToNextMediaItem() { invalidateNext(); super.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() { invalidateNext(); super.seekToPreviousMediaItem() }

    override fun setRepeatMode(repeatMode: Int) {
        assertThread()
        cancelTransition()
        mutableTransitionExplanation.value = null
        explanationQueue = null
        nextItem = null
        super.setRepeatMode(repeatMode)
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        assertThread()
        cancelTransition()
        mutableTransitionExplanation.value = null
        explanationQueue = null
        nextItem = null
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    private fun invalidateNext() {
        assertThread()
        cancelTransition()
        mutableTransitionExplanation.value = null
        explanationQueue = null
        nextItem = null
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        invalidateNext(); super.setMediaItems(mediaItems)
    }
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        invalidateNext(); super.setMediaItems(mediaItems, resetPosition)
    }
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        invalidateNext(); super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }
    override fun setMediaItem(mediaItem: MediaItem) {
        invalidateNext(); super.setMediaItem(mediaItem)
    }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        invalidateNext(); super.setMediaItem(mediaItem, startPositionMs)
    }
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        invalidateNext(); super.setMediaItem(mediaItem, resetPosition)
    }
    override fun addMediaItem(mediaItem: MediaItem) {
        invalidateNext(); super.addMediaItem(mediaItem)
    }
    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        invalidateNext(); super.addMediaItem(index, mediaItem)
    }
    override fun addMediaItems(mediaItems: List<MediaItem>) {
        invalidateNext(); super.addMediaItems(mediaItems)
    }
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        invalidateNext(); super.addMediaItems(index, mediaItems)
    }
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        invalidateNext(); super.moveMediaItem(currentIndex, newIndex)
    }
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        invalidateNext(); super.moveMediaItems(fromIndex, toIndex, newIndex)
    }
    override fun removeMediaItem(index: Int) {
        invalidateNext(); super.removeMediaItem(index)
    }
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        invalidateNext(); super.removeMediaItems(fromIndex, toIndex)
    }
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        invalidateNext(); super.replaceMediaItem(index, mediaItem)
    }
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) {
        invalidateNext(); super.replaceMediaItems(fromIndex, toIndex, mediaItems)
    }
    override fun clearMediaItems() { invalidateNext(); super.clearMediaItems() }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        assertThread()
        cancelTransition()
        super.setPlaybackParameters(playbackParameters)
    }
    override fun setPlaybackSpeed(speed: Float) {
        assertThread()
        cancelTransition()
        super.setPlaybackSpeed(speed)
    }

    /** Bass Boost is share-weighted by deck gain during overlap so two boosted tracks never stack their lows. */
    private fun applyBassBoost() {
        val activeIsA = transition.activeDeck == DeckTransition.Deck.A
        val fading = transition.phase == DeckTransition.Phase.FADING
        val total = activeGain + standbyGain
        val activeMix = when {
            !enabled || !bassBoost.enabled -> 0f
            fading && total > 1e-4f -> (activeGain / total).coerceIn(0f, 1f)
            else -> 1f
        }
        val standbyMix = if (!enabled || !bassBoost.enabled) 0f
        else if (fading && total > 1e-4f) (standbyGain / total).coerceIn(0f, 1f) else 0f
        for (factory in listOf(decks.factoryA, decks.factoryB)) {
            factory.bassBoostAudioProcessor.configureBoost(bassBoost.gainDb, bassBoost.frequencyHz)
        }
        val activeFactory = if (activeIsA) decks.factoryA else decks.factoryB
        val standbyFactory = if (activeIsA) decks.factoryB else decks.factoryA
        activeFactory.bassBoostAudioProcessor.setMix(activeMix)
        standbyFactory.bassBoostAudioProcessor.setMix(standbyMix)
    }

    private fun applyVolumes() {
        for (factory in listOf(decks.factoryA, decks.factoryB)) {
            factory.agcAudioProcessor.setEnabled(
                enabled && agcEnabled && !loudnessMatchingEnabled &&
                    factory.replayGainAudioProcessor.mode == ReplayGainUtil.Mode.None
            )
        }
        applyBassBoost()
        val outputGain = if (transition.phase == DeckTransition.Phase.FADING) {
            AutomixMath.overlapHeadroom(transitionProgress)
        } else 1f
        decks.factoryA.djTransitionAudioProcessor.setOutputGain(outputGain)
        decks.factoryB.djTransitionAudioProcessor.setOutputGain(outputGain)
        var activeTrim = 1f
        var standbyTrim = 1f
        val plan = loudnessPlan
        if (plan != null && transition.phase == DeckTransition.Phase.FADING) {
            val activeIsOutgoing = (transition.activeDeck == DeckTransition.Deck.A) == loudnessPlanOutgoingIsDeckA
            val outgoingTrim = LoudnessMatcher.outgoingTrim(plan.outgoingGain, transitionProgress)
            val incomingTrim = LoudnessMatcher.incomingTrim(plan.incomingGain, transitionProgress)
            activeTrim = if (activeIsOutgoing) outgoingTrim else incomingTrim
            standbyTrim = if (activeIsOutgoing) incomingTrim else outgoingTrim
        }
        active.volume = userVolume * focusVolume * activeGain * activeTrim
        standby.volume = userVolume * focusVolume * standbyGain * standbyTrim
    }

    private fun recordTransient(isA: Boolean, event: PcmTransientAudioProcessor.Transient) {
        if (released || event.periodUid == null) return
        val deck = if (isA) decks.a else decks.b
        val timeline = deck.currentTimeline
        val period = deck.currentPeriodIndex
        if (period == C.INDEX_UNSET || period >= timeline.periodCount ||
            timeline.getUidOfPeriod(period) != event.periodUid
        ) return
        val samples = if (isA) samplesA else samplesB
        val oldPeriod = if (isA) samplesPeriodA else samplesPeriodB
        if (oldPeriod != event.periodUid) {
            samples.clear()
            if (isA) samplesPeriodA = event.periodUid else samplesPeriodB = event.periodUid
        }
        if (samples.size == 32) samples.removeFirst()
        samples.addLast(event.positionUs)
    }

    private fun audioMetadata(player: ExoPlayer): ResolvedTrackAudioMetadata {
        val key = player.currentMediaItem?.let(AnalysisKey::from)
        val cached = key?.let { decks.analysisCache?.peekSelected(it) }
        val metadata = TrackAudioMetadataResolver.resolvePlayer(player, cached)
        val edges = cached?.edgeAnalysis ?: return metadata
        val edgeGrid = when {
            player.currentPosition in 0..edges.startWindowEndMs -> edges.startGrid
            player.currentPosition in edges.endWindowStartMs..edges.durationMs -> edges.endGrid
            else -> null
        }
        val bpm = metadata.bpm
        val usableGrid = edgeGrid?.takeIf {
            it.confidence >= 0.8f && it.beatPeriodMs.isFinite() && it.beatPeriodMs > 0 &&
                (bpm == null || kotlin.math.abs(60_000.0 / it.beatPeriodMs / bpm - 1.0) <= 0.03)
        }
        return if (usableGrid != null) metadata.copy(
            bpm = (60_000.0 / usableGrid.beatPeriodMs).toFloat(),
            beatGrid = usableGrid,
        ) else metadata
    }

    private fun cancelTransition() {
        if (isCrossfading) {
            mutableTransitionExplanation.value = null
            explanationQueue = null
        }
        if (beatLoopCoordinator.ownsTimingResources) {
            clearBeatLoop("Playback operation cancelled the Beat Loop.")
        }
        releaseGainOwnership()
        loudnessPlan = null
        observedExecutionEffects.clear()
        mutableRuntimeTelemetry.value = null
        decks.factoryA.djTransitionAudioProcessor.bypass()
        decks.factoryB.djTransitionAudioProcessor.bypass()
        if (vinylBrakeTransition) {
            active.playbackParameters = PlaybackParameters(1f, active.playbackParameters.pitch)
        }
        transition.cancel()
        activeEffectPlan = TransitionEffectPlan.create("smooth", emptySet())
        vinylBrakeTransition = false
        activeTransitionBeatmatched = false
        activeFadeDurationMs = 0L
        transitionProgress = 0f
        pendingStartPositionMs = null
        pendingBeatmatchExplanation = null
        pendingBeatJumpTarget = null
        loopTransitionRegion = null
        loopTransitionReplayed = false
        loopTransitionFallbackReason = null
        bpmMatchEvaluation = null
        preparedItem = null
        preparedQueue = null
        standby.playWhenReady = false
        standby.stop()
        standby.shuffleOrder = ShuffleOrder.DefaultShuffleOrder(standby.mediaItemCount)
        standby.clearMediaItems()
        standby.playbackParameters = PlaybackParameters.DEFAULT
        matchedSpeed = 1f
        activeGain = 1f
        standbyGain = 0f
        applyVolumes()
        active.playWhenReady = desiredPlay && focusAllowed
    }

    private fun deckListener(deck: ExoPlayer) = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val target = pendingBeatJumpTarget
            if (!released && deck === active && target != null &&
                reason == Player.DISCONTINUITY_REASON_SEEK
            ) {
                val succeeded = newPosition.positionMs == target
                beatEffectTelemetry?.takeIf { it.effectId == AutoMixEffect.BEAT_JUMP.id }?.let {
                    publishBeatTelemetry(it.copy(
                        state = if (succeeded) EffectLifecycleState.COMPLETED else EffectLifecycleState.FAILED,
                        beatPositionMs = newPosition.positionMs,
                        reason = if (succeeded) {
                            "Media3 reported the requested seek position; device-output audio is not observable."
                        } else {
                            "Media3 reported ${newPosition.positionMs} ms instead of the requested $target ms."
                        },
                    ))
                }
                pendingBeatJumpTarget = null
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (released) return
            if (beatLoopCoordinator.ownsTimingResources) {
                terminateBeatLoop("Playback error terminated the Beat Loop.", failed = true)
            }
            if (deck === standby) {
                Log.e(TAG, "Standby deck failed; cancelling crossfade", error)
                cancelTransition()
                nextItem = null
            } else {
                desiredPlay = false
                cancelTransition()
                nextItem = null
                abandonFocus()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val snapshot = mutableTransitionExplanation.value
            if (!released && deck === active && snapshot?.status == TransitionExecutionStatus.COMPLETED &&
                (deck.currentMediaItemIndex != snapshot.incomingIndex || mediaItem?.mediaId != snapshot.incomingId)
            ) {
                mutableTransitionExplanation.value = null
                explanationQueue = null
            }
            if (!released && deck === active && preparedItem != null) {
                cancelTransition()
                nextItem = null
            }
        }
    }

    private fun prepareStandby(item: MediaItem) {
        if (active.repeatMode == Player.REPEAT_MODE_ONE) return
        val items = (0 until active.mediaItemCount).map(active::getMediaItemAt).toMutableList()
        val index = active.nextMediaItemIndex
        if (index == C.INDEX_UNSET) {
            Log.w(TAG, "Requested next item is no longer in the active queue")
            nextItem = null
            return
        } else if (items[index] != item) {
            Log.w(TAG, "Requested next item differs from the active queue; skipping crossfade")
            nextItem = null
            return
        }
        transition.beginPreparation()
        preparedItem = item
        preparedQueue = queueSnapshot()
        standbyProcessor.applySettings(activeProcessor.snapshotSettings())
        val edges = AnalysisKey.from(item)?.let { decks.analysisCache?.peekSelected(it)?.edgeAnalysis }
        val startPosition = TrackEdgeAnalyzer.startPosition(edges, maxSkipMs)
        standby.setMediaItems(items, index, startPosition)
        val activeOrder = active.shuffleOrder
        standby.shuffleOrder = if (activeOrder is CircularShuffleOrder) activeOrder.copy() else {
            val indices = IntArray(activeOrder.length)
            var next = activeOrder.firstIndex
            for (i in indices.indices) {
                indices[i] = next
                next = activeOrder.getNextIndex(next)
            }
            ShuffleOrder.DefaultShuffleOrder(indices, Random.nextLong())
        }
        standby.repeatMode = active.repeatMode
        standby.shuffleModeEnabled = active.shuffleModeEnabled
        standby.pauseAtEndOfMediaItems = active.pauseAtEndOfMediaItems
        standby.trackSelectionParameters = active.trackSelectionParameters
        standby.playWhenReady = false
        standby.volume = 0f
        standby.prepare()
    }

    private fun tick() {
        if (released) return
        val loopState = beatLoopCoordinator.state()
        if (loopState.ownsTimingResources) {
            val result = beatLoopCoordinator.onPosition(
                active.currentPosition, active.isPlaying, loopState.generation
            )
            val lifecycle = when (result.state.lifecycle) {
                LoopLifecycleState.ACTIVE -> if (result.state.completedIterations > 0) {
                    EffectLifecycleState.EXECUTING
                } else EffectLifecycleState.PREPARED
                LoopLifecycleState.PAUSED -> EffectLifecycleState.PREPARED
                else -> EffectLifecycleState.COMPLETED
            }
            beatEffectTelemetry?.let { previous ->
                publishBeatTelemetry(previous.copy(
                    state = lifecycle,
                    beatPositionMs = result.seekPositionMs,
                    reason = if (result.seekPositionMs != null) {
                        "Loop boundary reached; seeking to the matching loop position."
                    } else previous.reason,
                ))
            }
            result.seekPositionMs?.let(active::seekTo)
            return
        }
        if (!enabled || nextItem == null) return
        if (transition.phase == DeckTransition.Phase.FADING) {
            if (active.pauseAtEndOfMediaItems) {
                cancelTransition()
                return
            }
            if (active.audioSessionId == C.AUDIO_SESSION_ID_UNSET ||
                standby.audioSessionId == C.AUDIO_SESSION_ID_UNSET ||
                active.audioSessionId == standby.audioSessionId) {
                Log.e(TAG, "Automix decks require independent audio sessions")
                cancelTransition()
                nextItem = null
                return
            }
            if (active.playbackState == Player.STATE_ENDED || standby.playbackState == Player.STATE_ENDED) {
                Log.w(TAG, "A deck ended before the crossfade completed")
                cancelTransition()
                nextItem = null
                return
            }
            if (desiredPlay && focusAllowed) {
                active.playWhenReady = standby.playbackState == Player.STATE_READY
                standby.playWhenReady = active.playbackState == Player.STATE_READY
            }
            if (!active.isPlaying || !standby.isPlaying) {
                transition.pause(SystemClock.elapsedRealtime())
                return
            }
            val now = SystemClock.elapsedRealtime()
            transition.resume(now)
            val progress = transition.progress(now)
            transitionProgress = progress
            applyEffectPlan(progress)
            if (vinylBrakeTransition) {
                val brakeDuration = minOf(VINYL_BRAKE_DURATION_MS, activeFadeDurationMs)
                    .coerceAtLeast(800L)
                val brakeStart = ((activeFadeDurationMs - brakeDuration).toFloat() /
                    activeFadeDurationMs).coerceIn(0f, 1f)
                val brakeProgress = ((progress - brakeStart) / (1f - brakeStart))
                    .coerceIn(0f, 1f)
                val outgoingSpeed = 1f + (VINYL_BRAKE_MIN_SPEED - 1f) * brakeProgress
                activeGain = 1f - brakeProgress
                standbyGain = 0f
                active.playbackParameters = PlaybackParameters(
                    outgoingSpeed, active.playbackParameters.pitch
                )
                standby.playbackParameters = PlaybackParameters(
                    1f, active.playbackParameters.pitch
                )
            } else {
                val gains = when (activeTransitionStyle) {
                    "fade" -> AutomixMath.Volumes(1f - progress, progress)
                    "exponential_fade" -> complementaryFadeGains(
                        VolumeCurve.Exponential(FadeCurveSettings.exponentialStrength(fadeCurveIntensity)),
                        progress
                    )
                    "s_curve_fade" -> complementaryFadeGains(
                        VolumeCurve.SCurve(FadeCurveSettings.sCurveSteepness(fadeCurveIntensity)),
                        progress
                    )
                    "rise" -> AutomixMath.equalPower(progress * progress)
                    else -> AutomixMath.equalPower(progress)
                }
                activeGain = gains.outgoing
                standbyGain = gains.incoming
                if (activeTransitionStyle == "tremolo_fade") {
                    val seconds = progress * activeFadeDurationMs / 1000f
                    val modulation = (0.75 + 0.25 * kotlin.math.cos(
                        seconds * tremoloBpm / 60.0 * 2.0 * kotlin.math.PI
                    )).toFloat()
                    activeGain *= modulation
                }
                standby.playbackParameters = PlaybackParameters(
                    AutomixMath.rampSpeed(matchedSpeed, progress), active.playbackParameters.pitch
                )
            }
            applyVolumes()
            updateEffectExecutionTelemetry()
            if (progress >= 1f) handoff(now)
            return
        }
        if (beatLoopCoordinator.ownsTimingResources) return
        if (!active.isPlaying || active.duration == C.TIME_UNSET ||
            active.playbackParameters.speed != 1f || active.isPlayingAd ||
            active.pauseAtEndOfMediaItems
        ) return
        val remainingToEnd = active.duration - active.currentPosition
        val edges = active.currentMediaItem?.let(AnalysisKey::from)
            ?.let { decks.analysisCache?.peekSelected(it)?.edgeAnalysis }
        val candidateEnd = TrackEdgeAnalyzer.endPosition(edges, active.duration, maxSkipMs)
        val plannedEnd = if (candidateEnd - active.currentPosition > END_MARGIN_MS) {
            candidateEnd
        } else active.duration
        var transitionEnd = plannedEnd
        var remaining = transitionEnd - active.currentPosition
        if (remaining <= 0) return
        if (transition.phase == DeckTransition.Phase.IDLE &&
            remainingToEnd <= maxOf((if (automaticDuration) 30_000L else durationMs) +
                maxSkipMs + PREBUFFER_LEAD_MS, 15_000L)
        ) {
            prepareStandby(nextItem ?: return)
        }
        if (transition.phase == DeckTransition.Phase.PREPARING &&
            standby.playbackState == Player.STATE_READY
        ) transition.markReady()
        if (transition.phase != DeckTransition.Phase.READY) return

        val outgoing = audioMetadata(active)
        val incoming = audioMetadata(standby)
        val outBpm = outgoing.bpm
        val inBpm = incoming.bpm
        val outgoingSamples = if (transition.activeDeck == DeckTransition.Deck.A) samplesA else samplesB
        val incomingSamples = if (transition.standbyDeck == DeckTransition.Deck.A) samplesA else samplesB
        val outgoingGrid = outgoing.beatGrid?.takeIf { it.confidence >= 0.8f }
            ?: BeatPhaseAligner.estimate(outBpm, outgoingSamples.toList())
        val incomingGrid = incoming.beatGrid?.takeIf { it.confidence >= 0.8f }
            ?: BeatPhaseAligner.estimate(inBpm, incomingSamples.toList())
        bpmMatchEvaluation = BpmMatchEvaluator.evaluate(
            outBpm, inBpm, outgoingGrid, incomingGrid,
            active.currentPosition.toDouble(), standby.currentPosition.toDouble(),
        )
        val phaseAlignment = bpmMatchEvaluation?.selected?.alignment
            ?: BeatPhaseAligner.align(
                outBpm, inBpm, outgoingGrid, incomingGrid,
                active.currentPosition.toDouble(), standby.currentPosition.toDouble()
            )
        val automaticDecision = if (transitionStyle == "auto") {
            AutomixEffectSelector.decide(
                outBpm, inBpm, phaseAlignment != null,
                outgoing.vocalEnergy, incoming.vocalEnergy
            )
        } else null
        activeTransitionStyle = automaticDecision?.style ?: transitionStyle
        vinylBrakeTransition = activeTransitionStyle == "vinyl_brake"
        loopTransitionFallbackReason = null
        if (activeTransitionStyle == "loop_transition") {
            if (loopTransitionRegion == null && !loopTransitionReplayed) {
                loopTransitionRegion = beatGrid(outgoing, active)?.loopRegionEndingBefore(
                    active.currentPosition, plannedEnd, loopTransitionBeats, active.duration
                )
                if (loopTransitionRegion == null) {
                    loopTransitionFallbackReason = EffectDecisionReason.LOOP_GRID_UNAVAILABLE
                    publishBeatTelemetry(
                        BeatEffectTelemetry(
                            AutoMixEffect.LOOP_TRANSITION.id, true, true, false,
                            EffectLifecycleState.REJECTED, outgoingGrid?.confidence,
                            reason = "No confident outgoing loop boundary; using the normal crossfade.",
                            timingLimitation = LoopBoundaryScheduler.TIMING_LIMITATION,
                        )
                    )
                    activeTransitionStyle = "smooth"
                    Log.w(TAG, "Loop Transition has no valid confident loop; using normal crossfade")
                } else {
                    publishBeatTelemetry(
                        BeatEffectTelemetry(
                            AutoMixEffect.LOOP_TRANSITION.id, true, true, true,
                            EffectLifecycleState.PREPARED, outgoingGrid?.confidence,
                            beatIndex = loopTransitionRegion?.startBeatIndex,
                            loopStartMs = loopTransitionRegion?.startMs,
                            loopEndMs = loopTransitionRegion?.endMs,
                            reason = "Prepared a ${loopTransitionBeats}-beat outgoing loop.",
                            timingLimitation = LoopBoundaryScheduler.TIMING_LIMITATION,
                        )
                    )
                }
            }
            val region = loopTransitionRegion
            if (activeTransitionStyle == "loop_transition" && region != null && !loopTransitionReplayed) {
                if (active.currentPosition < region.endMs) return
                loopTransitionReplayed = true
                beatEffectTelemetry?.takeIf {
                    it.effectId == AutoMixEffect.LOOP_TRANSITION.id
                }?.let {
                    publishBeatTelemetry(it.copy(
                        state = EffectLifecycleState.EXECUTING,
                        reason = "Outgoing loop replayed; its final pass is crossfading to the incoming deck.",
                    ))
                }
                active.seekTo(region.startMs)
                return
            }
            if (activeTransitionStyle == "loop_transition") {
                transitionEnd = checkNotNull(loopTransitionRegion).endMs
                remaining = transitionEnd - active.currentPosition
            }
        }
        var requestedFadeDuration = if (automaticDuration) {
            AutomixMath.automaticDurationMs(outBpm)
        } else durationMs
        if (activeTransitionStyle != "loop_transition") {
            requestedFadeDuration = (requestedFadeDuration * currentEnergyScale()).toLong()
        }
        if (activeTransitionStyle == "loop_transition") {
            requestedFadeDuration = requestedFadeDuration.coerceAtMost(
                checkNotNull(loopTransitionRegion).durationMs
            )
        }
        if (standby.duration != C.TIME_UNSET) {
            val incomingRemaining = standby.duration - standby.currentPosition - END_MARGIN_MS
            val speed = if (beatmatchEnabled && !vinylBrakeTransition) {
                phaseAlignment?.incomingSpeed ?: 1f
            } else 1f
            requestedFadeDuration = requestedFadeDuration.coerceAtMost(
                (incomingRemaining / speed.coerceAtLeast(1f)).toLong()
            )
        }
        if (requestedFadeDuration <= 0) {
            Log.w(TAG, "Incoming track is too short to crossfade")
            cancelTransition()
            nextItem = null
            return
        }
        if (remaining > requestedFadeDuration + END_MARGIN_MS || remaining <= END_MARGIN_MS) return

        val activeSession = active.audioSessionId
        val standbySession = standby.audioSessionId
        if (activeSession == C.AUDIO_SESSION_ID_UNSET || standbySession == C.AUDIO_SESSION_ID_UNSET) return
        if (activeSession == standbySession) {
            Log.e(TAG, "Automix decks require independent audio sessions; cancelling crossfade")
            cancelTransition()
            nextItem = null
            return
        }

        if (pendingStartPositionMs == null) {
            val alignment = if (beatmatchEnabled && !vinylBrakeTransition) phaseAlignment else null
            matchedSpeed = if (vinylBrakeTransition) 1f else alignment?.incomingSpeed ?: 1f
            activeTransitionBeatmatched = alignment != null
            pendingBeatmatchExplanation = if (beatmatchEnabled && !vinylBrakeTransition) {
                TransitionExplanationEngine.explainBeatmatch(
                    alignment != null, outBpm, inBpm, outgoingGrid, incomingGrid,
                    bpmMatchEvaluation,
                )
            } else null
            val delayMs = alignment?.startDelayMs?.let { ceil(it).toLong() } ?: 0L
            pendingStartPositionMs = active.currentPosition +
                delayMs.coerceAtMost(remaining - END_MARGIN_MS - 1)
        }
        if (active.currentPosition < pendingStartPositionMs!!) return
        var fadeDuration = (transitionEnd - active.currentPosition - END_MARGIN_MS)
            .coerceAtMost(requestedFadeDuration)
        if (standby.duration != C.TIME_UNSET) {
            val incomingRemaining = standby.duration - standby.currentPosition - END_MARGIN_MS
            fadeDuration = fadeDuration.coerceAtMost(
                (incomingRemaining / matchedSpeed.coerceAtLeast(1f)).toLong()
            )
        }
        if (fadeDuration <= 0) {
            Log.w(TAG, "Incoming track is too short to crossfade")
            cancelTransition()
            nextItem = null
            return
        }
        standby.playbackParameters = PlaybackParameters(
            if (vinylBrakeTransition) 1f else matchedSpeed,
            active.playbackParameters.pitch
        )
        activeEffectPlan = TransitionEffectPlan.create(activeTransitionStyle, additionalEffects, effectIntensity / 100f)
        observedExecutionEffects.clear()
        if (activeEffectPlan.rejected.isNotEmpty()) {
            Log.w(TAG, "Transition filter conflict; skipped ${activeEffectPlan.rejected}")
        }
        echoDelayMs = outBpm?.takeIf { it.isFinite() && it in 30f..300f }?.let {
            60_000f / it * 0.75f
        } ?: DEFAULT_ECHO_DELAY_MS
        if (activeTransitionStyle == "tremolo_fade") {
            tremoloBpm = outBpm?.takeIf { it.isFinite() && it in 30f..300f } ?: 120f
        }
        val loudnessExplanation = planLoudnessMatching()
        val energyExplanation = energyExplanation()
        applyEffectPlan(0f)
        activeFadeDurationMs = fadeDuration
        transitionProgress = 0f
        transition.start(fadeDuration, SystemClock.elapsedRealtime())
        transition.pause(SystemClock.elapsedRealtime())
        transitionStartActiveFrames = activeDjFilter.runtimeSnapshot().processedFrames
        transitionStartStandbyFrames = standbyDjFilter.runtimeSnapshot().processedFrames
        val outgoingItem = active.currentMediaItem
        val incomingItem = standby.currentMediaItem
        if (outgoingItem != null && incomingItem != null) {
            explanationQueue = queueSnapshot()
            val generation = TransitionGeneration.next()
            mutableTransitionExplanation.value = TransitionAnalysisSnapshot(
                generation = generation,
                outgoingId = outgoingItem.mediaId,
                incomingId = incomingItem.mediaId,
                outgoingIndex = active.currentMediaItemIndex,
                incomingIndex = active.nextMediaItemIndex,
                outgoingTitle = outgoingItem.mediaMetadata.title?.toString(),
                incomingTitle = incomingItem.mediaMetadata.title?.toString(),
                durationMs = fadeDuration,
                transitionStyle = activeTransitionStyle,
                transitionReasons = automaticDecision?.reasons ?: listOf(EffectDecisionReason.USER_PRESET),
                selectorDifferencePercent = automaticDecision?.differencePercent,
                selectorThresholdPercent = automaticDecision?.thresholdPercent,
                selectorRisingPercent = automaticDecision?.risingPercent,
                selectorRisingThresholdPercent = automaticDecision?.risingThresholdPercent,
                selectorVocalThreshold = automaticDecision?.vocalThreshold,
                status = TransitionExecutionStatus.ACTIVE,
                analyzer = decks.analysisCache?.selectedAnalyzer,
                outgoingBpm = outBpm,
                incomingBpm = inBpm,
                outgoingGridConfidence = outgoingGrid?.confidence,
                incomingGridConfidence = incomingGrid?.confidence,
                outgoingKey = outgoing.camelotKey,
                incomingKey = incoming.camelotKey,
                outgoingVocalEnergy = outgoing.vocalEnergy,
                incomingVocalEnergy = incoming.vocalEnergy,
                effects = TransitionExplanationEngine.explain(activeEffectPlan, activeTransitionStyle,
                    automaticDecision?.reasons, additionalEffects) +
                    listOfNotNull(pendingBeatmatchExplanation) +
                    listOfNotNull(loopTransitionExplanation(), loudnessExplanation, energyExplanation),
            )
            gainOwnershipRegistry.beginGeneration(generation)
            gainOwnershipGeneration = generation
            if (activeTransitionStyle != "vinyl_brake") {
                gainOwnershipRegistry.claim(
                    when (activeTransitionStyle) {
                        "exponential_fade" -> AutoMixEffect.EXPONENTIAL_FADE
                        "s_curve_fade" -> AutoMixEffect.S_CURVE_FADE
                        else -> AutoMixEffect.CROSSFADE
                    }, generation
                )
            } else {
                gainOwnershipRegistry.claim(AutoMixEffect.VINYL_BRAKE, generation)
            }
            if (activeTransitionStyle == "tremolo_fade") {
                gainOwnershipRegistry.claim(AutoMixEffect.TREMOLO_FADE, generation)
            }
            if (activeTransitionBeatmatched) {
                val beatmatchEffect = pendingBeatmatchExplanation?.effectId
                    ?.let(AutoMixEffect::fromId) ?: AutoMixEffect.BEATMATCH
                gainOwnershipRegistry.claim(beatmatchEffect, generation)
            }
            if (activeTransitionStyle == "loop_transition") {
                val selection = AutoMixEffectArbitrator.evaluate(
                    listOf(AutoMixEffect.LOOP_TRANSITION, AutoMixEffect.CROSSFADE),
                    generation = generation,
                )
                check(selection.all { it.state == EffectLifecycleState.SELECTED }) {
                    "Loop Transition is incompatible with the active crossfade."
                }
                gainOwnershipRegistry.claim(AutoMixEffect.LOOP_TRANSITION, generation)
            }
            if (loudnessPlan?.applied == true) {
                gainOwnershipRegistry.claim(AutoMixEffect.LOUDNESS_MATCHING, generation)
            }
            activeEffectPlan.effects.forEach { scheduled ->
                gainOwnershipRegistry.claim(
                    checkNotNull(AutoMixEffect.fromId(scheduled.effect.id)), generation
                )
            }
            updateEffectExecutionTelemetry(prepared = true)
        }
        applyVolumes()
        standby.playWhenReady = true
    }

    private fun applyEffectPlan(progress: Float) {
        val bassOwned = activeEffectPlan.effects.any { it.effect == TransitionEffect.BASS_SWAP }
        val midOwned = activeEffectPlan.effects.any { it.effect == TransitionEffect.VOCAL_SWAP }
        for (scheduled in activeEffectPlan.effects) {
            val value = scheduled.progress(progress)
            val intensity = scheduled.intensity
            when (scheduled.effect) {
                TransitionEffect.BASS_SWAP -> {
                    activeDjFilter.setBassGain(1f - intensity * value)
                    standbyDjFilter.setBassGain(1f - intensity * (1f - value))
                }
                TransitionEffect.VOCAL_SWAP -> {
                    activeDjFilter.setVocalAttenuation(value * intensity * VOCAL_SWAP_ATTENUATION)
                    standbyDjFilter.setVocalAttenuation((1f - value) * intensity * VOCAL_SWAP_ATTENUATION)
                }
                TransitionEffect.EQ_TRANSITION -> {
                    activeDjFilter.setTransitionEq(
                        if (bassOwned) 1f else 1f - intensity * value,
                        if (midOwned) 1f else 1f - intensity * value * 0.5f,
                        1f - intensity * value * 0.5f,
                    )
                    standbyDjFilter.setTransitionEq(
                        if (bassOwned) 1f else 1f - intensity * (1f - value),
                        if (midOwned) 1f else 1f - intensity * (1f - value) * 0.5f,
                        1f - intensity * (1f - value) * 0.5f,
                    )
                }
                TransitionEffect.FILTER_SWEEP -> {
                    activeDjFilter.setFilter(DjTransitionAudioProcessor.Type.HIGH_PASS,
                        exponentialCutoff(FILTER_OUT_START_HZ, FILTER_OUT_END_HZ, value), intensity)
                    standbyDjFilter.setFilter(DjTransitionAudioProcessor.Type.LOW_PASS,
                        exponentialCutoff(FILTER_IN_START_HZ, FILTER_IN_END_HZ, value), intensity)
                }
                TransitionEffect.HIGH_PASS_INTRO ->
                    standbyDjFilter.setFilter(DjTransitionAudioProcessor.Type.HIGH_PASS,
                        exponentialCutoff(3500f, FILTER_OUT_START_HZ, value), intensity)
                TransitionEffect.RISE ->
                    standbyDjFilter.setFilter(DjTransitionAudioProcessor.Type.LOW_PASS,
                        exponentialCutoff(FILTER_IN_START_HZ, FILTER_IN_END_HZ, value), intensity)
                TransitionEffect.ECHO -> {
                    val dryProgress = ((value - ECHO_OUT_DRY_MUTE_START) /
                        (1f - ECHO_OUT_DRY_MUTE_START)).coerceIn(0f, 1f)
                    val dryMute = dryProgress * dryProgress * (3f - 2f * dryProgress)
                    activeDjFilter.setEcho(echoDelayMs, feedback = ECHO_OUT_FEEDBACK,
                        wetMix = ECHO_OUT_WET_MIX * intensity * value,
                        dryGain = 1f - intensity * dryMute)
                }
                TransitionEffect.REVERB -> activeDjFilter.setReverb(value * intensity * 0.7f)
            }
        }
    }

    private fun handoff(now: Long) {
        if (beatLoopCoordinator.ownsTimingResources) {
            clearBeatLoop("Deck handoff terminated the Beat Loop.")
        }
        val outgoing = active
        val incoming = standby
        lastTransitionStyle = activeTransitionStyle
        lastEffectIds = activeEffectPlan.effects.map { it.effect.id } +
            if (activeTransitionStyle == "loop_transition") {
                listOf(AutoMixEffect.LOOP_TRANSITION.id)
            } else emptyList()
        lastTransitionBeatmatched = activeTransitionBeatmatched
        incoming.playbackParameters = PlaybackParameters(
            if (vinylBrakeTransition) 1f else outgoing.playbackParameters.speed,
            outgoing.playbackParameters.pitch
        )
        updateEffectExecutionTelemetry(completed = true)
        beatEffectTelemetry?.takeIf {
            it.effectId == AutoMixEffect.LOOP_TRANSITION.id &&
                it.selected && it.state != EffectLifecycleState.FAILED
        }?.let {
            publishBeatTelemetry(it.copy(
                state = EffectLifecycleState.COMPLETED,
                reason = "Loop Transition completed through the crossfade exit.",
            ))
        }
        transition.complete(now)
        releaseGainOwnership()
        activeEffectPlan = TransitionEffectPlan.create("smooth", emptySet())
        decks.factoryA.djTransitionAudioProcessor.bypass()
        decks.factoryB.djTransitionAudioProcessor.bypass()
        activeGain = 1f
        standbyGain = 0f
        nextItem = null
        preparedItem = null
        preparedQueue = null
        pendingStartPositionMs = null
        pendingBeatmatchExplanation = null
        lastHandoffElapsedRealtimeMs = now
        vinylBrakeTransition = false
        activeTransitionBeatmatched = false
        activeFadeDurationMs = 0L
        transitionProgress = 0f
        loopTransitionRegion = null
        loopTransitionReplayed = false
        loopTransitionFallbackReason = null
        bpmMatchEvaluation = null
        loudnessPlan = null
        applyVolumes()
        onActiveDeckChanged?.invoke(outgoing, incoming)
        forwarding.handoff(incoming)
        explanationQueue = queueSnapshot()
        mutableTransitionExplanation.value = mutableTransitionExplanation.value?.copy(
            status = TransitionExecutionStatus.COMPLETED
        )
        outgoing.playWhenReady = false
        outgoing.stop()
        outgoing.shuffleOrder = ShuffleOrder.DefaultShuffleOrder(outgoing.mediaItemCount)
        outgoing.clearMediaItems()
    }

    private fun updateEffectExecutionTelemetry(
        prepared: Boolean = false,
        completed: Boolean = false,
    ) {
        val snapshot = mutableTransitionExplanation.value ?: return
        val identity = mutableTransitionExplanation.value?.identity ?: return
        val activeRuntime = activeDjFilter.runtimeSnapshot()
        val standbyRuntime = standbyDjFilter.runtimeSnapshot()
        val runtimeById = snapshot.effects.mapNotNull { effect ->
            if (effect.selectionStatus != EffectSelectionStatus.SELECTED) return@mapNotNull null
            val autoMixEffect = AutoMixEffect.fromId(effect.effectId) ?: return@mapNotNull null
            val processorEffects = processorEffects(effect.effectId)
            val activeSeen = processorEffects.any { it in activeRuntime.activeEffects }
            val standbySeen = processorEffects.any { it in standbyRuntime.activeEffects }
            val seen = activeSeen || standbySeen
            val beatmatchEffect = effect.effectId == "beatmatch" ||
                effect.effectId == AutoMixEffect.HALF_TIME_DOUBLE_TIME.id
            val tempoControlSeen = beatmatchEffect && activeTransitionBeatmatched &&
                standby.isPlaying && kotlin.math.abs(
                    standby.playbackParameters.speed -
                        AutomixMath.rampSpeed(matchedSpeed, transitionProgress)
                ) < 0.0001f
            val envelopeEffect = effect.effectId == "crossfade" ||
                (effect.effectId == AutoMixEffect.LOUDNESS_MATCHING.id && loudnessPlan?.applied == true) ||
                (effect.effectId == activeTransitionStyle &&
                    effect.effectId in setOf("exponential_fade", "s_curve_fade")) ||
                (effect.effectId == AutoMixEffect.LOOP_TRANSITION.id &&
                    activeTransitionStyle == "loop_transition") ||
                (effect.effectId == "rise" && activeTransitionStyle == "rise")
            if (seen || tempoControlSeen || (envelopeEffect && transitionProgress > 0f)) {
                observedExecutionEffects += autoMixEffect
            }
            val executionObserved = autoMixEffect in observedExecutionEffects
            val state = when {
                completed && executionObserved -> EffectLifecycleState.COMPLETED
                completed -> EffectLifecycleState.PREPARED
                prepared -> EffectLifecycleState.PREPARED
                tempoControlSeen -> EffectLifecycleState.EXECUTING
                envelopeEffect ->
                    if (transitionProgress > 0f) EffectLifecycleState.EXECUTING else EffectLifecycleState.PREPARED
                seen -> EffectLifecycleState.EXECUTING
                else -> return@mapNotNull null
            }
            val processor = if (activeSeen) activeRuntime else standbyRuntime
            val baselineFrames = if (activeSeen) transitionStartActiveFrames
                else transitionStartStandbyFrames
            effect.effectId to EffectRuntimeTelemetry(
                effect = autoMixEffect,
                state = state,
                processedFrames = (processor.processedFrames - baselineFrames).coerceAtLeast(0L),
                sampleRate = processor.sampleRate,
                activeGain = if (activeSeen) activeGain else standbyGain,
                targetGain = if (activeSeen) activeGain else standbyGain,
                detail = when {
                    completed && !executionObserved -> "No execution evidence was observed."
                    tempoControlSeen ->
                        "Media3 playback parameters were applied; device-output audio is not observable."
                    !processor.configured && processorEffects.isNotEmpty() ->
                        "Audio processor is not configured."
                    else -> null
                },
            )
        }.toMap()
        TransitionExplanationEngine.updateExecution(snapshot, identity, runtimeById)?.let {
            if (!TransitionGeneration.isCurrent(it.identity, mutableTransitionExplanation.value?.identity)) {
                return
            }
            mutableTransitionExplanation.value = it
            mutableRuntimeTelemetry.value = TransitionRuntimeTelemetry(
                identity, transitionProgress.coerceIn(0f, 1f), runtimeById.values.toList()
            )
        }
    }

    private fun processorEffects(effectId: String): Set<TransitionProcessorEffect> = when (effectId) {
        "bass_swap" -> setOf(TransitionProcessorEffect.BASS)
        "vocal_swap" -> setOf(TransitionProcessorEffect.VOCAL)
        "eq_transition" -> setOf(TransitionProcessorEffect.EQ)
        "filter_sweep" -> setOf(
            TransitionProcessorEffect.FILTER_HIGH_PASS, TransitionProcessorEffect.FILTER_LOW_PASS
        )
        "high_pass_intro" -> setOf(TransitionProcessorEffect.FILTER_HIGH_PASS)
        "rise" -> setOf(TransitionProcessorEffect.FILTER_LOW_PASS)
        "echo_out" -> setOf(TransitionProcessorEffect.ECHO)
        "reverb_tail" -> setOf(TransitionProcessorEffect.REVERB)
        else -> emptySet()
    }

    private fun trackLoudness(player: ExoPlayer, deckIsA: Boolean): TrackLoudness {
        val key = player.currentMediaItem?.let(AnalysisKey::from)
        val measured = key?.let { decks.analysisCache?.peekLoudness(it) }
        val processor = if (deckIsA) decks.factoryA.replayGainAudioProcessor
            else decks.factoryB.replayGainAudioProcessor
        return TrackLoudness(measured = measured, replayGainAppliedDb = processor.appliedGainDb)
    }

    /** Plans loudness matching for the transition that is starting; returns its explanation entry. */
    private fun planLoudnessMatching(): EffectSelectionExplanation? {
        loudnessPlan = null
        if (!loudnessMatchingEnabled) return null
        val outgoingIsA = transition.activeDeck == DeckTransition.Deck.A
        val plan = LoudnessMatcher.plan(
            trackLoudness(active, outgoingIsA), trackLoudness(standby, !outgoingIsA),
            LoudnessMatchConfig(allowMetadataFallback = false)
        )
        val format = java.text.DecimalFormat("0.0")
        val detail = if (plan.outgoingEffectiveLufs != null && plan.incomingEffectiveLufs != null) {
            "Outgoing " + format.format(plan.outgoingEffectiveLufs) + " LUFS, incoming " +
                format.format(plan.incomingEffectiveLufs) + " LUFS (60 s middle window, after applied ReplayGain); " +
                "attenuation " + format.format(plan.appliedCorrectionDb) + " dB"
        } else null
        val reason = when (plan.reason) {
            LoudnessMatchReason.APPLIED, LoudnessMatchReason.CLAMPED_TO_MAX_CORRECTION ->
                EffectDecisionReason.LOUDNESS_MATCH_APPLIED
            LoudnessMatchReason.WITHIN_DEADBAND -> EffectDecisionReason.LOUDNESS_WITHIN_DEADBAND
            LoudnessMatchReason.SOURCES_NOT_COMPARABLE -> EffectDecisionReason.LOUDNESS_SOURCES_NOT_COMPARABLE
            LoudnessMatchReason.OUTGOING_UNAVAILABLE, LoudnessMatchReason.INCOMING_UNAVAILABLE ->
                EffectDecisionReason.LOUDNESS_DATA_UNAVAILABLE
        }
        if (plan.applied) {
            loudnessPlan = plan
            loudnessPlanOutgoingIsDeckA = outgoingIsA
        }
        return EffectSelectionExplanation(
            AutoMixEffect.LOUDNESS_MATCHING.id,
            if (plan.applied) EffectSelectionStatus.SELECTED else EffectSelectionStatus.SKIPPED,
            listOf(reason),
            if (plan.applied) EffectExecutionStatus.PLANNED else null,
            executionDetail = detail,
        )
    }

    /** Energy plan for the current outgoing/incoming pair; memoised so the 25 ms tick stays cheap. */
    private fun energyPlanFor(): EnergyMatchPlan? {
        if (!energyMatchingEnabled) return null
        val outKey = active.currentMediaItem?.let(AnalysisKey::from)
        val inKey = standby.currentMediaItem?.let(AnalysisKey::from)
        if (outKey == null || inKey == null) return null
        val pair = outKey.mediaId to inKey.mediaId
        val cached = energyPlan
        if (energyPlanKey == pair && cached != null && cached.reason != EnergyMatchReason.OUTGOING_UNAVAILABLE &&
            cached.reason != EnergyMatchReason.INCOMING_UNAVAILABLE) return cached
        val cache = decks.analysisCache ?: return null
        val plan = EnergyMatcher.plan(cache.peekEnergy(outKey), cache.peekEnergy(inKey))
        energyPlanKey = pair
        energyPlan = plan
        return plan
    }

    private fun currentEnergyScale(): Float = energyPlanFor()?.durationScale ?: 1f

    private fun energyExplanation(): EffectSelectionExplanation? {
        if (!energyMatchingEnabled) return null
        val plan = energyPlanFor() ?: return null
        val reason = when (plan.reason) {
            EnergyMatchReason.EXTENDED_FOR_RISE, EnergyMatchReason.EXTENDED_FOR_DROP ->
                EffectDecisionReason.ENERGY_MATCH_APPLIED
            EnergyMatchReason.WITHIN_THRESHOLD -> EffectDecisionReason.ENERGY_WITHIN_THRESHOLD
            EnergyMatchReason.LOW_CONFIDENCE, EnergyMatchReason.SOURCES_NOT_COMPARABLE,
            EnergyMatchReason.OUTGOING_UNAVAILABLE, EnergyMatchReason.INCOMING_UNAVAILABLE ->
                EffectDecisionReason.ENERGY_DATA_UNAVAILABLE
        }
        val format = java.text.DecimalFormat("0.0")
        val detail = plan.differenceDb?.let {
            "Incoming minus outgoing unweighted RMS: " + format.format(it) + " dB (not perceived loudness); " +
                "transition length x" + format.format(plan.durationScale)
        }
        return EffectSelectionExplanation(
            AutoMixEffect.ENERGY_MATCHING.id,
            if (plan.applied) EffectSelectionStatus.SELECTED else EffectSelectionStatus.SKIPPED,
            listOf(reason),
            if (plan.applied) EffectExecutionStatus.PLANNED else null,
            executionDetail = detail,
        )
    }

    private fun loopTransitionExplanation(): EffectSelectionExplanation? {
        val region = loopTransitionRegion
        if (activeTransitionStyle == "loop_transition" && region != null) {
            return EffectSelectionExplanation(
                AutoMixEffect.LOOP_TRANSITION.id,
                EffectSelectionStatus.SELECTED,
                listOf(EffectDecisionReason.LOOP_TRANSITION_READY),
                EffectExecutionStatus.PREPARED,
                loopStartMs = region.startMs,
                loopEndMs = region.endMs,
                timingLimitation = LoopBoundaryScheduler.TIMING_LIMITATION,
            )
        }
        val reason = loopTransitionFallbackReason ?: return null
        return EffectSelectionExplanation(
            AutoMixEffect.LOOP_TRANSITION.id,
            EffectSelectionStatus.SKIPPED,
            listOf(reason, EffectDecisionReason.LOOP_FALLBACK_CROSSFADE),
            null,
            timingLimitation = LoopBoundaryScheduler.TIMING_LIMITATION,
        )
    }

    private fun releaseGainOwnership() {
        gainOwnershipGeneration?.let(gainOwnershipRegistry::releaseGeneration)
        gainOwnershipGeneration = null
    }

    private fun exponentialCutoff(startHz: Float, endHz: Float, progress: Float): Float =
        startHz * (endHz / startHz).toDouble().pow(progress.coerceIn(0f, 1f).toDouble()).toFloat()
}

/**
 * Swappable event-forwarding layer for a future dual-deck player. The caller must fully prepare
 * the replacement and transfer its queue before handoff.
 */
internal class SwappableDeckPlayer(initialPlayer: Player) :
    ForwardingSimpleBasePlayer(initialPlayer) {
    private var automaticHandoff = false
    private var exposedVolume = initialPlayer.volume

    override fun getState(): State {
        val state = super.getState().buildUpon().setVolume(exposedVolume)
        if (automaticHandoff) {
            state.setPositionDiscontinuity(
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION, player.currentPosition
            )
        }
        return state.build()
    }

    fun setExposedVolume(volume: Float) {
        require(volume.isFinite() && volume in 0f..1f)
        exposedVolume = volume
        invalidateState()
    }

    fun handoff(replacement: Player) {
        require(replacement !== player) { "A handoff requires a different player" }
        require(replacement.applicationLooper == applicationLooper) {
            "Both decks must use the same application looper"
        }
        // Initialize the old state even when no getter has been called before the first handoff.
        currentTimeline
        automaticHandoff = true
        try {
            setPlayer(replacement)
        } finally {
            automaticHandoff = false
        }
    }
}
