/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DownloadScannerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun scansAudioRecursivelyInBatchesAndSkipsNomediaAndArchives() = runBlocking {
        val music = folder.newFolder("Music")
        val downloads = folder.newFolder("Download")
        val album = File(downloads, "album").apply { mkdir() }
        val ignored = File(music, "ignored").apply { mkdir() }
        File(ignored, ".nomedia").createNewFile()
        File(ignored, "hidden.flac").createNewFile()
        File(downloads, "archive.zip").createNewFile()
        repeat(130) { File(album, "track$it.FLAC").createNewFile() }
        File(music, "song.m4a").createNewFile()
        val batches = mutableListOf<List<String>>()

        val complete = DownloadScanner.scanRoots(RuntimeEnvironment.getApplication(), listOf(music, downloads)) { _, paths ->
            batches.add(paths.toList())
            true
        }

        assertTrue(complete)
        assertEquals(listOf(128, 3), batches.map { it.size })
        assertEquals(131, batches.flatten().toSet().size)
        assertTrue(batches.flatten().contains(File(music, "song.m4a").absolutePath))
        assertFalse(batches.flatten().any { it.endsWith(".zip") || it.contains("hidden.flac") })
    }

    @Test
    fun reportsFailedScanAndStillProcessesLaterBatches() = runBlocking {
        val music = folder.newFolder("Music")
        repeat(129) { File(music, "track$it.wav").createNewFile() }
        var calls = 0
        val complete = DownloadScanner.scanRoots(RuntimeEnvironment.getApplication(), listOf(music)) { _, _ ->
            ++calls != 1
        }
        assertFalse(complete)
        assertEquals(2, calls)
    }
}
