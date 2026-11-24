package com.siw.plug.logic.utils.automix.stems

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Wires the stem pipeline to app storage. Not yet connected to settings/UI/playback (Phase H). */
object StemSeparation {
    const val DEFAULT_CACHE_BYTES = 1_000_000_000L
    /** Below this the 12 s segment (~70 MB of spectra and tensors) plus the 79 MB model is unsafe. */
    private const val MIN_TOTAL_RAM_BYTES = 3L * 1024 * 1024 * 1024

    fun modelStore(context: Context) = StemModelStore(java.io.File(context.filesDir, "stem-models"))

    fun isDeviceEligible(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false // ONNX Runtime Android requires API 24
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return !activityManager.isLowRamDevice && info.totalMem >= MIN_TOTAL_RAM_BYTES
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun create(
        context: Context,
        scope: CoroutineScope,
        cacheBytes: Long = DEFAULT_CACHE_BYTES,
    ): StemSeparationManager {
        val app = context.applicationContext
        val store = modelStore(app)
        val cache = StemCache(java.io.File(app.filesDir, "stems"), cacheBytes)
        return StemSeparationManager(
            scope = scope,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            cache = cache,
            modelId = store.spec.id,
            modelVersion = store.spec.version,
            isModelInstalled = store::isInstalled,
            openModel = {
                OnnxSpleeterModel(store.file(StemModelSpec.VOCALS), store.file(StemModelSpec.ACCOMPANIMENT))
            },
            openSource = { uri -> MediaCodecStereoSource(app, Uri.parse(uri)) },
            contentSha256 = { uri ->
                checkNotNull(app.contentResolver.openInputStream(Uri.parse(uri))) { "Cannot open $uri" }
                    .use { sha256Hex(it) }
            },
            eligibility = { isDeviceEligible(app) },
        )
    }
}
