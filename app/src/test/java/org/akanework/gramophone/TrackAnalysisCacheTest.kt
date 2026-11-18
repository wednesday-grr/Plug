/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.akanework.gramophone

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.akanework.gramophone.logic.utils.automix.AnalysisKey
import org.akanework.gramophone.logic.utils.automix.TrackAnalysis
import org.akanework.gramophone.logic.utils.automix.TrackAnalysisCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uk.akane.libphonograph.items.EXTRA_MODIFIED_DATE

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TrackAnalysisCacheTest {
    @Test
    fun persistsAndInvalidatesByModificationAndUri() {
        val context = RuntimeEnvironment.getApplication()
        val id = AnalysisKey("cache-test", 1, "content://media/track/1")
        val value = TrackAnalysis(120f, "8A", 500.0, 0.9f, 100, 50_000)
        val initial = TrackAnalysisCache(context)
        try {
            initial.store(id, value)
        } finally {
            initial.close()
        }
        val cache = TrackAnalysisCache(context)
        try {
            assertNull(cache.peek(id))
            assertEquals(value, cache.load(id))
            assertEquals(value, cache.peek(id))
            assertNull(cache.load(id.copy(modified = 2)))
            assertNull(cache.load(id.copy(uri = "content://media/track/2")))
            cache.store(id.copy(modified = 2), value.copy(bpm = 130f))
            assertNull(cache.load(id))
        } finally {
            cache.close()
        }
    }

    @Test
    fun requiresStableIdentityVersionAndLocalUri() {
        val metadata = MediaMetadata.Builder().setExtras(Bundle().apply {
            putLong(EXTRA_MODIFIED_DATE, 123)
        }).build()
        val local = MediaItem.Builder().setMediaId("one")
            .setUri("content://media/track/1").setMediaMetadata(metadata).build()
        assertEquals(123L, AnalysisKey.from(local)?.modified)
        assertNull(AnalysisKey.from(local.buildUpon().setMediaMetadata(MediaMetadata.EMPTY).build()))
        assertNull(AnalysisKey.from(local.buildUpon().setUri("https://example.com/song").build()))
    }
}
