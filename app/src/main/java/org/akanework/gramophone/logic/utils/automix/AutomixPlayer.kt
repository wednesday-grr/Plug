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

package org.akanework.gramophone.logic.utils.automix

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
import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.akanework.gramophone.logic.utils.ReplayGainUtil
import org.akanework.gramophone.logic.utils.CircularShuffleOrder
import org.akanework.gramophone.logic.utils.PostAmpAudioOutputProvider
import androidx.media3.common.Format
import org.akanework.gramophone.logic.utils.exoplayer.PlugRenderFactory
import org.akanework.gramophone.logic.utils.exoplayer.PlugExtractorsFactory
import org.akanework.gramophone.logic.utils.exoplayer.PlugMediaSourceFactory
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
    private var activeEffectPlan = TransitionEffectPlan.create("smooth", emptySet())
    private var lastEffectIds: List<String> = emptyList()
    private val mutableTransitionExplanation = MutableStateFlow<TransitionAnalysisSnapshot?>(null)
    internal val transitionExplanation = mutableTransitionExplanation.asStateFlow()
    private val mutableRuntimeTelemetry = MutableStateFlow<TransitionRuntimeTelemetry?>(null)
    internal val runtimeTelemetry = mutableRuntimeTelemetry.asStateFlow()
    private var explanationQueue: QueueSnapshot? = null
    private val gainOwnershipRegistry = GainOwnershipRegistry()
    private var activeTransitionBeatmatched = false
    private var lastTransitionStyle: String? = null
    private var lastTransitionBeatmatched = false
    private var vinylBrakeTransition = false
    private var echoDelayMs = DEFAULT_ECHO_DELAY_MS
    private var activeFadeDurationMs = 0L
    private var transitionProgress = 0f
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
        return first && second
    }

    fun setReplayGainMode(mode: ReplayGainUtil.Mode, doNotNotifyListener: Boolean): Boolean {
        assertThread()
        val first = decks.factoryA.replayGainAudioProcessor.setMode(mode, doNotNotifyListener)
        val second = decks.factoryB.replayGainAudioProcessor.setMode(mode, doNotNotifyListener)
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
                delay(if (enabled && nextItem != null) TICK_MS else 250L)
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
    ) {
        assertThread()
        require(durationSec in 1..30)
        require(maxSkipSeconds in 0..30)
        require(additionalEffects.all { it in TransitionEffectPlan.selectableIds })
        require(effectIntensityPercent in 0..100)
        require(transitionStyle in setOf(
            "auto", "smooth", "vinyl_brake", "bass_swap", "filter_sweep", "echo_out",
            "high_pass_intro", "reverb_tail", "tremolo_fade", "fade", "rise"
        ))
        if (this.enabled != enabled || this.beatmatchEnabled != beatmatchEnabled ||
            durationMs != durationSec * 1000L || this.transitionStyle != transitionStyle ||
            this.automaticDuration != automaticDuration || maxSkipMs != maxSkipSeconds * 1000L ||
            this.additionalEffects != additionalEffects || effectIntensity != effectIntensityPercent
        ) cancelTransition()
        this.enabled = enabled
        this.beatmatchEnabled = beatmatchEnabled
        durationMs = durationSec * 1000L
        this.transitionStyle = transitionStyle
        this.automaticDuration = automaticDuration
        maxSkipMs = maxSkipSeconds * 1000L
        this.additionalEffects = additionalEffects.toSet()
        effectIntensity = effectIntensityPercent
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        assertThread()
        require(!handleAudioFocus || audioAttributes.usage == C.USAGE_MEDIA ||
            audioAttributes.usage == C.USAGE_GAME) {
            "Automatic audio focus requires media or game usage"
        }
        abandonFocus()
        handleFocus = handleAudioFocus
        focusRequest = if (handleAudioFocus) AudioFocusRequestCompat.Builder(AudioManager.AUDIOFOCUS_GAIN)
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

    private fun applyVolumes() {
        val outputGain = if (transition.phase == DeckTransition.Phase.FADING) {
            AutomixMath.overlapHeadroom(transitionProgress)
        } else 1f
        decks.factoryA.djTransitionAudioProcessor.setOutputGain(outputGain)
        decks.factoryB.djTransitionAudioProcessor.setOutputGain(outputGain)
        active.volume = userVolume * focusVolume * activeGain
        standby.volume = userVolume * focusVolume * standbyGain
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
            releaseGainOwnership()
            mutableTransitionExplanation.value = null
            mutableRuntimeTelemetry.value = null
            explanationQueue = null
        }
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
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (released) return
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
        if (released || !enabled || nextItem == null) return
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
        val remaining = plannedEnd - active.currentPosition
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
        val phaseAlignment = BeatPhaseAligner.align(
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
        var requestedFadeDuration = if (automaticDuration) {
            AutomixMath.automaticDurationMs(outBpm)
        } else durationMs
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
                    alignment != null, outBpm, inBpm, outgoingGrid, incomingGrid)
            } else null
            val delayMs = alignment?.startDelayMs?.let { ceil(it).toLong() } ?: 0L
            pendingStartPositionMs = active.currentPosition +
                delayMs.coerceAtMost(remaining - END_MARGIN_MS - 1)
        }
        if (active.currentPosition < pendingStartPositionMs!!) return
        var fadeDuration = (plannedEnd - active.currentPosition - END_MARGIN_MS)
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
        if (activeEffectPlan.rejected.isNotEmpty()) {
            Log.w(TAG, "Transition filter conflict; skipped ${activeEffectPlan.rejected}")
        }
        echoDelayMs = outBpm?.takeIf { it.isFinite() && it in 30f..300f }?.let {
            60_000f / it * 0.75f
        } ?: DEFAULT_ECHO_DELAY_MS
        if (activeTransitionStyle == "tremolo_fade") {
            tremoloBpm = outBpm?.takeIf { it.isFinite() && it in 30f..300f } ?: 120f
        }
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
                    automaticDecision?.reasons, additionalEffects) + listOfNotNull(pendingBeatmatchExplanation),
            )
            gainOwnershipRegistry.beginGeneration(generation)
            gainOwnershipRegistry.claim(AutoMixEffect.CROSSFADE, generation)
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
        val outgoing = active
        val incoming = standby
        lastTransitionStyle = activeTransitionStyle
        lastEffectIds = activeEffectPlan.effects.map { it.effect.id }
        lastTransitionBeatmatched = activeTransitionBeatmatched
        incoming.playbackParameters = PlaybackParameters(
            if (vinylBrakeTransition) 1f else outgoing.playbackParameters.speed,
            outgoing.playbackParameters.pitch
        )
        updateEffectExecutionTelemetry(completed = true)
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
            val processorEffects = processorEffects(effect.effectId)
            val activeSeen = processorEffects.any { it in activeRuntime.activeEffects }
            val standbySeen = processorEffects.any { it in standbyRuntime.activeEffects }
            val seen = activeSeen || standbySeen
            val envelopeEffect = effect.effectId == "crossfade" || effect.effectId == "beatmatch" ||
                (effect.effectId == "rise" && activeTransitionStyle == "rise")
            val executionObserved = seen || (envelopeEffect && transitionProgress > 0f)
            val state = when {
                completed -> if (executionObserved) EffectLifecycleState.COMPLETED
                    else EffectLifecycleState.FAILED
                prepared -> EffectLifecycleState.PREPARED
                envelopeEffect ->
                    if (transitionProgress > 0f) EffectLifecycleState.EXECUTING else EffectLifecycleState.PREPARED
                seen -> EffectLifecycleState.EXECUTING
                else -> return@mapNotNull null
            }
            val processor = if (activeSeen) activeRuntime else standbyRuntime
            val baselineFrames = if (activeSeen) transitionStartActiveFrames
                else transitionStartStandbyFrames
            effect.effectId to EffectRuntimeTelemetry(
                effect = AutoMixEffect.fromId(effect.effectId) ?: return@mapNotNull null,
                state = state,
                processedFrames = (processor.processedFrames - baselineFrames).coerceAtLeast(0L),
                sampleRate = processor.sampleRate,
                activeGain = if (activeSeen) activeGain else standbyGain,
                targetGain = if (activeSeen) activeGain else standbyGain,
                detail = when {
                    completed && !executionObserved -> "No execution evidence was observed."
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

    private fun releaseGainOwnership() {
        mutableTransitionExplanation.value?.generation?.let(gainOwnershipRegistry::releaseGeneration)
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
