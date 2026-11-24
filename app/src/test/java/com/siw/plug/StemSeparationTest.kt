package com.siw.plug

import com.siw.plug.logic.utils.automix.stems.SpectrogramModel
import com.siw.plug.logic.utils.automix.stems.SpleeterDsp
import com.siw.plug.logic.utils.automix.stems.StemFailure
import com.siw.plug.logic.utils.automix.stems.StemCache
import com.siw.plug.logic.utils.automix.stems.StemKey
import com.siw.plug.logic.utils.automix.stems.StemModelFile
import com.siw.plug.logic.utils.automix.stems.StemModelIntegrityException
import com.siw.plug.logic.utils.automix.stems.StemModelSpec
import com.siw.plug.logic.utils.automix.stems.StemModelStore
import com.siw.plug.logic.utils.automix.stems.StemSeparationManager
import com.siw.plug.logic.utils.automix.stems.StemSeparator
import com.siw.plug.logic.utils.automix.stems.StemSink
import com.siw.plug.logic.utils.automix.stems.StemStatus
import com.siw.plug.logic.utils.automix.stems.StereoFrameQueue
import com.siw.plug.logic.utils.automix.stems.StereoLinearResampler
import com.siw.plug.logic.utils.automix.stems.StereoPcmSource
import com.siw.plug.logic.utils.automix.stems.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class StemSeparationTest {
    private val tmp = Files.createTempDirectory("stems-test").toFile()

    @After fun cleanup() { tmp.deleteRecursively() }

    private fun tone(hz: Double, frames: Int, amp: Double = 0.4) =
        FloatArray(frames) { (amp * sin(2 * PI * hz * it / SpleeterDsp.SAMPLE_RATE)).toFloat() }

    private class ArraySource(val l: FloatArray, val r: FloatArray, val chunk: Int = 70_001) : StereoPcmSource {
        var pos = 0
        override fun read(left: FloatArray, right: FloatArray, maxFrames: Int): Int {
            if (pos >= l.size) return -1
            val n = minOf(maxFrames, chunk, l.size - pos)
            System.arraycopy(l, pos, left, 0, n); System.arraycopy(r, pos, right, 0, n)
            pos += n
            return n
        }
    }

    private class CollectSink(total: Int) : StemSink {
        val vl = FloatArray(total); val vr = FloatArray(total); val al = FloatArray(total); val ar = FloatArray(total)
        var n = 0
        override fun write(vocalsL: FloatArray, vocalsR: FloatArray, accL: FloatArray, accR: FloatArray, count: Int) {
            System.arraycopy(vocalsL, 0, vl, n, count); System.arraycopy(vocalsR, 0, vr, n, count)
            System.arraycopy(accL, 0, al, n, count); System.arraycopy(accR, 0, ar, n, count)
            n += count
        }
    }

    /** Routes bins below [splitBin] to "vocals" and the rest to "accompaniment": a genuine spectral partition. */
    private class SplitModel(val splitBin: Int) : SpectrogramModel {
        override fun infer(magnitudes: FloatArray): Pair<FloatArray, FloatArray> {
            val v = FloatArray(magnitudes.size); val a = FloatArray(magnitudes.size)
            for (i in magnitudes.indices) {
                if (i % SpleeterDsp.MODEL_BINS < splitBin) v[i] = magnitudes[i] + 1e-3f else a[i] = magnitudes[i] + 1e-3f
            }
            return v to a
        }
        override fun close() {}
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    @Test fun fftMatchesToneAndRoundTrips() {
        val n = SpleeterDsp.N_FFT
        val re = FloatArray(n) { cos(2 * PI * 100 * it / n).toFloat() }
        val im = FloatArray(n)
        val original = re.copyOf()
        SpleeterDsp.fft(re, im, false)
        assertEquals(n / 2.0, re[100].toDouble(), 0.5)
        assertEquals(0.0, re[50].toDouble(), 0.01)
        SpleeterDsp.fft(re, im, true)
        for (i in 0 until n) assertEquals(original[i].toDouble(), re[i].toDouble(), 1e-3)
    }

    private fun cos(x: Double) = kotlin.math.cos(x)

    @Test fun separationPartitionsSpectrumAndReconstructsAcrossSegments() {
        val frames = SpleeterDsp.SEGMENT_VALID * 2 + 123_457 // three segments, ragged tail
        val low = tone(440.0, frames); val high = tone(5000.0, frames, 0.3)
        val mix = FloatArray(frames) { low[it] + high[it] }
        val sink = CollectSink(frames)
        val produced = StemSeparator(SplitModel(splitBin = 512)).separate(ArraySource(mix, mix), sink)
        assertEquals(frames.toLong(), produced)
        assertEquals(frames, sink.n)
        var maxErr = 0.0
        // the first/last 64 samples are excluded: the abrupt end of the synthetic tone is band-limited (>11 kHz is dropped by design)
        for (i in 64 until frames - 64) maxErr = maxOf(maxErr, abs((sink.vl[i] + sink.al[i] - mix[i]).toDouble()))
        val worst = (64 until frames - 64).maxByOrNull { abs((sink.vl[it] + sink.al[it] - mix[it]).toDouble()) }
        assertTrue("stems must sum back to the mix, max error $maxErr at $worst of $frames", maxErr < 0.01)
        assertEquals(sink.vl.size, frames)
    }

    @Test fun highBandGoesOnlyToAccompaniment() {
        val frames = SpleeterDsp.SEGMENT_VALID + 50_000
        val low = tone(440.0, frames); val high = tone(7000.0, frames, 0.3) // bin ~650
        val mix = FloatArray(frames) { low[it] + high[it] }
        val sink = CollectSink(frames)
        StemSeparator(SplitModel(splitBin = 512)).separate(ArraySource(mix, mix), sink)
        val mid = frames / 2
        val vocalsRms = rms(sink.vl, mid, mid + 20_000)
        val accRms = rms(sink.al, mid, mid + 20_000)
        assertEquals(0.4 / sqrt(2.0), vocalsRms, 0.01)
        assertEquals(0.3 / sqrt(2.0), accRms, 0.01)
        // cross-talk: the vocals stem must not contain the 7 kHz tone's energy
        val residual = FloatArray(20_000) { sink.vl[mid + it] - low[mid + it] }
        assertTrue(rms(residual, 0, residual.size) < 0.01)
    }

    @Test fun cancellationStopsBetweenSegments() {
        val frames = SpleeterDsp.SEGMENT_VALID * 3
        val mix = tone(300.0, frames)
        val inferences = AtomicInteger()
        val model = object : SpectrogramModel {
            override fun infer(magnitudes: FloatArray) = SplitModel(512).infer(magnitudes).also { inferences.incrementAndGet() }
            override fun close() {}
        }
        try {
            StemSeparator(model).separate(ArraySource(mix, mix), CollectSink(frames), isCancelled = { inferences.get() >= 1 })
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, inferences.get())
    }

    @Test fun resamplerPreservesDurationAndPitch() {
        val input = FloatArray(48_000) { (0.4 * sin(2 * PI * 1000.0 * it / 48_000)).toFloat() }
        val queue = StereoFrameQueue()
        val resampler = StereoLinearResampler(48_000, 44_100)
        for (x in input) resampler.process(x, x, queue)
        assertTrue(abs(queue.size - 44_100) <= 2)
        val l = FloatArray(queue.size); val r = FloatArray(queue.size)
        val n = queue.pop(l, r, l.size)
        var crossings = 0
        for (i in 1 until n) if (l[i - 1] < 0 && l[i] >= 0) crossings++
        assertEquals(1000.0, crossings.toDouble(), 2.0)
    }

    private fun key(id: String = "t1", content: String = "c1") = StemKey(id, content, "m", "v1")

    private fun writeEntry(cache: StemCache, key: StemKey, frames: Int = 1000): com.siw.plug.logic.utils.automix.stems.StemEntry {
        val w = cache.beginWrite(key)
        val x = FloatArray(frames) { 0.25f }
        w.write(x, x, x, x, frames)
        return w.commit()
    }

    @Test fun cacheRoundTripVerifyAndCorruption() {
        val cache = StemCache(File(tmp, "c"), maxBytes = 10_000_000)
        val entry = writeEntry(cache, key())
        assertEquals(1000L, entry.frames)
        val hit = cache.lookup(key())
        assertNotNull(hit)
        assertTrue(cache.verify(hit!!))
        assertNull(cache.lookup(key(content = "different")))
        // flip one byte: size is unchanged so lookup succeeds, checksum verification must fail
        hit.vocals.writeBytes(hit.vocals.readBytes().also { it[10] = (it[10] + 1).toByte() })
        assertFalse(cache.verify(cache.lookup(key())!!))
        // truncation is caught at lookup
        hit.vocals.writeBytes(ByteArray(10))
        assertNull(cache.lookup(key()))
    }

    @Test fun cacheEvictsLeastRecentlyUsedAndHonoursPins() {
        var now = 1000L
        val perEntry = 1000L * 4 * 2
        val cache = StemCache(File(tmp, "c"), maxBytes = perEntry * 2 + 10, clock = { now++ })
        writeEntry(cache, key("a")); writeEntry(cache, key("b"))
        cache.lookup(key("a")) // a is now more recent than b
        writeEntry(cache, key("c"))
        assertNotNull(cache.lookup(key("a"))); assertNull(cache.lookup(key("b"))); assertNotNull(cache.lookup(key("c")))
        cache.pin(key("a"))
        cache.lookup(key("c"))
        writeEntry(cache, key("d")) // a is least recent but pinned -> c is evicted instead
        assertNotNull(cache.lookup(key("a"))); assertNull(cache.lookup(key("c"))); assertNotNull(cache.lookup(key("d")))
        cache.unpin(key("a"))
    }

    @Test fun cacheRecoversFromInterruptedWritesAndAbort() {
        val root = File(tmp, "c")
        val cache = StemCache(root, maxBytes = 10_000_000)
        val w = cache.beginWrite(key("x"))
        w.write(FloatArray(10), FloatArray(10), FloatArray(10), FloatArray(10), 10)
        // process "dies" here: staging dir remains
        assertTrue(root.listFiles()!!.any { it.name.startsWith("tmp-") })
        val reopened = StemCache(root, maxBytes = 10_000_000)
        assertTrue(root.listFiles()!!.none { it.name.startsWith("tmp-") })
        assertTrue(reopened.entries().isEmpty())
        val w2 = reopened.beginWrite(key("y")); w2.abort()
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun spec(vararg payloads: ByteArray) = StemModelSpec(
        "t", "1", "MIT", "test",
        payloads.mapIndexed { i, p -> StemModelFile("f$i.onnx", "https://example.invalid/f$i", p.size.toLong(), sha256Hex(p)) },
    )

    @Test fun modelStoreDownloadsVerifiesAndRejectsCorruption() {
        val a = ByteArray(200_000) { it.toByte() }; val b = ByteArray(1000) { 7 }
        val spec = spec(a, b)
        val payloads = mapOf("https://example.invalid/f0" to a, "https://example.invalid/f1" to b)
        val store = StemModelStore(File(tmp, "m"), spec) { ByteArrayInputStream(payloads.getValue(it)) }
        assertFalse(store.isInstalled())
        var last = 0L
        store.download(onProgress = { done, _ -> last = done })
        assertEquals(spec.totalBytes, last)
        assertTrue(store.isInstalled() && store.verifyInstalled())

        val bad = StemModelStore(File(tmp, "m2"), spec) { ByteArrayInputStream(ByteArray(a.size) { 1 }) }
        try { bad.download(); fail() } catch (_: StemModelIntegrityException) {}
        assertFalse(bad.isInstalled())
        assertTrue(File(tmp, "m2").listFiles()!!.isEmpty())

        val cancelled = StemModelStore(File(tmp, "m3"), spec) { ByteArrayInputStream(payloads.getValue(it)) }
        try { cancelled.download(isCancelled = { true }); fail() } catch (_: CancellationException) {}
        assertTrue(File(tmp, "m3").listFiles()!!.isEmpty())
    }

    @Test fun pinnedModelSpecDescribesBothSpleeterFiles() {
        val spec = StemModelSpec.SPLEETER_2STEMS_FP32
        assertEquals(2, spec.files.size)
        assertTrue(spec.files.all { it.url.startsWith("https://") && it.sha256.length == 64 })
        assertTrue(spec.license.contains("MIT"))
    }

    private fun manager(
        scope: CoroutineScope,
        cache: StemCache,
        installed: Boolean = true,
        opens: AtomicInteger = AtomicInteger(),
        frames: Int = 60_000,
        model: () -> SpectrogramModel = { SplitModel(512) },
        eligible: Boolean = true,
    ): StemSeparationManager {
        val mix = tone(440.0, frames)
        return StemSeparationManager(
            scope, Dispatchers.Default, cache, "m", "v1", { installed },
            { opens.incrementAndGet(); model() }, { ArraySource(mix, mix) }, { "hash-$it" }, { eligible },
        )
    }

    private fun await(m: StemSeparationManager, id: String, pred: (StemStatus) -> Boolean): StemStatus = runBlocking {
        withTimeout(60_000) { m.statuses.first { s -> s[id]?.let(pred) == true } }.getValue(id)
    }

    @Test fun managerProducesReadyThenServesFromCacheWithoutRerunning() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opens = AtomicInteger()
        val cache = StemCache(File(tmp, "c"), 50_000_000)
        val m = manager(scope, cache, opens = opens)
        m.request("t1", "file:///t1")
        val ready = await(m, "t1") { it is StemStatus.Ready } as StemStatus.Ready
        assertEquals(60_000L, ready.entry.frames)
        assertTrue(cache.verify(ready.entry))
        assertEquals(1, opens.get())

        val second = manager(scope, cache, opens = opens)
        second.request("t1", "file:///t1")
        await(second, "t1") { it is StemStatus.Ready }
        assertEquals("cache hit must not run the model again", 1, opens.get())
        scope.cancel()
    }

    @Test fun managerReportsFailuresInsteadOfFakingStems() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = StemCache(File(tmp, "c"), 50_000_000)

        val missing = manager(scope, cache, installed = false)
        missing.request("a", "u")
        assertEquals(StemStatus.Failed(StemFailure.MODEL_MISSING, null), missing.statusOf("a"))

        val ineligible = manager(scope, cache, eligible = false)
        ineligible.request("a", "u")
        assertEquals(StemStatus.Failed(StemFailure.NOT_ELIGIBLE, null), ineligible.statusOf("a"))

        val broken = manager(scope, cache, model = { error("model exploded") })
        broken.request("b", "u")
        val failed = await(broken, "b") { it is StemStatus.Failed } as StemStatus.Failed
        assertEquals("model exploded", failed.detail)
        assertTrue(cache.entries().isEmpty())
        assertTrue(cache.entries().isEmpty() && File(tmp, "c").listFiles()!!.isEmpty())
        scope.cancel()
    }

    @Test fun managerCancellationAbortsWriteAndLeavesNoPartialEntry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = StemCache(File(tmp, "c"), 50_000_000)
        val inInference = java.util.concurrent.CountDownLatch(1)
        val blocking = object : SpectrogramModel {
            override fun infer(magnitudes: FloatArray): Pair<FloatArray, FloatArray> {
                inInference.countDown()
                Thread.sleep(30_000)
                return magnitudes to magnitudes
            }
            override fun close() {}
        }
        val m = manager(scope, cache, model = { blocking })
        m.request("t", "u")
        assertTrue(inInference.await(30, java.util.concurrent.TimeUnit.SECONDS))
        m.cancel("t")
        await(m, "t") { it is StemStatus.Cancelled }
        Thread.sleep(300)
        assertTrue(File(tmp, "c").listFiles()!!.isEmpty())
        // a cancelled track can be requested again
        m.request("t", "u")
        assertTrue(m.statusOf("t") !is StemStatus.Cancelled)
        m.cancelAll()
        scope.cancel()
    }

    @Test fun corruptCachedStemsAreInvalidatedAndRegenerated() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = StemCache(File(tmp, "c"), 50_000_000)
        val opens = AtomicInteger()
        val m = manager(scope, cache, opens = opens)
        m.request("t", "u")
        val ready = await(m, "t") { it is StemStatus.Ready } as StemStatus.Ready
        ready.entry.vocals.writeBytes(ready.entry.vocals.readBytes().also { it[100] = (it[100] + 3).toByte() })
        val again = manager(scope, cache, opens = opens)
        again.request("t", "u")
        val regenerated = await(again, "t") { it is StemStatus.Ready } as StemStatus.Ready
        assertTrue(cache.verify(regenerated.entry))
        assertEquals(2, opens.get())
        scope.cancel()
    }
}
