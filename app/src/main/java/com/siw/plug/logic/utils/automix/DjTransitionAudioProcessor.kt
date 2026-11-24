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

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class TransitionProcessorEffect {
    FILTER_HIGH_PASS,
    FILTER_LOW_PASS,
    BASS,
    VOCAL,
    EQ,
    ECHO,
    REVERB,
}

data class TransitionProcessorRuntimeSnapshot(
    val configured: Boolean,
    val sampleRate: Int?,
    val processedFrames: Long,
    val activeEffects: Set<TransitionProcessorEffect>,
)

/** Per-deck stereo PCM effects processor. Setters publish configuration to the audio thread. */
class DjTransitionAudioProcessor(
    private val forcePcm16Output: Boolean = false,
) : BaseAudioProcessor() {
    enum class Type { HIGH_PASS, LOW_PASS }

    private data class Request(
        val type: Type?,
        val cutoffHz: Float,
        val intensity: Float,
        val bassGain: Float,
        val echoEnabled: Boolean,
        val delayMs: Float,
        val feedback: Float,
        val wetMix: Float,
        val dryGain: Float,
        val vocalAttenuation: Float,
        val outputGain: Float,
        val reverbEnabled: Boolean,
        val reverbWet: Float,
        val eqLow: Float,
        val eqMid: Float,
        val eqHigh: Float,
        val version: Long,
        val stateVersion: Long,
        val bassStateVersion: Long,
        val echoStateVersion: Long,
        val vocalStateVersion: Long,
    )

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    @Volatile
    private var request = Request(
        null, 0f, 0f, 1f, false, 375f, 0.45f, 0f, 1f, 0f, 1f,
        false, 0f, 1f, 1f, 1f, 0L, 0L, 0L, 0L, 0L,
    )
    private var appliedVersion = -1L
    private var appliedStateVersion = -1L
    private var appliedBassStateVersion = -1L
    private var appliedEchoStateVersion = -1L
    private var appliedVocalStateVersion = -1L
    private var appliedType: Type? = null
    private var appliedCutoffHz: Float? = null
    private var appliedBassGain: Float? = null
    private var appliedVocalAttenuation: Float? = null
    private var coefficients: Coefficients? = null
    private var bassCoefficients: Coefficients? = null
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)
    private val bassZ1 = DoubleArray(2)
    private val bassZ2 = DoubleArray(2)
    private var delayBuffer = DoubleArray(0)
    private var delayPosition = 0
    private val dampedFeedback = DoubleArray(2)
    private var dampingCoefficient = 0.0
    private var appliedDelayFrames = 0
    private var vocalCoefficients: Coefficients? = null
    private val vocalZ1 = DoubleArray(2)
    private val vocalZ2 = DoubleArray(2)
    private var reverbBuffers = emptyArray<DoubleArray>()
    private val reverbPositions = IntArray(4)
    private val eqZ1 = Array(3) { DoubleArray(2) }
    private val eqZ2 = Array(3) { DoubleArray(2) }
    private var eqCoefficients = emptyArray<Coefficients>()
    private val eqMix = DoubleArray(3)
    @Volatile
    private var runtimeConfigured = false
    @Volatile
    private var runtimeSampleRate = 0
    @Volatile
    private var runtimeProcessedFrames = 0L
    @Volatile
    private var runtimeEffectMask = 0

    fun runtimeSnapshot(): TransitionProcessorRuntimeSnapshot {
        val mask = runtimeEffectMask
        val effects = buildSet {
            if (mask and FILTER_HIGH_PASS_MASK != 0) add(TransitionProcessorEffect.FILTER_HIGH_PASS)
            if (mask and FILTER_LOW_PASS_MASK != 0) add(TransitionProcessorEffect.FILTER_LOW_PASS)
            if (mask and BASS_MASK != 0) add(TransitionProcessorEffect.BASS)
            if (mask and VOCAL_MASK != 0) add(TransitionProcessorEffect.VOCAL)
            if (mask and EQ_MASK != 0) add(TransitionProcessorEffect.EQ)
            if (mask and ECHO_MASK != 0) add(TransitionProcessorEffect.ECHO)
            if (mask and REVERB_MASK != 0) add(TransitionProcessorEffect.REVERB)
        }
        return TransitionProcessorRuntimeSnapshot(
            runtimeConfigured,
            runtimeSampleRate.takeIf { it > 0 },
            runtimeProcessedFrames,
            effects,
        )
    }

    fun setFilter(type: Type, cutoffHz: Float, intensity: Float = 1f) {
        require(cutoffHz.isFinite() && cutoffHz > 0f)
        require(intensity.isFinite() && intensity in 0f..1f)
        val previous = request
        if (previous.type == type && previous.cutoffHz == cutoffHz &&
            previous.intensity == intensity
        ) return
        val stateVersion = if (previous.type != type || previous.intensity == 0f || intensity == 0f)
            previous.stateVersion + 1 else previous.stateVersion
        request = previous.copy(
            type = type,
            cutoffHz = cutoffHz,
            intensity = intensity,
            version = previous.version + 1,
            stateVersion = stateVersion,
        )
    }

    fun setBassGain(bassGain: Float) {
        require(bassGain.isFinite() && bassGain in 0f..1f)
        val previous = request
        if (previous.bassGain == bassGain) return
        request = previous.copy(
            bassGain = bassGain,
            version = previous.version + 1,
            bassStateVersion = if ((previous.bassGain == 1f) != (bassGain == 1f))
                previous.bassStateVersion + 1 else previous.bassStateVersion,
        )
    }

    fun setEcho(
        delayMs: Float,
        feedback: Float = DEFAULT_ECHO_FEEDBACK,
        wetMix: Float,
        dryGain: Float = 1f,
    ) {
        require(delayMs.isFinite() && delayMs in MIN_ECHO_DELAY_MS..MAX_ECHO_DELAY_MS)
        require(feedback.isFinite() && feedback in 0f..MAX_ECHO_FEEDBACK)
        require(wetMix.isFinite() && wetMix in 0f..1f)
        require(dryGain.isFinite() && dryGain in 0f..1f)
        val previous = request
        if (previous.echoEnabled && previous.delayMs == delayMs &&
            previous.feedback == feedback && previous.wetMix == wetMix &&
            previous.dryGain == dryGain
        ) return
        val resetEcho = !previous.echoEnabled || previous.delayMs != delayMs
        request = previous.copy(
            echoEnabled = true,
            delayMs = delayMs,
            feedback = feedback,
            wetMix = wetMix,
            dryGain = dryGain,
            version = previous.version + 1,
            echoStateVersion = if (resetEcho) previous.echoStateVersion + 1
                else previous.echoStateVersion,
        )
    }

    fun setVocalAttenuation(vocalAttenuation: Float) {
        require(vocalAttenuation.isFinite() && vocalAttenuation in 0f..1f)
        val previous = request
        if (previous.vocalAttenuation == vocalAttenuation) return
        request = previous.copy(
            vocalAttenuation = vocalAttenuation,
            version = previous.version + 1,
            vocalStateVersion = if ((previous.vocalAttenuation == 0f) !=
                (vocalAttenuation == 0f)
            ) previous.vocalStateVersion + 1 else previous.vocalStateVersion,
        )
    }

    fun setOutputGain(outputGain: Float) {
        require(outputGain.isFinite() && outputGain in 0f..1f)
        val previous = request
        if (previous.outputGain == outputGain) return
        request = previous.copy(outputGain = outputGain, version = previous.version + 1)
    }

    fun setReverb(wetMix: Float) {
        require(wetMix.isFinite() && wetMix in 0f..1f)
        val previous = request
        if (previous.reverbEnabled && previous.reverbWet == wetMix) return
        request = previous.copy(
            reverbEnabled = true, reverbWet = wetMix, version = previous.version + 1
        )
    }

    fun setTransitionEq(low: Float, mid: Float, high: Float) {
        require(listOf(low, mid, high).all { it.isFinite() && it in 0f..1f })
        val previous = request
        if (previous.eqLow == low && previous.eqMid == mid && previous.eqHigh == high) return
        request = previous.copy(eqLow = low, eqMid = mid, eqHigh = high,
            version = previous.version + 1)
    }

    fun bypass() {
        val previous = request
        if (previous.type == null && previous.intensity == 0f && previous.bassGain == 1f &&
            !previous.echoEnabled && previous.vocalAttenuation == 0f &&
            previous.outputGain == 1f && !previous.reverbEnabled &&
            previous.eqLow == 1f && previous.eqMid == 1f && previous.eqHigh == 1f
        ) return
        request = Request(
            null, 0f, 0f, 1f, false, 375f, DEFAULT_ECHO_FEEDBACK, 0f, 1f, 0f, 1f,
            false, 0f, 1f, 1f, 1f,
            previous.version + 1, previous.stateVersion + 1,
            previous.bassStateVersion + 1, previous.echoStateVersion + 1,
            previous.vocalStateVersion + 1,
        )
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount != 2 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
        ) {
            runtimeConfigured = false
            runtimeSampleRate = 0
            runtimeEffectMask = 0
            return AudioProcessor.AudioFormat.NOT_SET
        }
        runtimeConfigured = true
        runtimeSampleRate = inputAudioFormat.sampleRate
        return if (forcePcm16Output) {
            AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
        } else inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        clearState()
        clearBassState()
        clearEchoState()
        clearVocalState()
        clearReverbState()
        clearEqState()
        runtimeEffectMask = 0
        appliedVersion = -1L
        appliedStateVersion = -1L
        appliedBassStateVersion = -1L
        appliedEchoStateVersion = -1L
        appliedVocalStateVersion = -1L
        appliedType = null
        appliedCutoffHz = null
        appliedBassGain = null
        appliedVocalAttenuation = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val inputBytesPerFrame = inputAudioFormat.bytesPerFrame
        require(remaining % inputBytesPerFrame == 0) { "PCM input must contain complete frames" }
        val frameCount = remaining / inputBytesPerFrame

        val current = request
        if (eqCoefficients.isEmpty() &&
            (current.eqLow != 1f || current.eqMid != 1f || current.eqHigh != 1f)
        ) {
            eqCoefficients = arrayOf(
                designBassShelf(0.25f, inputAudioFormat.sampleRate),
                designVocalAttenuation(0.5f, inputAudioFormat.sampleRate),
                designHighShelf(inputAudioFormat.sampleRate),
            )
        }
        if (current.version != appliedVersion) {
            if (current.reverbEnabled && reverbBuffers.isEmpty()) {
                reverbBuffers = doubleArrayOf(0.0297, 0.0371, 0.0411, 0.0437).map {
                    DoubleArray((it * inputAudioFormat.sampleRate).toInt().coerceAtLeast(1) * 2)
                }.toTypedArray()
                reverbPositions.fill(0)
            } else if (!current.reverbEnabled && reverbBuffers.isNotEmpty()) {
                clearReverbState()
            }
            if (current.stateVersion != appliedStateVersion) clearState()
            if (current.type != appliedType || current.cutoffHz != appliedCutoffHz) {
                coefficients = if (current.type != null && current.intensity > 0f) {
                    design(current.type, current.cutoffHz, inputAudioFormat.sampleRate)
                } else null
                appliedType = current.type
                appliedCutoffHz = current.cutoffHz
            }
            if (current.bassGain != appliedBassGain) {
                bassCoefficients = if (current.bassGain < 1f) {
                    designBassShelf(current.bassGain, inputAudioFormat.sampleRate)
                } else null
                appliedBassGain = current.bassGain
            }
            if (current.bassStateVersion != appliedBassStateVersion) clearBassState()
            val delayFrames = if (current.echoEnabled) {
                (current.delayMs * inputAudioFormat.sampleRate / 1000f).toInt()
                    .coerceIn(1, inputAudioFormat.sampleRate * MAX_ECHO_DELAY_MS.toInt() / 1000)
            } else 0
            if (current.echoStateVersion != appliedEchoStateVersion ||
                delayFrames != appliedDelayFrames
            ) {
                if (current.echoEnabled) delayBuffer = DoubleArray(delayFrames * 2)
                else delayBuffer = DoubleArray(0)
                appliedDelayFrames = delayFrames
                clearEchoState()
                dampingCoefficient = 1.0 - exp(
                    -2.0 * PI * ECHO_DAMPING_HZ / inputAudioFormat.sampleRate
                )
            }
            if (current.vocalStateVersion != appliedVocalStateVersion) {
                clearVocalState()
            }
            if (current.vocalAttenuation != appliedVocalAttenuation) {
                vocalCoefficients = if (current.vocalAttenuation > 0f) {
                    designVocalAttenuation(current.vocalAttenuation, inputAudioFormat.sampleRate)
                } else null
                appliedVocalAttenuation = current.vocalAttenuation
            }
            appliedVersion = current.version
            appliedStateVersion = current.stateVersion
            appliedBassStateVersion = current.bassStateVersion
            appliedEchoStateVersion = current.echoStateVersion
            appliedVocalStateVersion = current.vocalStateVersion
        }

        val output = replaceOutputBuffer(frameCount * outputAudioFormat.bytesPerFrame)
            .order(ByteOrder.nativeOrder())
        val filter = coefficients
        val bassFilter = bassCoefficients
        if ((filter == null || current.intensity == 0f) &&
            (bassFilter == null || current.bassGain == 1f) &&
            !current.echoEnabled && current.vocalAttenuation == 0f &&
            current.outputGain == 1f && !current.reverbEnabled &&
            current.eqLow == 1f && current.eqMid == 1f && current.eqHigh == 1f &&
            eqMix.all { it < 0.00001 } &&
            outputAudioFormat == inputAudioFormat
        ) {
            if (eqCoefficients.isNotEmpty()) clearEqState()
            output.put(inputBuffer).flip()
            runtimeProcessedFrames += frameCount
            runtimeEffectMask = 0
            return
        }

        val input = inputBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        val eqSmoothing = 1.0 - exp(-1.0 / (0.01 * inputAudioFormat.sampleRate))
        while (input.hasRemaining()) {
            val left = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> {
                    input.short / 32768.0
                }
                C.ENCODING_PCM_FLOAT -> input.float.toDouble()
                else -> error("Unsupported PCM encoding")
            }
            val right = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> input.short / 32768.0
                C.ENCODING_PCM_FLOAT -> input.float.toDouble()
                else -> error("Unsupported PCM encoding")
            }
            var processedLeft = processChannel(left, 0, current, filter, bassFilter)
            var processedRight = processChannel(right, 1, current, filter, bassFilter)
            for (band in eqCoefficients.indices) {
                val target = when (band) {
                    0 -> 1f - current.eqLow
                    1 -> 1f - current.eqMid
                    else -> 1f - current.eqHigh
                }
                eqMix[band] += (target - eqMix[band]) * eqSmoothing
                val leftEq = process(processedLeft, 0, eqCoefficients[band], eqZ1[band], eqZ2[band])
                val rightEq = process(processedRight, 1, eqCoefficients[band], eqZ1[band], eqZ2[band])
                processedLeft += (leftEq - processedLeft) * eqMix[band]
                processedRight += (rightEq - processedRight) * eqMix[band]
            }
            if (current.vocalAttenuation > 0f) {
                val mid = (processedLeft + processedRight) * 0.5
                val side = (processedLeft - processedRight) * 0.5
                val attenuatedMid = process(
                    mid, 0, checkNotNull(vocalCoefficients), vocalZ1, vocalZ2
                )
                processedLeft = attenuatedMid + side
                processedRight = attenuatedMid - side
            }
            if (current.echoEnabled) {
                val leftIndex = delayPosition * 2
                val rightIndex = leftIndex + 1
                val delayedLeft = delayBuffer[leftIndex]
                val delayedRight = delayBuffer[rightIndex]
                dampedFeedback[0] += dampingCoefficient * (delayedLeft - dampedFeedback[0])
                dampedFeedback[1] += dampingCoefficient * (delayedRight - dampedFeedback[1])
                delayBuffer[leftIndex] = processedLeft + dampedFeedback[0] * current.feedback
                delayBuffer[rightIndex] = processedRight + dampedFeedback[1] * current.feedback
                processedLeft = processedLeft * current.dryGain + delayedLeft * current.wetMix
                processedRight = processedRight * current.dryGain + delayedRight * current.wetMix
                delayPosition++
                if (delayPosition == appliedDelayFrames) delayPosition = 0
            }
            if (current.reverbEnabled) {
                var wetLeft = 0.0
                var wetRight = 0.0
                for (i in reverbBuffers.indices) {
                    val buffer = reverbBuffers[i]
                    val position = reverbPositions[i]
                    val left = buffer[position]
                    val right = buffer[position + 1]
                    buffer[position] = processedLeft + left * 0.72
                    buffer[position + 1] = processedRight + right * 0.72
                    wetLeft += left
                    wetRight += right
                    reverbPositions[i] = (position + 2) % buffer.size
                }
                processedLeft = processedLeft * (1f - current.reverbWet) + wetLeft * current.reverbWet * 0.25
                processedRight = processedRight * (1f - current.reverbWet) + wetRight * current.reverbWet * 0.25
            }
            processedLeft = (processedLeft * current.outputGain).coerceIn(-1.0, 1.0)
            processedRight = (processedRight * current.outputGain).coerceIn(-1.0, 1.0)
            when (outputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> {
                    output.putShort(toPcm16(processedLeft))
                    output.putShort(toPcm16(processedRight))
                }
                C.ENCODING_PCM_FLOAT -> {
                    output.putFloat(processedLeft.toFloat())
                    output.putFloat(processedRight.toFloat())
                }
            }
        }
        inputBuffer.position(input.position())
        output.flip()
        runtimeProcessedFrames += frameCount
        runtimeEffectMask = runtimeMask(current, filter, bassFilter)
    }

    override fun onReset() {
        clearState()
        clearBassState()
        clearEchoState()
        clearVocalState()
        clearReverbState()
        clearEqState()
        runtimeConfigured = false
        runtimeSampleRate = 0
        runtimeEffectMask = 0
        coefficients = null
        bassCoefficients = null
        appliedVersion = -1L
        appliedStateVersion = -1L
        appliedBassStateVersion = -1L
        appliedEchoStateVersion = -1L
        appliedVocalStateVersion = -1L
        appliedType = null
        appliedCutoffHz = null
        appliedBassGain = null
        appliedDelayFrames = 0
        vocalCoefficients = null
        appliedVocalAttenuation = null
    }

    private fun runtimeMask(
        current: Request,
        filter: Coefficients?,
        bassFilter: Coefficients?,
    ): Int {
        var mask = 0
        if (filter != null && current.intensity > 0f) {
            mask = mask or when (current.type) {
                Type.HIGH_PASS -> FILTER_HIGH_PASS_MASK
                Type.LOW_PASS -> FILTER_LOW_PASS_MASK
                null -> 0
            }
        }
        if (bassFilter != null && current.bassGain < 1f) mask = mask or BASS_MASK
        if (current.vocalAttenuation > 0f) mask = mask or VOCAL_MASK
        if (current.eqLow < 1f || current.eqMid < 1f || current.eqHigh < 1f) mask = mask or EQ_MASK
        if (current.echoEnabled && (current.wetMix > 0f || current.dryGain < 1f)) mask = mask or ECHO_MASK
        if (current.reverbEnabled && current.reverbWet > 0f) mask = mask or REVERB_MASK
        return mask
    }

    private fun processChannel(
        input: Double,
        channel: Int,
        current: Request,
        filter: Coefficients?,
        bassFilter: Coefficients?,
    ): Double {
        var sample = input
        if (bassFilter != null && current.bassGain < 1f) {
            sample = process(sample, channel, bassFilter, bassZ1, bassZ2)
        }
        if (filter != null && current.intensity > 0f) {
            val filtered = process(sample, channel, filter, z1, z2)
            sample += (filtered - sample) * current.intensity
        }
        return sample
    }

    private fun toPcm16(sample: Double): Short =
        (sample.coerceIn(-1.0, 1.0) * 32768.0).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun process(
        input: Double,
        channel: Int,
        filter: Coefficients,
        state1: DoubleArray,
        state2: DoubleArray,
    ): Double {
        val output = filter.b0 * input + state1[channel]
        state1[channel] = filter.b1 * input - filter.a1 * output + state2[channel]
        state2[channel] = filter.b2 * input - filter.a2 * output
        return output
    }

    private fun clearState() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    private fun clearBassState() {
        bassZ1.fill(0.0)
        bassZ2.fill(0.0)
    }

    private fun clearEchoState() {
        delayBuffer.fill(0.0)
        delayPosition = 0
        dampedFeedback.fill(0.0)
    }

    private fun clearVocalState() {
        vocalZ1.fill(0.0)
        vocalZ2.fill(0.0)
    }

    private fun clearEqState() {
        eqZ1.forEach { it.fill(0.0) }
        eqZ2.forEach { it.fill(0.0) }
        eqMix.fill(0.0)
        eqCoefficients = emptyArray()
    }

    private fun clearReverbState() {
        reverbBuffers = emptyArray()
        reverbPositions.fill(0)
    }

    private fun designVocalAttenuation(
        attenuation: Float,
        sampleRate: Int,
    ): Coefficients {
        // This broad peaking cut spans roughly 250 Hz–4 kHz without subtracting phase-shifted audio.
        val amplitude = 10.0.pow(-VOCAL_ATTENUATION_DB * attenuation / 40.0)
        val omega = 2.0 * PI * VOCAL_CENTER_HZ / sampleRate
        val cosine = cos(omega)
        val alpha = sin(omega) / (2.0 * VOCAL_Q)
        val a0 = 1.0 + alpha / amplitude
        return Coefficients(
            (1.0 + alpha * amplitude) / a0,
            -2.0 * cosine / a0,
            (1.0 - alpha * amplitude) / a0,
            -2.0 * cosine / a0,
            (1.0 - alpha / amplitude) / a0,
        )
    }

    private fun design(type: Type, cutoffHz: Float, sampleRate: Int): Coefficients {
        val cutoff = cutoffHz.toDouble().coerceIn(1.0, sampleRate / 2.0 * 0.99)
        val omega = 2.0 * PI * cutoff / sampleRate
        val cosine = cos(omega)
        val alpha = sin(omega) / (2.0 / sqrt(2.0))
        val a0 = 1.0 + alpha
        val (b0, b1, b2) = when (type) {
            Type.LOW_PASS -> Triple((1.0 - cosine) / 2.0, 1.0 - cosine, (1.0 - cosine) / 2.0)
            Type.HIGH_PASS -> Triple((1.0 + cosine) / 2.0, -(1.0 + cosine), (1.0 + cosine) / 2.0)
        }
        return Coefficients(
            b0 / a0,
            b1 / a0,
            b2 / a0,
            -2.0 * cosine / a0,
            (1.0 - alpha) / a0,
        )
    }

    private fun designBassShelf(bassGain: Float, sampleRate: Int): Coefficients {
        val gain = bassGain.toDouble().coerceAtLeast(MIN_BASS_GAIN)
        val amplitude = sqrt(gain)
        val omega = 2.0 * PI * BASS_CUTOFF_HZ / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val shelfSlope = 1.0
        val alpha = sine / 2.0 * sqrt(
            (amplitude + 1.0 / amplitude) * (1.0 / shelfSlope - 1.0) + 2.0
        )
        val beta = 2.0 * sqrt(amplitude) * alpha
        val a0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + beta
        return Coefficients(
            amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + beta) / a0,
            2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine) / a0,
            amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta) / a0,
            -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine) / a0,
            ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta) / a0,
        )
    }

    private fun designHighShelf(sampleRate: Int): Coefficients {
        val amplitude = 0.5
        val omega = 2.0 * PI * minOf(6000.0, sampleRate * 0.45) / sampleRate
        val cosine = cos(omega)
        val alpha = sin(omega) / 2.0 * sqrt(2.0)
        val beta = 2.0 * sqrt(amplitude) * alpha
        val a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta
        return Coefficients(
            amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta) / a0,
            -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine) / a0,
            amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta) / a0,
            2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine) / a0,
            ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta) / a0,
        )
    }

    private companion object {
        const val FILTER_HIGH_PASS_MASK = 1
        const val FILTER_LOW_PASS_MASK = 1 shl 1
        const val BASS_MASK = 1 shl 2
        const val VOCAL_MASK = 1 shl 3
        const val EQ_MASK = 1 shl 4
        const val ECHO_MASK = 1 shl 5
        const val REVERB_MASK = 1 shl 6
        const val BASS_CUTOFF_HZ = 200f
        const val MIN_BASS_GAIN = 0.0001
        const val MIN_ECHO_DELAY_MS = 1f
        const val MAX_ECHO_DELAY_MS = 2000f
        const val DEFAULT_ECHO_FEEDBACK = 0.45f
        const val MAX_ECHO_FEEDBACK = 0.95f
        const val ECHO_DAMPING_HZ = 4500.0
        const val VOCAL_CENTER_HZ = 1000.0
        const val VOCAL_Q = 0.7
        const val VOCAL_ATTENUATION_DB = 24.0
    }
}
