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
import org.akanework.gramophone.logic.utils.automix.TrackEdges
import org.akanework.gramophone.logic.utils.automix.BeatPhaseAligner
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
    fun migratesVersionOneAndTwoWithoutLosingTrackData() {
        val context = RuntimeEnvironment.getApplication()
        for (oldVersion in 1..2) {
            val key = AnalysisKey("migration-v$oldVersion", 1, "content://media/migration-$oldVersion")
            TrackAnalysisCache(context).also {
                val db = it.writableDatabase
                db.execSQL("DROP TABLE analysis")
                val vocalColumn = if (oldVersion == 2) ", vocal_energy REAL" else ""
                db.execSQL("""CREATE TABLE analysis (
                    media_id TEXT NOT NULL, modified INTEGER NOT NULL, uri TEXT NOT NULL,
                    bpm REAL, camelot TEXT, beat_offset REAL, confidence REAL NOT NULL,
                    window_start INTEGER NOT NULL, window_end INTEGER NOT NULL $vocalColumn,
                    PRIMARY KEY(media_id, modified, uri))""")
                db.execSQL("""INSERT INTO analysis (media_id, modified, uri, bpm, camelot,
                    beat_offset, confidence, window_start, window_end)
                    VALUES (?, ?, ?, 121, '8A', 10, 0.8, 0, 50000)""",
                    arrayOf<Any>(key.mediaId, key.modified, key.uri))
                if (oldVersion == 2) db.execSQL("UPDATE analysis SET vocal_energy=0.4")
                db.version = oldVersion
                it.close()
            }
            TrackAnalysisCache(context).also {
                assertEquals(TrackAnalysis(121f, "8A", 10.0, 0.8f, 0, 50_000,
                    if (oldVersion == 2) 0.4f else null), it.load(key))
                assertNull(it.load(key.copy(analyzer = "essentia")))
                it.close()
            }
        }
    }

    @Test
    fun keepsAnalyzerResultsSeparateAcrossReloads() {
        val context = RuntimeEnvironment.getApplication()
        val existing = AnalysisKey("engine-cache-test", 1, "content://media/engine")
        val essentia = existing.copy(analyzer = "essentia")
        val first = TrackAnalysis(120f, "8A", 500.0, 0.9f, 100, 50_000)
        val second = TrackAnalysis(123f, null, null, 0f, 100, 50_000)
        TrackAnalysisCache(context).also {
            it.store(existing, first)
            it.store(essentia, second)
            assertEquals(first, it.peek(existing))
            assertEquals(second, it.peek(essentia))
            it.close()
        }
        TrackAnalysisCache(context).also {
            assertEquals(first, it.load(existing))
            assertEquals(second, it.load(essentia))
            it.close()
        }
    }

    @Test
    fun migratesVersionThreeResultsAsExisting() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("migration-cache-test", 1, "content://media/migration")
        val value = TrackAnalysis(121f, "8A", 10.0, 0.8f, 0, 50_000, 0.4f,
            TrackEdges(200_000, 1000, 2000, 3000, 4000,
                BeatPhaseAligner.Grid(500.0, 11.0, 0.7f),
                BeatPhaseAligner.Grid(510.0, 12.0, 0.8f), 30_000, 170_000))
        TrackAnalysisCache(context).also {
            it.store(key, value)
            val db = it.writableDatabase
            db.execSQL("ALTER TABLE analysis RENAME TO analysis_v4")
            db.execSQL("""CREATE TABLE analysis (
                media_id TEXT NOT NULL, modified INTEGER NOT NULL, uri TEXT NOT NULL,
                bpm REAL, camelot TEXT, confidence REAL NOT NULL, beat_offset REAL,
                window_start INTEGER NOT NULL, window_end INTEGER NOT NULL, vocal_energy REAL,
                edge_duration INTEGER, leading_silence INTEGER, trailing_silence INTEGER,
                intro_point INTEGER, outro_skip INTEGER, start_period REAL, start_phase REAL,
                start_confidence REAL, end_period REAL, end_phase REAL, end_confidence REAL,
                start_window_end INTEGER, end_window_start INTEGER,
                PRIMARY KEY(media_id, modified, uri))""")
            db.execSQL("""INSERT INTO analysis SELECT media_id, modified, uri, bpm, camelot,
                confidence, beat_offset, window_start, window_end, vocal_energy, edge_duration,
                leading_silence, trailing_silence, intro_point, outro_skip, start_period,
                start_phase, start_confidence, end_period, end_phase, end_confidence,
                start_window_end, end_window_start FROM analysis_v4""")
            db.execSQL("DROP TABLE analysis_v4")
            db.version = 3
            it.close()
        }
        TrackAnalysisCache(context).also {
            assertEquals(value, it.load(key))
            assertNull(it.load(key.copy(analyzer = "essentia")))
            it.close()
        }
    }

    @Test
    fun persistsAndInvalidatesByModificationAndUri() {
        val context = RuntimeEnvironment.getApplication()
        val id = AnalysisKey("cache-test", 1, "content://media/track/1")
        val value = TrackAnalysis(120f, "8A", 500.0, 0.9f, 100, 50_000, 0.72f,
            TrackEdges(200_000, 2000, 1000, 4000, 5000,
                BeatPhaseAligner.Grid(500.0, 250.0, 0.9f),
                BeatPhaseAligner.Grid(500.0, 400.0, 0.8f), 30_000, 170_000))
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
