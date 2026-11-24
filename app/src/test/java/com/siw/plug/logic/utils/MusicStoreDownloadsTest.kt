/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicStoreDownloadsTest {
    @Test
    fun acceptsRequestedFormatsAndRecognizedMimeTypes() {
        listOf("FLAC", "m4a", "mp3", "WAV", "zip").forEach {
            assertTrue(MusicStoreDownloads.isSupported("track.$it", "application/octet-stream"))
        }
        assertTrue(MusicStoreDownloads.isSupported("download", "audio/flac"))
        assertTrue(MusicStoreDownloads.isSupported("download", "application/x-zip-compressed"))
        assertFalse(MusicStoreDownloads.isSupported("document.pdf", "application/pdf"))
        assertTrue(MusicStoreDownloads.isArchive("album.ZIP", "application/octet-stream"))
        assertFalse(MusicStoreDownloads.isArchive("track.mp3", "audio/mpeg"))
    }

    @Test
    fun destinationCannotContainPathSeparatorsAndKeepsExtension() {
        assertEquals(
            ".._album_song-12345678.flac",
            MusicStoreDownloads.destinationName("../album\\song.FLAC", "12345678")
        )
        assertEquals("download-12345678", MusicStoreDownloads.destinationName("download", "12345678"))
    }
}
