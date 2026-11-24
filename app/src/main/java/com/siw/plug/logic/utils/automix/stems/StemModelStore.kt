package com.siw.plug.logic.utils.automix.stems

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

data class StemModelFile(val name: String, val url: String, val sizeBytes: Long, val sha256: String)

/**
 * A distributable separation model. Never bundled in the APK: it is downloaded only after explicit
 * user opt-in and every file is checked against a pinned SHA-256 before use.
 */
data class StemModelSpec(
    val id: String,
    val version: String,
    val license: String,
    val provenance: String,
    val files: List<StemModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    companion object {
        private const val HF_REVISION = "7001ba316a615cacddb3f9ef3ec416661a277e26"
        private const val HF_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems/resolve/$HF_REVISION/"

        const val VOCALS = "vocals.onnx"
        const val ACCOMPANIMENT = "accompaniment.onnx"

        /**
         * Deezer Spleeter 2-stems (MIT, https://github.com/deezer/spleeter/blob/master/LICENSE), converted to
         * fp32 ONNX by k2-fsa/sherpa-onnx (Apache-2.0; scripts/spleeter/export_onnx.py). The conversion repo
         * on Hugging Face carries no licence metadata of its own, so the MIT licence of the upstream weights
         * is the governing one.
         */
        val SPLEETER_2STEMS_FP32 = StemModelSpec(
            id = "spleeter-2stems",
            version = "sherpa-onnx-fp32-$HF_REVISION",
            license = "MIT (Deezer Spleeter); conversion scripts Apache-2.0 (k2-fsa/sherpa-onnx)",
            provenance = "huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems@$HF_REVISION",
            files = listOf(
                StemModelFile(VOCALS, HF_BASE + VOCALS, 39_318_336L,
                    "bdc16ab6bf6117ddd4842c19e80e40e2be188fc555295064d424616b0224ac97"),
                StemModelFile(ACCOMPANIMENT, HF_BASE + ACCOMPANIMENT, 39_318_343L,
                    "671ace17acd3720674a2bc14de32ac6292453dec20d9eb0ba4255d4ad8e3d8c0"),
            ),
        )
    }
}

class StemModelIntegrityException(message: String) : Exception(message)

/** Stores model files under [dir]. Downloads are staged as `.part` files and published only after hash verification. */
class StemModelStore(
    private val dir: File,
    val spec: StemModelSpec = StemModelSpec.SPLEETER_2STEMS_FP32,
    private val open: (String) -> InputStream = ::openHttps,
) {
    fun file(name: String) = File(dir, name)

    /** Cheap presence check (sizes). Use [verifyInstalled] for a full checksum. */
    fun isInstalled(): Boolean = spec.files.all { file(it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }

    fun verifyInstalled(): Boolean = spec.files.all { file(it.name).let { f -> f.isFile && sha256Hex(f) == it.sha256 } }

    fun delete() { spec.files.forEach { file(it.name).delete(); File(dir, it.name + ".part").delete() } }

    /**
     * Downloads missing or corrupt files. Blocking; call from a background dispatcher. Throws
     * [StemModelIntegrityException] if a downloaded file does not match its pinned hash (the file is discarded).
     */
    fun download(isCancelled: () -> Boolean = { false }, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        dir.mkdirs()
        var done = 0L
        val total = spec.totalBytes
        for (model in spec.files) {
            val target = file(model.name)
            if (target.isFile && target.length() == model.sizeBytes && sha256Hex(target) == model.sha256) {
                done += model.sizeBytes
                onProgress(done, total)
                continue
            }
            target.delete()
            val part = File(dir, model.name + ".part")
            part.delete()
            try {
                open(model.url).use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw CancellationException("Model download cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (part.length() != model.sizeBytes || sha256Hex(part) != model.sha256) {
                    throw StemModelIntegrityException("${model.name} failed size/SHA-256 verification")
                }
                check(part.renameTo(target)) { "Cannot publish ${model.name}" }
            } catch (t: Throwable) {
                part.delete()
                throw t
            }
        }
    }

    private companion object {
        fun openHttps(url: String): InputStream {
            require(url.startsWith("https://")) { "Model downloads must use HTTPS" }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} for model download" }
            return connection.inputStream
        }
    }
}
