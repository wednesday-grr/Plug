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

package org.akanework.gramophone.logic.utils

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.Log
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Serializes session effect access on [handler]. Deck requirements and fade weights may be
 * submitted from different player threads; neither deck owns the shared effect.
 */
class SessionBoostCoordinator(
    private val handler: Handler,
    private val audioSessionId: Int,
) {
    data class DeckBoostState(
        val targetGainMb: Int = 0,
        val isActive: Boolean = false,
        val fadeWeight: Float = 0f,
        val mixerVolumeDb: Float? = null,
        val outputVolume: Float = 1f,
        val volumeBoostMb: Int = targetGainMb,
    )

    companion object {
        private const val TAG = "SessionBoost"

        fun effectiveGainMb(states: Collection<DeckBoostState>): Int {
            val audible = states.filter { it.isActive && it.fadeWeight > 0f }
            if (audible.isEmpty()) return 0
            if (audible.size == 1) return audible.single().targetGainMb
            val weight = audible.sumOf { it.fadeWeight.toDouble() }
            return (audible.sumOf { it.targetGainMb.toDouble() * it.fadeWeight } / weight)
                .roundToInt()
        }

        internal fun volumeFallbackDeck(states: Collection<DeckBoostState>): DeckBoostState? =
            states.filter { it.isActive }.singleOrNull()
    }

    private val lock = Any()
    private val states = mutableMapOf<String, DeckBoostState>()
    private var released = false
    private var updatePending = false
    private var dpe: DynamicsProcessingEffectWrapper? = null
    private var volume: VolumeEffectWrapper? = null
    private var loggedUnavailable = false

    init {
        require(audioSessionId > 0) { "A valid audio session is required" }
    }

    fun registerDeck(deckId: String) {
        synchronized(lock) {
            check(!released)
            check(deckId !in states) { "Duplicate deck: $deckId" }
            states[deckId] = DeckBoostState()
        }
    }

    fun requireSessionId(id: Int) {
        check(id == audioSessionId) { "Deck audio session $id does not match $audioSessionId" }
    }

    internal fun snapshotDecks(): Map<String, DeckBoostState> = synchronized(lock) { states.toMap() }

    fun updateRequirement(
        deckId: String, targetGainMb: Int, mixerVolumeDb: Float?, outputVolume: Float,
        volumeBoostMb: Int = targetGainMb,
    ) {
        require(targetGainMb >= 0)
        require(volumeBoostMb >= 0)
        require(mixerVolumeDb == null || mixerVolumeDb.isFinite())
        require(outputVolume.isFinite() && outputVolume in 0f..1f)
        synchronized(lock) {
            if (released) return
            val state = checkNotNull(states[deckId]) { "Unknown deck: $deckId" }
            states[deckId] = state.copy(
                targetGainMb = targetGainMb, mixerVolumeDb = mixerVolumeDb,
                outputVolume = outputVolume, volumeBoostMb = volumeBoostMb
            )
            scheduleUpdateLocked()
        }
    }

    fun updateFadeWeights(activeDeckId: String, activeWeight: Float, standbyDeckId: String,
                          standbyWeight: Float, overlapping: Boolean) {
        require(activeDeckId != standbyDeckId)
        require(activeWeight.isFinite() && activeWeight in 0f..1f)
        require(standbyWeight.isFinite() && standbyWeight in 0f..1f)
        synchronized(lock) {
            if (released) return
            val active = checkNotNull(states[activeDeckId]) { "Unknown deck: $activeDeckId" }
            val standby = checkNotNull(states[standbyDeckId]) { "Unknown deck: $standbyDeckId" }
            states[activeDeckId] = active.copy(isActive = true, fadeWeight = activeWeight)
            states[standbyDeckId] = standby.copy(isActive = overlapping, fadeWeight = standbyWeight)
            scheduleUpdateLocked()
        }
        // The incoming deck can start on another playback thread before a posted update runs.
        if (overlapping && Looper.myLooper() == handler.looper) volume?.created = false
    }

    fun refresh() {
        synchronized(lock) {
            if (!released) scheduleUpdateLocked()
        }
    }

    private fun scheduleUpdateLocked() {
        if (!updatePending) {
            updatePending = true
            check(handler.post(::applyState)) { "Session boost handler is no longer running" }
        }
    }

    private fun applyState() {
        val snapshot = synchronized(lock) {
            updatePending = false
            if (released) return
            states.values.toList()
        }
        val active = snapshot.filter { it.isActive }
        val gain = effectiveGainMb(active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && PostAmpAudioOutputProvider.isDpeAvailable) {
            val effect = dpe ?: DynamicsProcessingEffectWrapper(handler) { session ->
                DynamicsProcessing(
                    -100000, session,
                    DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        1, false, 0, false, 0, false, 0, true
                    ).setAllChannelsTo(
                        DynamicsProcessing.Channel(0f, false, 0, false, 0, false, 0, true).apply {
                            mbc = DynamicsProcessing.Mbc(false, false, 0)
                            limiter = DynamicsProcessing.Limiter(
                                true, false, 0, ReplayGainUtil.TAU_ATTACK * 1000f,
                                ReplayGainUtil.TAU_RELEASE * 1000f, ReplayGainUtil.RATIO, 0f, 0f
                            )
                            preEq = DynamicsProcessing.Eq(false, false, 0)
                            postEq = DynamicsProcessing.Eq(false, false, 0)
                        }
                    ).build()
                )
            }.also {
                dpe = it
                it.hasControlListener = { refresh() }
                it.audioSessionId = audioSessionId
            }
            effect.created = gain > 0
            if (effect.hasControl) {
                volume?.created = false
                try {
                    effect.effect!!.enabled = gain > 0
                    effect.effect!!.setInputGainAllChannelsTo(gain / 100f)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to update session Dynamics Processing", e)
                }
                return
            }
        }
        // Volume sets an absolute session level and cannot preserve independent track fades.
        val single = volumeFallbackDeck(active)
        if (active.any { it.volumeBoostMb > 0 } &&
            !PostAmpAudioOutputProvider.isVolumeAvailable && !loggedUnavailable
        ) {
            Log.w(TAG, "No controllable session boost effect is available")
            loggedUnavailable = true
        }
        if (single == null || single.volumeBoostMb <= 0 ||
            single.mixerVolumeDb == null || !PostAmpAudioOutputProvider.isVolumeAvailable
        ) {
            volume?.created = false
            return
        }
        val effect = volume ?: VolumeEffectWrapper(-100000).also {
            volume = it
            it.hasControlListener = { refresh() }
            it.audioSessionId = audioSessionId
        }
        effect.created = true
        if (!effect.hasControl) return
        try {
            val platformEffect = effect.effect!!
            platformEffect.enabled = true
            val level = min(
                platformEffect.maxLevel.toFloat(),
                (single.mixerVolumeDb + ReplayGainUtil.amplToDb(single.outputVolume)) * 100f +
                    single.volumeBoostMb
            ).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            repeat(20) { platformEffect.level = level }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to update session Volume effect", e)
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            states.clear()
        }
        check(handler.post {
            dpe?.releaseSafe()
            dpe = null
            volume?.releaseSafe()
            volume = null
        }) { "Session boost handler is no longer running" }
    }
}
