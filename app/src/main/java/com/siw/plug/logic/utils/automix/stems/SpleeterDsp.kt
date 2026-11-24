package com.siw.plug.logic.utils.automix.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectral front/back end for the Spleeter 2-stem ONNX models, matching the reference pipeline in
 * k2-fsa/sherpa-onnx (n_fft 4096, hop 1024, periodic Hann, center=false, first 1024 bins fed to the
 * network, ratio masks applied to the complex STFT, bins >= 1024 zeroed as in the reference).
 * Pure Kotlin so it is unit-testable and has no real-time-thread involvement.
 */
object SpleeterDsp {
    const val SAMPLE_RATE = 44_100
    const val N_FFT = 4096
    const val HOP = 1024
    const val MODEL_BINS = 1024
    const val FFT_BINS = N_FFT / 2 + 1
    const val FRAMES_PER_CHUNK = 512
    const val SEGMENT_SAMPLES = N_FFT + (FRAMES_PER_CHUNK - 1) * HOP
    /** Samples at each segment edge discarded because overlap-add is incomplete there. */
    const val MARGIN = N_FFT
    const val SEGMENT_VALID = SEGMENT_SAMPLES - 2 * MARGIN
    private const val EPS = 1e-10f

    val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }

    private val cosTable = FloatArray(N_FFT / 2) { cos(2.0 * PI * it / N_FFT).toFloat() }
    private val sinTable = FloatArray(N_FFT / 2) { -sin(2.0 * PI * it / N_FFT).toFloat() }
    private val bitReverse = IntArray(N_FFT).also { table ->
        val bits = Integer.numberOfTrailingZeros(N_FFT)
        for (i in 0 until N_FFT) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Sum over frames of window^2 at each position of a full chunk; used for overlap-add normalisation. */
    private val windowEnergy: FloatArray = FloatArray(SEGMENT_SAMPLES).also { sum ->
        for (frame in 0 until FRAMES_PER_CHUNK) {
            val start = frame * HOP
            for (n in 0 until N_FFT) sum[start + n] += window[n] * window[n]
        }
    }

    /** In-place radix-2 FFT. [inverse] conjugates twiddles and scales by 1/N. */
    fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until N_FFT) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= N_FFT) {
            val half = size / 2
            val step = N_FFT / size
            var start = 0
            while (start < N_FFT) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = if (inverse) -sinTable[k] else sinTable[k]
                    val tr = re[j + half] * wr - im[j + half] * wi
                    val ti = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val scale = 1f / N_FFT
            for (i in 0 until N_FFT) { re[i] *= scale; im[i] *= scale }
        }
    }

    /** Complex spectrogram of one segment of one channel: [FRAMES_PER_CHUNK] x [FFT_BINS], row-major. */
    class ChannelSpectrum(val re: FloatArray, val im: FloatArray)

    fun stft(segment: FloatArray): ChannelSpectrum {
        require(segment.size == SEGMENT_SAMPLES) { "Segment must hold $SEGMENT_SAMPLES samples" }
        val outRe = FloatArray(FRAMES_PER_CHUNK * FFT_BINS)
        val outIm = FloatArray(FRAMES_PER_CHUNK * FFT_BINS)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (frame in 0 until FRAMES_PER_CHUNK) {
            val start = frame * HOP
            for (n in 0 until N_FFT) { re[n] = segment[start + n] * window[n]; im[n] = 0f }
            fft(re, im, inverse = false)
            System.arraycopy(re, 0, outRe, frame * FFT_BINS, FFT_BINS)
            System.arraycopy(im, 0, outIm, frame * FFT_BINS, FFT_BINS)
        }
        return ChannelSpectrum(outRe, outIm)
    }

    /** Network input: [2, 1, 512, 1024] magnitudes (channel-major). */
    fun modelInput(left: ChannelSpectrum, right: ChannelSpectrum): FloatArray {
        val input = FloatArray(2 * FRAMES_PER_CHUNK * MODEL_BINS)
        for ((c, spec) in listOf(left, right).withIndex()) {
            for (frame in 0 until FRAMES_PER_CHUNK) {
                val src = frame * FFT_BINS
                val dst = (c * FRAMES_PER_CHUNK + frame) * MODEL_BINS
                for (k in 0 until MODEL_BINS) {
                    input[dst + k] = sqrt(spec.re[src + k] * spec.re[src + k] + spec.im[src + k] * spec.im[src + k])
                }
            }
        }
        return input
    }

    /**
     * Normalises the two network outputs into complementary ratio masks in place:
     * vocals' = (v^2 + eps/2) / (v^2 + a^2 + eps) and likewise for accompaniment.
     */
    fun normaliseMasks(vocals: FloatArray, accompaniment: FloatArray) {
        require(vocals.size == accompaniment.size)
        for (i in vocals.indices) {
            val v2 = vocals[i] * vocals[i]
            val a2 = accompaniment[i] * accompaniment[i]
            val sum = v2 + a2 + EPS
            vocals[i] = (v2 + EPS / 2) / sum
            accompaniment[i] = (a2 + EPS / 2) / sum
        }
    }

    /**
     * Applies [mask] ([2][512][1024] channel-major) to [spectrum] for [channel] and returns the
     * time-domain segment of [SEGMENT_SAMPLES] samples via windowed overlap-add.
     */
    fun istft(spectrum: ChannelSpectrum, mask: FloatArray, channel: Int): FloatArray {
        val out = FloatArray(SEGMENT_SAMPLES)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (frame in 0 until FRAMES_PER_CHUNK) {
            val src = frame * FFT_BINS
            val maskBase = (channel * FRAMES_PER_CHUNK + frame) * MODEL_BINS
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (k in 0 until MODEL_BINS) {
                val m = mask[maskBase + k]
                val r = spectrum.re[src + k] * m
                val i = spectrum.im[src + k] * m
                re[k] = r
                im[k] = i
                if (k in 1 until N_FFT / 2) { re[N_FFT - k] = r; im[N_FFT - k] = -i }
            }
            fft(re, im, inverse = true)
            val start = frame * HOP
            for (n in 0 until N_FFT) out[start + n] += re[n] * window[n]
        }
        for (i in out.indices) out[i] /= windowEnergy[i].coerceAtLeast(1e-3f)
        return out
    }
}
