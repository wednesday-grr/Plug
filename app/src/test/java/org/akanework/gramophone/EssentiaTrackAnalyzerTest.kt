/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.utils.automix.EssentiaTrackAnalyzer
import org.akanework.gramophone.logic.utils.automix.TrackPcmDecoder
import org.junit.Test

class EssentiaTrackAnalyzerTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortWindowsBeforeNativeCall() {
        runBlocking {
            EssentiaTrackAnalyzer.analyze(TrackPcmDecoder.Window(FloatArray(44_100), 0, 1000))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSilenceBeforeNativeCall() {
        runBlocking {
            EssentiaTrackAnalyzer.analyze(TrackPcmDecoder.Window(FloatArray(44_100 * 5), 0, 5000))
        }
    }
}
