package com.siw.plug.logic.utils.automix.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ONNX Runtime (CPU) backend for the two Spleeter sub-models. Inference is expensive: only ever call
 * from a background dispatcher, never from the playback or UI thread.
 */
class OnnxSpleeterModel(vocalsFile: File, accompanimentFile: File, threads: Int = 2) : SpectrogramModel {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
    }
    private val vocals = env.createSession(vocalsFile.absolutePath, options)
    private val accompaniment = env.createSession(accompanimentFile.absolutePath, options)

    override fun infer(magnitudes: FloatArray): Pair<FloatArray, FloatArray> {
        val shape = longArrayOf(2, 1, SpleeterDsp.FRAMES_PER_CHUNK.toLong(), SpleeterDsp.MODEL_BINS.toLong())
        val direct = ByteBuffer.allocateDirect(magnitudes.size * 4).order(ByteOrder.nativeOrder())
        direct.asFloatBuffer().put(magnitudes)
        OnnxTensor.createTensor(env, direct.asFloatBuffer(), shape).use { input ->
            return run(vocals, input) to run(accompaniment, input)
        }
    }

    private fun run(session: OrtSession, input: OnnxTensor): FloatArray {
        val name = session.inputNames.first()
        session.run(mapOf(name to input)).use { result ->
            val buffer = (result[0] as OnnxTensor).floatBuffer
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
    }

    override fun close() {
        vocals.close()
        accompaniment.close()
        options.close()
    }
}
