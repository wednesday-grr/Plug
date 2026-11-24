package com.siw.plug.logic.utils.automix.stems

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

/** Identity of one cached stem pair. Changing any field produces a different cache key. */
data class StemKey(
    val trackId: String,
    val contentSha256: String,
    val modelId: String,
    val modelVersion: String,
) {
    val id: String = sha256Hex("$trackId|$contentSha256|$modelId|$modelVersion".toByteArray()).take(40)
}

data class StemEntry(
    val key: StemKey,
    val dir: File,
    val frames: Long,
    val sampleRate: Int,
    val channels: Int,
    val vocalsSha256: String,
    val accompanimentSha256: String,
    val bytes: Long,
    val createdAtMs: Long,
    val lastAccessMs: Long,
) {
    val vocals: File get() = File(dir, VOCALS_FILE)
    val accompaniment: File get() = File(dir, ACCOMPANIMENT_FILE)

    companion object {
        const val VOCALS_FILE = "vocals.pcm16"
        const val ACCOMPANIMENT_FILE = "accompaniment.pcm16"
    }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun sha256Hex(stream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().toHex()
}

fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

/**
 * Disk cache of separated stems (16-bit little-endian interleaved stereo PCM at 44.1 kHz, one file
 * per stem). Writes are staged in a temp directory and renamed, so a killed process never leaves a
 * half-written entry visible; leftovers are removed by [recover]. Eviction is least-recently-used,
 * skips pinned entries, and only ever deletes files inside [root] - source audio is never touched.
 */
class StemCache(
    private val root: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pins = HashMap<String, Int>()

    init {
        require(maxBytes > 0)
        root.mkdirs()
        recover()
    }

    /** Removes interrupted staging directories and entries whose metadata or files are missing/corrupt. */
    @Synchronized
    fun recover() {
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (dir.name.startsWith(STAGING_PREFIX) || readEntry(dir) == null) dir.deleteRecursively()
        }
    }

    /** Returns the entry (and marks it recently used) if it exists and its files have the recorded sizes. */
    @Synchronized
    fun lookup(key: StemKey): StemEntry? {
        val dir = File(root, key.id)
        val entry = readEntry(dir) ?: return null
        if (entry.key != key) return null
        val touched = entry.copy(lastAccessMs = clock())
        writeMeta(touched)
        return touched
    }

    /** Full checksum verification; call before trusting a cached entry for playback. */
    fun verify(entry: StemEntry): Boolean =
        entry.vocals.isFile && entry.accompaniment.isFile &&
            sha256Hex(entry.vocals) == entry.vocalsSha256 &&
            sha256Hex(entry.accompaniment) == entry.accompanimentSha256

    @Synchronized
    fun pin(key: StemKey) { pins[key.id] = (pins[key.id] ?: 0) + 1 }

    @Synchronized
    fun unpin(key: StemKey) {
        val count = (pins[key.id] ?: return) - 1
        if (count <= 0) pins.remove(key.id) else pins[key.id] = count
    }

    @Synchronized
    fun totalBytes(): Long = entries().sumOf { it.bytes }

    @Synchronized
    fun entries(): List<StemEntry> = root.listFiles()?.mapNotNull { if (it.isDirectory) readEntry(it) else null }.orEmpty()

    @Synchronized
    fun remove(key: StemKey): Boolean = File(root, key.id).takeIf { it.isDirectory }?.deleteRecursively() ?: false

    /** Opens a staged writer; call [Writer.commit] on success or [Writer.abort] otherwise. */
    fun beginWrite(key: StemKey): Writer = Writer(key, File(root, STAGING_PREFIX + key.id + "-" + System.nanoTime()))

    inner class Writer internal constructor(private val key: StemKey, private val staging: File) : StemSink {
        private val vocalsOut: BufferedOutputStream
        private val accOut: BufferedOutputStream
        private val vocalsDigest = MessageDigest.getInstance("SHA-256")
        private val accDigest = MessageDigest.getInstance("SHA-256")
        private var frames = 0L
        private var closed = false

        init {
            check(staging.mkdirs()) { "Cannot create staging directory" }
            vocalsOut = BufferedOutputStream(FileOutputStream(File(staging, StemEntry.VOCALS_FILE)), 64 * 1024)
            accOut = BufferedOutputStream(FileOutputStream(File(staging, StemEntry.ACCOMPANIMENT_FILE)), 64 * 1024)
        }

        override fun write(vocalsL: FloatArray, vocalsR: FloatArray, accL: FloatArray, accR: FloatArray, count: Int) {
            writePcm(vocalsOut, vocalsDigest, vocalsL, vocalsR, count)
            writePcm(accOut, accDigest, accL, accR, count)
            frames += count
        }

        private fun writePcm(out: BufferedOutputStream, digest: MessageDigest, l: FloatArray, r: FloatArray, count: Int) {
            val bytes = ByteArray(count * 4)
            for (i in 0 until count) {
                val a = pcm16(l[i]); val b = pcm16(r[i])
                bytes[i * 4] = a.toByte(); bytes[i * 4 + 1] = (a shr 8).toByte()
                bytes[i * 4 + 2] = b.toByte(); bytes[i * 4 + 3] = (b shr 8).toByte()
            }
            out.write(bytes)
            digest.update(bytes)
        }

        private fun pcm16(x: Float): Int = (x * 32767f).toInt().coerceIn(-32768, 32767)

        @Synchronized
        fun commit(): StemEntry {
            check(!closed) { "Writer already closed" }
            closed = true
            vocalsOut.close(); accOut.close()
            val now = clock()
            val dir = File(root, key.id)
            val entry = StemEntry(
                key = key, dir = dir, frames = frames, sampleRate = SpleeterDsp.SAMPLE_RATE, channels = 2,
                vocalsSha256 = vocalsDigest.digest().toHex(), accompanimentSha256 = accDigest.digest().toHex(),
                bytes = frames * 4 * 2, createdAtMs = now, lastAccessMs = now,
            )
            synchronized(this@StemCache) {
                dir.deleteRecursively()
                writeMetaTo(staging, entry)
                check(staging.renameTo(dir)) { "Cannot publish stem entry" }
                evict(protect = key.id)
            }
            return entry
        }

        fun abort() {
            if (closed) return
            closed = true
            runCatching { vocalsOut.close() }
            runCatching { accOut.close() }
            staging.deleteRecursively()
        }
    }

    private fun evict(protect: String) {
        var total = entries().sumOf { it.bytes }
        if (total <= maxBytes) return
        for (entry in entries().sortedBy { it.lastAccessMs }) {
            if (total <= maxBytes) break
            if (entry.key.id == protect || (pins[entry.key.id] ?: 0) > 0) continue
            if (entry.dir.deleteRecursively()) total -= entry.bytes
        }
    }

    private fun writeMeta(entry: StemEntry) = writeMetaTo(entry.dir, entry)

    private fun writeMetaTo(dir: File, entry: StemEntry) {
        val p = Properties()
        p["schema"] = SCHEMA.toString()
        p["trackId"] = entry.key.trackId
        p["contentSha256"] = entry.key.contentSha256
        p["modelId"] = entry.key.modelId
        p["modelVersion"] = entry.key.modelVersion
        p["frames"] = entry.frames.toString()
        p["sampleRate"] = entry.sampleRate.toString()
        p["channels"] = entry.channels.toString()
        p["vocalsSha256"] = entry.vocalsSha256
        p["accompanimentSha256"] = entry.accompanimentSha256
        p["bytes"] = entry.bytes.toString()
        p["createdAtMs"] = entry.createdAtMs.toString()
        p["lastAccessMs"] = entry.lastAccessMs.toString()
        val tmp = File(dir, "$META_FILE.tmp")
        tmp.outputStream().use { p.store(it, null) }
        val target = File(dir, META_FILE)
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "Cannot write stem metadata" } }
    }

    private fun readEntry(dir: File): StemEntry? = runCatching {
        val p = Properties()
        File(dir, META_FILE).inputStream().use { p.load(it) }
        if (p.getProperty("schema") != SCHEMA.toString()) return null
        val key = StemKey(p.getProperty("trackId"), p.getProperty("contentSha256"), p.getProperty("modelId"), p.getProperty("modelVersion"))
        if (key.id != dir.name) return null
        val frames = p.getProperty("frames").toLong()
        val entry = StemEntry(
            key, dir, frames, p.getProperty("sampleRate").toInt(), p.getProperty("channels").toInt(),
            p.getProperty("vocalsSha256"), p.getProperty("accompanimentSha256"),
            p.getProperty("bytes").toLong(), p.getProperty("createdAtMs").toLong(), p.getProperty("lastAccessMs").toLong(),
        )
        val expected = frames * 4
        if (entry.vocals.length() != expected || entry.accompaniment.length() != expected) return null
        entry
    }.getOrNull()

    private companion object {
        const val STAGING_PREFIX = "tmp-"
        const val META_FILE = "meta.properties"
        const val SCHEMA = 1
    }
}
