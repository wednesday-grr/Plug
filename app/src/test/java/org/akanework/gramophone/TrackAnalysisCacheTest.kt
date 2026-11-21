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
import org.akanework.gramophone.logic.utils.automix.AnalysisWindow
import org.akanework.gramophone.logic.utils.automix.BeatGridCacheRead
import org.akanework.gramophone.logic.utils.automix.BeatGridCacheRecord
import org.akanework.gramophone.logic.utils.automix.BeatTempoHypothesis
import org.akanework.gramophone.logic.utils.automix.BeatTempoInterpretation
import org.akanework.gramophone.logic.utils.automix.BeatTrackingResult
import org.akanework.gramophone.logic.utils.automix.beatRegions
import org.akanework.gramophone.logic.utils.automix.DetectedBeatEvent
import org.akanework.gramophone.logic.utils.automix.DetectedBeatGridEvidence
import org.akanework.gramophone.logic.utils.automix.TrackAnalysis
import org.akanework.gramophone.logic.utils.automix.TrackAnalysisCache
import org.akanework.gramophone.logic.utils.automix.TrackEdges
import org.akanework.gramophone.logic.utils.automix.BeatPhaseAligner
import org.akanework.gramophone.logic.utils.automix.BeatEvidenceAdapter
import org.akanework.gramophone.logic.utils.automix.EvidenceSource
import org.akanework.gramophone.logic.utils.automix.MusicalBeatTracker
import org.akanework.gramophone.logic.utils.automix.SourceTimeline
import org.akanework.gramophone.logic.utils.automix.TimelineMapping
import org.akanework.gramophone.logic.utils.automix.TimelineOrigin
import org.akanework.gramophone.logic.utils.automix.TrackDspAnalyzer
import org.akanework.gramophone.logic.utils.automix.TimingFact
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
    fun freshCachePersistsEvidenceProvenanceAndVersion() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("evidence-cache", 1, "content://media/evidence")
        val newerKey = AnalysisKey("future-evidence-cache", 1, "content://media/future-evidence")
        val analysis = TrackAnalysis(120f, "8A", 125.0, 0.9f, 0, 5000,
            pulseProvenance = BeatEvidenceAdapter.spectralFluxProvenance)
        TrackAnalysisCache(context).also {
            assertEquals(8, it.writableDatabase.version)
            it.store(key, analysis)
            it.store(newerKey, analysis.copy(pulseProvenance =
                BeatEvidenceAdapter.spectralFluxProvenance.copy(algorithmVersion = "future-v2")))
            it.close()
        }
        TrackAnalysisCache(context).also {
            val loaded = it.load(key)!!
            assertEquals(analysis, loaded)
            val pulse = BeatEvidenceAdapter.fromAnalysis(loaded, key.uri)!!
            assertEquals(EvidenceSource.SPECTRAL_FLUX, pulse.provenance.source)
            assertEquals(true, BeatEvidenceAdapter.isAlgorithmVersion(pulse.provenance, BeatEvidenceAdapter.SPECTRAL_FLUX_VERSION))
            assertEquals(false, BeatEvidenceAdapter.isAlgorithmVersion(pulse.provenance, "future-version"))
            assertEquals(120f, loaded.bpm!!, 0f)
            assertEquals("8A", loaded.camelotKey)
            val newer = BeatEvidenceAdapter.fromAnalysis(it.load(newerKey)!!, newerKey.uri)!!
            assertEquals("future-v2", newer.provenance.algorithmVersion)
            assertEquals(false, newer.algorithmRecognized)
            assertEquals(org.akanework.gramophone.logic.utils.automix.ConfidenceMeaning.UNKNOWN_ALGORITHM,
                newer.confidence?.meaning)
            it.close()
        }
    }

    @Test
    fun migratesVersionFourAndPreservesPartialRecords() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("v4-evidence", 1, "content://media/v4-evidence")
        TrackAnalysisCache(context).also {
            val db = it.writableDatabase
            db.execSQL("DROP TABLE analysis")
            createVersionFourTable(db)
            db.execSQL("""INSERT INTO analysis (media_id, modified, uri, analyzer, bpm, camelot,
                beat_offset, confidence, window_start, window_end)
                VALUES (?, ?, ?, 'existing', 121, '8A', 125, 0.9, 0, 5000)""",
                arrayOf<Any>(key.mediaId, key.modified, key.uri))
            db.execSQL("""INSERT INTO analysis (media_id, modified, uri, analyzer, bpm, camelot,
                confidence, window_start, window_end)
                VALUES ('partial', 1, 'content://media/partial', 'existing', 110, '9A', 0, 0, 1000)""")
            db.version = 4
            it.close()
        }
        TrackAnalysisCache(context).also {
            assertEquals(8, it.writableDatabase.version)
            val loaded = it.load(key)!!
            assertEquals(121f, loaded.bpm!!, 0f)
            assertEquals("8A", loaded.camelotKey)
            assertEquals(125.0, loaded.beatOffsetMs!!, 0.0)
            assertEquals(EvidenceSource.LEGACY_CACHE, BeatEvidenceAdapter.fromAnalysis(loaded, key.uri)!!.provenance.source)
            val partial = it.load(AnalysisKey("partial", 1, "content://media/partial"))!!
            assertEquals(110f, partial.bpm!!, 0f)
            assertEquals("9A", partial.camelotKey)
            assertNull(BeatEvidenceAdapter.fromAnalysis(partial, null))
            it.close()
        }
    }

    @Test
    fun failedVersionFourMigrationRollsBackSchemaAndData() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("rollback", 1, "content://media/rollback")
        TrackAnalysisCache(context).also {
            val db = it.writableDatabase
            db.execSQL("DROP TABLE analysis")
            createVersionFourTable(db)
            db.execSQL("ALTER TABLE analysis ADD COLUMN evidence_timeline_origin TEXT")
            db.execSQL("""INSERT INTO analysis (media_id, modified, uri, analyzer, bpm, camelot,
                beat_offset, confidence, window_start, window_end)
                VALUES (?, ?, ?, 'existing', 121, '8A', 125, 0.9, 0, 5000)""",
                arrayOf<Any>(key.mediaId, key.modified, key.uri))
            db.version = 4
            it.close()
        }
        val failing = TrackAnalysisCache(context)
        try {
            org.junit.Assert.assertThrows(android.database.SQLException::class.java) { failing.writableDatabase }
        } finally {
            failing.close()
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("track_analysis.db").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(4, db.version)
            db.rawQuery("PRAGMA table_info(analysis)", null).use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(1))
                assertEquals(false, columns.contains("evidence_source"))
                assertEquals(false, columns.contains("evidence_algorithm_version"))
            }
            db.rawQuery("SELECT bpm, camelot, beat_offset FROM analysis WHERE media_id='rollback'", null)
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(121f, cursor.getFloat(0), 0f)
                    assertEquals("8A", cursor.getString(1))
                    assertEquals(125.0, cursor.getDouble(2), 0.0)
                }
        }
    }

    @Test
    fun incompleteEvidenceMetadataRemainsLegacy() {
        val key = AnalysisKey("partial-evidence", 1, "content://media/partial-evidence")
        TrackAnalysisCache(RuntimeEnvironment.getApplication()).also {
            it.store(key, TrackAnalysis(120f, "8A", 50.0, 0.9f, 0, 1000))
            it.writableDatabase.execSQL(
                "UPDATE analysis SET evidence_source='SPECTRAL_FLUX' WHERE media_id=?",
                arrayOf<Any>(key.mediaId))
            it.close()
        }
        TrackAnalysisCache(RuntimeEnvironment.getApplication()).also {
            val loaded = it.load(key)!!
            assertNull(loaded.pulseProvenance)
            assertEquals(EvidenceSource.LEGACY_CACHE, BeatEvidenceAdapter.fromAnalysis(loaded, key.uri)!!.provenance.source)
            it.close()
        }
    }

    private fun createVersionFourTable(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("""CREATE TABLE analysis (
            media_id TEXT NOT NULL, modified INTEGER NOT NULL, uri TEXT NOT NULL,
            bpm REAL, camelot TEXT, beat_offset REAL, confidence REAL NOT NULL,
            window_start INTEGER NOT NULL, window_end INTEGER NOT NULL, vocal_energy REAL,
            edge_duration INTEGER, leading_silence INTEGER, trailing_silence INTEGER,
            intro_point INTEGER, outro_skip INTEGER, start_period REAL, start_phase REAL,
            start_confidence REAL, end_period REAL, end_phase REAL, end_confidence REAL,
            start_window_end INTEGER, end_window_start INTEGER, analyzer TEXT NOT NULL,
            PRIMARY KEY(media_id, modified, uri, analyzer))""")
    }

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
        val second = TrackAnalysis(123f, "8B", null, 0f, 100, 50_000)
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
    fun reanalyzesEssentiaCacheEntriesWithoutAKey() {
        val essentia = AnalysisKey(
            "essentia-key-cache-test", 1, "content://media/essentia-key", "essentia",
        )
        val oldResult = TrackAnalysis(123f, null, null, 0f, 100, 50_000)
        val updatedResult = oldResult.copy(camelotKey = "8B")
        val cache = TrackAnalysisCache(RuntimeEnvironment.getApplication())
        try {
            cache.store(essentia, oldResult)
            assertNull(cache.peek(essentia))
            assertNull(cache.load(essentia))
            cache.store(essentia, updatedResult)
            assertEquals(updatedResult, cache.load(essentia))
        } finally {
            cache.close()
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

    @Test
    fun persistsBeatGridAndRejectsVersionSourceAndWindowMismatches() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("beat-grid-cache", 1, "content://media/beat-grid")
        val record = beatRecord(key)
        TrackAnalysisCache(context).also {
            it.store(key, TrackAnalysis(120f, "8A", 125.0, 0.9f, 0, 5000,
                pulseProvenance = BeatEvidenceAdapter.spectralFluxProvenance))
            it.storeBeatGrid(key, record)
            it.close()
        }
        TrackAnalysisCache(context).also {
            assertEquals(8, it.writableDatabase.version)
            assertEquals(BeatGridCacheRead.Available(record), it.loadBeatGrid(key))
            assertEquals(BeatGridCacheRead.Invalidated, it.loadBeatGrid(
                key, configurationVersion = "future-config"))
            assertEquals(BeatGridCacheRead.Invalidated, it.loadBeatGrid(
                key, expectedWindow = AnalysisWindow(0, 9_000_000)))
            assertEquals(BeatGridCacheRead.Missing, it.loadBeatGrid(key.copy(uri = "content://media/other")))
            assertEquals(120f, it.load(key)!!.bpm!!, 0f)
            assertEquals("8A", it.load(key)!!.camelotKey)
            assertEquals(125.0, it.load(key)!!.beatOffsetMs!!, 0.0)
            it.close()
        }
    }

    @Test
    fun cachedEmptyAndUnavailableBeatResultsRemainDistinct() {
        val cache = TrackAnalysisCache(RuntimeEnvironment.getApplication())
        val key = AnalysisKey("beat-grid-empty", 1, "content://media/beat-grid-empty")
        val complete = beatRecord(key)
        val grid = checkNotNull(complete.result.grid)
        val empty = complete.copy(result = complete.result.copy(grid = grid.copy(events = emptyList())))
        val unavailable = complete.copy(result = complete.result.copy(grid = null))
        try {
            cache.storeBeatGrid(key, empty)
            assertEquals(org.akanework.gramophone.logic.utils.automix.BeatGridCacheState.EMPTY,
                (cache.loadBeatGrid(key) as BeatGridCacheRead.Available).record.state)
            cache.storeBeatGrid(key, unavailable)
            assertEquals(org.akanework.gramophone.logic.utils.automix.BeatGridCacheState.UNAVAILABLE,
                (cache.loadBeatGrid(key) as BeatGridCacheRead.Available).record.state)
        } finally {
            cache.close()
        }
    }

    @Test
    fun persistsObservedAndUnavailableRegionsAcrossCacheReopen() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("beat-regions", 1, "content://media/beat-regions")
        val unavailableKey = key.copy(mediaId = "beat-regions-unavailable",
            uri = "content://media/beat-regions-unavailable")
        val initial = beatRecord(key)
        val withRegions = initial.copy(result = initial.result.copy(
            regions = beatRegions(initial.window, checkNotNull(initial.result.grid).events)))
        val unavailableBase = beatRecord(unavailableKey)
        val unavailable = unavailableBase.copy(result = unavailableBase.result.copy(
            grid = null, regions = beatRegions(unavailableBase.window, emptyList())))
        TrackAnalysisCache(context).also {
            it.storeBeatGrid(key, withRegions)
            it.storeBeatGrid(unavailableKey, unavailable)
            it.close()
        }
        TrackAnalysisCache(context).also {
            assertEquals(8, it.writableDatabase.version)
            assertEquals(BeatGridCacheRead.Available(withRegions), it.loadBeatGrid(key))
            assertEquals(BeatGridCacheRead.Available(unavailable), it.loadBeatGrid(unavailableKey))
            it.close()
        }
    }

    @Test
    fun rejectsRegionsInconsistentWithObservedEvents() {
        val key = AnalysisKey("beat-regions-invalid", 1, "content://media/beat-regions-invalid")
        val record = beatRecord(key)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            record.copy(result = record.result.copy(regions = beatRegions(record.window, emptyList())))
        }
    }

    @Test
    fun invalidatesBeatGridsOnAlgorithmAndStoredTimelineMismatch() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("beat-grid-invalidated", 1, "content://media/beat-grid-invalidated")
        val first = TrackAnalysisCache(context)
        first.storeBeatGrid(key, beatRecord(key))
        first.writableDatabase.execSQL(
            "UPDATE beat_grid SET algorithm_version='future-algorithm' WHERE media_id=?",
            arrayOf<Any>(key.mediaId),
        )
        first.close()
        val algorithmMismatch = TrackAnalysisCache(context)
        assertEquals(BeatGridCacheRead.Invalidated, algorithmMismatch.loadBeatGrid(key))
        algorithmMismatch.close()

        val second = TrackAnalysisCache(context)
        second.storeBeatGrid(key, beatRecord(key))
        second.writableDatabase.execSQL(
            "UPDATE beat_grid SET timeline_identity='content://media/other' WHERE media_id=?",
            arrayOf<Any>(key.mediaId),
        )
        second.close()
        val identityMismatch = TrackAnalysisCache(context)
        assertEquals(BeatGridCacheRead.Invalidated, identityMismatch.loadBeatGrid(key))
        identityMismatch.close()
    }

    @Test
    fun rejectsInvalidBeatGridWritesBeforePersisting() {
        val cache = TrackAnalysisCache(RuntimeEnvironment.getApplication())
        val key = AnalysisKey("invalid-beat-grid", 1, "content://media/invalid-beat-grid")
        val record = beatRecord(key).copy(
            result = beatRecord(key).result.copy(grid = null),
            algorithmVersion = "unknown-algorithm",
        )
        try {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                cache.storeBeatGrid(key, record)
            }
            assertEquals(BeatGridCacheRead.Missing, cache.loadBeatGrid(key))
        } finally {
            cache.close()
        }
    }

    @Test
    fun migratesVersionFiveWithoutChangingExistingAnalysisRows() {
        val context = RuntimeEnvironment.getApplication()
        val key = AnalysisKey("beat-grid-v5", 1, "content://media/beat-grid-v5")
        TrackAnalysisCache(context).also { cache ->
            cache.store(key, TrackAnalysis(121f, "8A", 125.0, 0.9f, 0, 5000))
            cache.writableDatabase.execSQL("DROP TABLE beat_grid")
            cache.writableDatabase.version = 5
            cache.close()
        }
        TrackAnalysisCache(context).also { cache ->
            assertEquals(8, cache.writableDatabase.version)
            assertEquals(121f, cache.load(key)!!.bpm!!, 0f)
            assertEquals("8A", cache.load(key)!!.camelotKey)
            assertEquals(125.0, cache.load(key)!!.beatOffsetMs!!, 0.0)
            assertEquals(BeatGridCacheRead.Missing, cache.loadBeatGrid(key))
            cache.close()
        }
    }

    private fun beatRecord(key: AnalysisKey): BeatGridCacheRecord {
        val timeline = SourceTimeline(key.uri, TimelineOrigin.DECODER_PRESENTATION,
            TimelineMapping.UNKNOWN, TimingFact.YES, TimingFact.YES, TimingFact.NO, TimingFact.NO)
        val window = AnalysisWindow(1_000_000, 9_000_000)
        val grid = DetectedBeatGridEvidence(
            listOf(
                DetectedBeatEvent(0, 1_100_000, 0.7f, onsetStrength = 0.8f),
                DetectedBeatEvent(1, 1_600_000, 0.8f, onsetStrength = 0.9f),
            ),
            "spectral-flux-onsets", MusicalBeatTracker.ALGORITHM_VERSION, window, timeline, 0.75f, null,
        )
        return BeatGridCacheRecord(
            key.uri,
            TrackDspAnalyzer.BEAT_ANALYSIS_CONFIG_VERSION,
            BeatTrackingResult(
                grid,
                listOf(BeatTempoHypothesis(BeatTempoInterpretation.PRIMARY, 120.0, 0.75f)),
                BeatTempoInterpretation.PRIMARY,
                false,
                "Observed onset sequence",
            ),
            window,
            timeline,
        )
    }
}
