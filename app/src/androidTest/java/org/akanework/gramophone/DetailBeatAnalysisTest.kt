/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import android.content.ContentUris
import android.content.ContentValues
import android.content.ComponentName
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.PlugPlaybackService
import org.akanework.gramophone.logic.utils.MediaItemList
import org.akanework.gramophone.logic.utils.automix.AudioAnalysisScheduler
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.fragments.DetailDialogFragment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class DetailBeatAnalysisTest {
    @Test
    fun detailButtonCancelsAndThenDisplaysPolledResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val name = "gramophone-beat-ui-${UUID.randomUUID()}.wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.TITLE, name)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/GramophoneBeatUiTest")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                val rate = 11_025
                val samples = rate * 60
                val pcm = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                pcm.put("RIFF".toByteArray()).putInt(36 + samples * 2)
                    .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(samples * 2)
                repeat(samples) { i ->
                    val amplitude = if (i % (rate / 2) < rate / 30)
                        sin(2 * PI * 1000 * i / rate) * 24_000 else 0.0
                    pcm.putShort(amplitude.toInt().toShort())
                }
                output.write(pcm.array())
            }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }, null, null)
            }
            val id = ContentUris.parseId(uri)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity { activity = it }
                val item = runBlocking {
                    withTimeout(120_000) { activity.reader.idMapFlow.first { id in it }.getValue(id) }
                }
                scenario.onActivity {
                    it.startFragment(DetailDialogFragment()) { putString("Id", "MediaStore:$id") }
                }
                waitForUi(scenario) { button, _ -> button.isEnabled }
                val scheduler = serviceScheduler()
                val lock = checkNotNull(schedulerField(scheduler, "lock"))
                val originalQueue = synchronized(lock) {
                    (schedulerField(scheduler, "queue") as List<*>).map { it as MediaItem }
                }
                try {
                    scheduler.updateQueue(emptyList())
                    scenario.onActivity { it.findViewById<MaterialButton>(R.id.analyze_beats).performClick() }
                    waitForUi(scenario) { button, _ ->
                        button.text.toString() == context.getString(R.string.beat_analysis_cancel)
                    }
                    scenario.onActivity { it.findViewById<MaterialButton>(R.id.analyze_beats).performClick() }
                    waitForUi(scenario, diagnostic = { serviceStatus(item) }) { button, status ->
                        button.isEnabled && button.text.toString() == context.getString(R.string.beat_analysis_start) &&
                            status.text.isEmpty()
                    }
                    scenario.onActivity { it.findViewById<MaterialButton>(R.id.analyze_beats).performClick() }
                    waitForUi(scenario, diagnostic = { serviceStatus(item) }) { button, _ ->
                        button.text.toString() == context.getString(R.string.beat_analysis_cancel)
                    }
                    waitForUi(scenario, diagnostic = { serviceStatus(item) }) { button, status ->
                        !button.isEnabled && (status.text.toString().contains("beats detected") ||
                            status.text.toString() == context.getString(R.string.beat_analysis_empty) ||
                            status.text.toString() == context.getString(R.string.beat_analysis_unavailable))
                    }
                } finally {
                    synchronized(lock) {
                        if ((schedulerField(scheduler, "queue") as List<*>).isEmpty()) {
                            scheduler.updateQueue(originalQueue)
                        }
                    }
                }
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun waitForUi(
        scenario: ActivityScenario<MainActivity>,
        diagnostic: () -> String = { "" },
        condition: (MaterialButton, TextView) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        var matches = false
        var lastState = "view not found"
        while (!matches && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                val button = activity.findViewById<MaterialButton>(R.id.analyze_beats)
                val status = activity.findViewById<TextView>(R.id.beat_analysis_status)
                matches = button != null && status != null && condition(button, status)
                if (button != null && status != null) {
                    lastState = "button=${button.text}, enabled=${button.isEnabled}, status=${status.text}"
                }
            }
            if (!matches) SystemClock.sleep(100)
        }
        if (!matches) {
            assertTrue("Beat analysis UI did not reach the expected state; $lastState; ${diagnostic()}", false)
        }
    }

    private fun serviceStatus(item: MediaItem): String = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(context, ComponentName(context, PlugPlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().get(30, TimeUnit.SECONDS)
        try {
            val future = withContext(Dispatchers.Main.immediate) {
                controller.sendCustomCommand(SessionCommand(PlugPlaybackService.SERVICE_BEAT_ANALYSIS, Bundle.EMPTY),
                    Bundle().apply {
                        putString("operation", "status")
                        putBinder("item", MediaItemList(listOf(item)))
                    })
            }
            val result = future.await()
            val playback = withContext(Dispatchers.Main.immediate) {
                "queueSize=${controller.mediaItemCount} playing=${controller.isPlaying}"
            }
            val scheduler = serviceScheduler()
            val fields = listOf("currentIsBeat", "currentIsLibrary", "currentJob", "pendingBeatRequests")
            val schedulerState = fields.joinToString { name ->
                val value = schedulerField(scheduler, name)
                "$name=${when (value) {
                    is kotlinx.coroutines.Job -> "active:${value.isActive},completed:${value.isCompleted}"
                    is Map<*, *> -> value.size
                    else -> value
                }}"
            }
            "service=${result.extras.getString("beat_analysis_status")} code=${result.resultCode} $playback $schedulerState"
        } finally {
            withContext(Dispatchers.Main.immediate) { controller.release() }
        }
    }

    private fun serviceScheduler(): AudioAnalysisScheduler {
        val service = checkNotNull(PlugPlaybackService.instanceForWidgetAndLyricsOnly)
        return service.javaClass.getDeclaredField("audioAnalysis")
            .apply { isAccessible = true }.get(service) as AudioAnalysisScheduler
    }

    private fun schedulerField(scheduler: AudioAnalysisScheduler, name: String): Any? =
        scheduler.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(scheduler)
}
