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
package org.akanework.gramophone.logic.utils.automix

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.sqlite.transaction
import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import uk.akane.libphonograph.items.modifiedDate
import java.util.concurrent.ConcurrentHashMap

data class AnalysisKey(
    val mediaId: String,
    val modified: Long,
    val uri: String,
    val analyzer: String = "existing",
) {
    init {
        require(analyzer in setOf("existing", "essentia")) { "Unknown analyzer: $analyzer" }
    }

    companion object {
        fun from(item: MediaItem): AnalysisKey? {
            val uri = item.localConfiguration?.uri ?: return null
            val modified = item.mediaMetadata.modifiedDate ?: return null
            if (item.mediaId.isBlank() || uri.scheme !in listOf("content", "file")) return null
            return AnalysisKey(item.mediaId, modified, uri.toString())
        }
    }
}

class TrackAnalysisCache(context: Context) : SQLiteOpenHelper(context, "track_analysis.db", null, 6) {
    private val memory = ConcurrentHashMap<AnalysisKey, TrackAnalysis>()
    private val beatGridMemory = ConcurrentHashMap<AnalysisKey, BeatGridCacheRecord>()
    @Volatile
    var selectedAnalyzer: String = "existing"
        set(value) {
            require(value in setOf("existing", "essentia"))
            field = value
        }

    fun selectedKey(key: AnalysisKey): AnalysisKey = key.copy(analyzer = selectedAnalyzer)
    fun peekSelected(key: AnalysisKey): TrackAnalysis? = peek(selectedKey(key))
    fun loadSelected(key: AnalysisKey): TrackAnalysis? = load(selectedKey(key))

    override fun onCreate(db: SQLiteDatabase) {
        createVersionFour(db)
        addEvidenceColumns(db)
        createBeatGridTable(db)
    }

    private fun createVersionFour(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE analysis (
            media_id TEXT NOT NULL, modified INTEGER NOT NULL, uri TEXT NOT NULL,
            bpm REAL, camelot TEXT, beat_offset REAL, confidence REAL NOT NULL,
            window_start INTEGER NOT NULL, window_end INTEGER NOT NULL, vocal_energy REAL,
            edge_duration INTEGER, leading_silence INTEGER, trailing_silence INTEGER,
            intro_point INTEGER, outro_skip INTEGER, start_period REAL, start_phase REAL,
            start_confidence REAL, end_period REAL, end_phase REAL, end_confidence REAL,
            start_window_end INTEGER, end_window_start INTEGER,
            analyzer TEXT NOT NULL,
            PRIMARY KEY(media_id, modified, uri, analyzer))""")
    }

    private fun addEvidenceColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE analysis ADD COLUMN evidence_source TEXT")
        db.execSQL("ALTER TABLE analysis ADD COLUMN evidence_algorithm_version TEXT")
        db.execSQL("ALTER TABLE analysis ADD COLUMN evidence_timeline_origin TEXT")
        db.execSQL("ALTER TABLE analysis ADD COLUMN evidence_timeline_mapping TEXT")
    }

    private fun createBeatGridTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS beat_grid (
            media_id TEXT NOT NULL, modified INTEGER NOT NULL, uri TEXT NOT NULL, analyzer TEXT NOT NULL,
            algorithm_version TEXT NOT NULL, configuration_version TEXT NOT NULL, state TEXT NOT NULL,
            window_start_us INTEGER NOT NULL, window_end_us INTEGER NOT NULL,
            timeline_identity TEXT NOT NULL, timeline_origin TEXT NOT NULL, timeline_mapping TEXT NOT NULL,
            relative_to_original TEXT NOT NULL, leading_silence_included TEXT NOT NULL,
            encoder_priming_corrected TEXT NOT NULL, edge_trim_applied TEXT NOT NULL,
            edge_trim_offset_us INTEGER, result_source TEXT, result_reason TEXT NOT NULL,
            selected_interpretation TEXT, ambiguous INTEGER NOT NULL, musical_beat_confidence REAL,
            downbeat_confidence REAL, hypotheses_json TEXT NOT NULL, events_json TEXT,
            PRIMARY KEY(media_id, modified, uri, analyzer))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL("ALTER TABLE analysis ADD COLUMN vocal_energy REAL")
        }
        if (oldVersion < 3 && newVersion >= 3) {
            for (name in listOf("edge_duration", "leading_silence", "trailing_silence", "intro_point", "outro_skip")) {
                db.execSQL("ALTER TABLE analysis ADD COLUMN $name INTEGER")
            }
            for (name in listOf("start_period", "start_phase", "start_confidence", "end_period", "end_phase", "end_confidence")) {
                db.execSQL("ALTER TABLE analysis ADD COLUMN $name REAL")
            }
            db.execSQL("ALTER TABLE analysis ADD COLUMN start_window_end INTEGER")
            db.execSQL("ALTER TABLE analysis ADD COLUMN end_window_start INTEGER")
        }
        if (oldVersion < 4 && newVersion >= 4) {
            db.execSQL("ALTER TABLE analysis RENAME TO analysis_v3")
            createVersionFour(db)
            db.execSQL("""INSERT INTO analysis (
                media_id, modified, uri, bpm, camelot, beat_offset, confidence,
                window_start, window_end, vocal_energy, edge_duration, leading_silence,
                trailing_silence, intro_point, outro_skip, start_period, start_phase,
                start_confidence, end_period, end_phase, end_confidence, start_window_end,
                end_window_start, analyzer
            ) SELECT
                media_id, modified, uri, bpm, camelot, beat_offset, confidence,
                window_start, window_end, vocal_energy, edge_duration, leading_silence,
                trailing_silence, intro_point, outro_skip, start_period, start_phase,
                start_confidence, end_period, end_phase, end_confidence, start_window_end,
                end_window_start, 'existing' FROM analysis_v3""")
            db.execSQL("DROP TABLE analysis_v3")
        }
        if (oldVersion < 5 && newVersion >= 5) addEvidenceColumns(db)
        if (oldVersion < 6 && newVersion >= 6) createBeatGridTable(db)
    }

    fun peek(key: AnalysisKey): TrackAnalysis? = memory[key]?.takeIf { isCurrent(key, it) }

    fun peekBeatGrid(
        key: AnalysisKey,
        configurationVersion: String = TrackDspAnalyzer.BEAT_ANALYSIS_CONFIG_VERSION,
    ): BeatGridCacheRead = beatGridMemory[key]?.let {
        if (it.configurationVersion == configurationVersion &&
            it.algorithmVersion == MusicalBeatTracker.ALGORITHM_VERSION
        ) BeatGridCacheRead.Available(it) else BeatGridCacheRead.Invalidated
    } ?: BeatGridCacheRead.Missing

    private fun isCurrent(key: AnalysisKey, result: TrackAnalysis): Boolean =
        key.analyzer != "essentia" || result.camelotKey != null

    /** Call on a background dispatcher. */
    @Synchronized
    fun load(key: AnalysisKey): TrackAnalysis? {
        memory[key]?.takeIf { isCurrent(key, it) }?.let { return it }
        readableDatabase.query(
            "analysis", arrayOf(
                "bpm", "camelot", "beat_offset", "confidence", "window_start", "window_end",
                "vocal_energy", "edge_duration", "leading_silence", "trailing_silence",
                "intro_point", "outro_skip", "start_period", "start_phase", "start_confidence",
                "end_period", "end_phase", "end_confidence", "start_window_end", "end_window_start",
                "evidence_source", "evidence_algorithm_version", "evidence_timeline_origin",
                "evidence_timeline_mapping"
            ),
            "media_id=? AND modified=? AND uri=? AND analyzer=?",
            arrayOf(key.mediaId, key.modified.toString(), key.uri, key.analyzer),
            null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val result = TrackAnalysis(
                if (cursor.isNull(0)) null else cursor.getFloat(0),
                if (cursor.isNull(1)) null else cursor.getString(1),
                if (cursor.isNull(2)) null else cursor.getDouble(2),
                cursor.getFloat(3), cursor.getLong(4), cursor.getLong(5),
                if (cursor.isNull(6)) null else cursor.getFloat(6),
                if (cursor.isNull(7) || cursor.isNull(8) || cursor.isNull(9)) null else TrackEdges(
                    cursor.getLong(7), cursor.getLong(8), cursor.getLong(9),
                    if (cursor.isNull(10)) null else cursor.getLong(10),
                    if (cursor.isNull(11)) null else cursor.getLong(11),
                    if (cursor.isNull(12) || cursor.isNull(13) || cursor.isNull(14)) null
                    else BeatPhaseAligner.Grid(cursor.getDouble(12), cursor.getDouble(13), cursor.getFloat(14)),
                    if (cursor.isNull(15) || cursor.isNull(16) || cursor.isNull(17)) null
                    else BeatPhaseAligner.Grid(cursor.getDouble(15), cursor.getDouble(16), cursor.getFloat(17)),
                    if (cursor.isNull(18)) 0 else cursor.getLong(18),
                    if (cursor.isNull(19)) 0 else cursor.getLong(19),
                ),
                if (cursor.isNull(20) && cursor.isNull(21) && cursor.isNull(22) && cursor.isNull(23)) {
                    null
                } else {
                    val source = EvidenceSource.entries.firstOrNull { it.name == cursor.getString(20) }
                    val version = if (cursor.isNull(21)) null else cursor.getString(21)
                    val origin = TimelineOrigin.entries.firstOrNull { it.name == cursor.getString(22) }
                    val mapping = TimelineMapping.entries.firstOrNull { it.name == cursor.getString(23) }
                    if (source == null || origin == null || mapping == null ||
                        version != null && version.isBlank() ||
                        origin == TimelineOrigin.UNKNOWN && mapping != TimelineMapping.UNKNOWN
                    ) {
                        Log.w("TrackAnalysisCache", "Incomplete or invalid beat evidence metadata; treating as legacy")
                        null
                    } else PulseProvenance(source, version, origin, mapping)
                },
            )
            if (!isCurrent(key, result)) return null
            memory[key] = result
            return result
        }
    }

    /** Call on a background dispatcher. */
    @Synchronized
    fun loadBeatGrid(
        key: AnalysisKey,
        expectedWindow: AnalysisWindow? = null,
        configurationVersion: String = TrackDspAnalyzer.BEAT_ANALYSIS_CONFIG_VERSION,
    ): BeatGridCacheRead {
        beatGridMemory[key]?.let { record ->
            if (record.configurationVersion != configurationVersion ||
                record.algorithmVersion != MusicalBeatTracker.ALGORITHM_VERSION ||
                record.sourceIdentity != key.uri || expectedWindow != null && record.window != expectedWindow
            ) return BeatGridCacheRead.Invalidated
            return BeatGridCacheRead.Available(record)
        }
        readableDatabase.query(
            "beat_grid",
            arrayOf(
                "algorithm_version", "configuration_version", "state", "window_start_us",
                "window_end_us", "timeline_identity", "timeline_origin", "timeline_mapping",
                "relative_to_original", "leading_silence_included", "encoder_priming_corrected",
                "edge_trim_applied", "edge_trim_offset_us", "result_source", "result_reason",
                "selected_interpretation", "ambiguous", "musical_beat_confidence",
                "downbeat_confidence", "hypotheses_json", "events_json",
            ),
            "media_id=? AND modified=? AND uri=? AND analyzer=?",
            arrayOf(key.mediaId, key.modified.toString(), key.uri, key.analyzer),
            null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return BeatGridCacheRead.Missing
            if (cursor.getString(0) != MusicalBeatTracker.ALGORITHM_VERSION ||
                cursor.getString(1) != configurationVersion ||
                cursor.getString(5) != key.uri ||
                expectedWindow != null &&
                (cursor.getLong(3) != expectedWindow.startUs || cursor.getLong(4) != expectedWindow.endUs)
            ) return BeatGridCacheRead.Invalidated
            return try {
                val origin = TimelineOrigin.valueOf(cursor.getString(6))
                val mapping = TimelineMapping.valueOf(cursor.getString(7))
                val timeline = SourceTimeline(
                    cursor.getString(5), origin, mapping,
                    TimingFact.valueOf(cursor.getString(8)),
                    TimingFact.valueOf(cursor.getString(9)),
                    TimingFact.valueOf(cursor.getString(10)),
                    TimingFact.valueOf(cursor.getString(11)),
                    if (cursor.isNull(12)) null else cursor.getLong(12),
                )
                val window = AnalysisWindow(cursor.getLong(3), cursor.getLong(4))
                val hypotheses = JSONArray(cursor.getString(19)).let { array ->
                    List(array.length()) { index ->
                        val value = array.getJSONObject(index)
                        BeatTempoHypothesis(
                            BeatTempoInterpretation.valueOf(value.getString("interpretation")),
                            value.getDouble("bpm"),
                            value.getDouble("support").toFloat(),
                        )
                    }
                }
                val grid = if (cursor.isNull(20)) null else {
                    val array = JSONArray(cursor.getString(20))
                    val events = List(array.length()) { index ->
                        val value = array.getJSONObject(index)
                        DetectedBeatEvent(
                            value.getLong("index"),
                            value.getLong("sourceTimeUs"),
                            value.optionalFloat("musicalBeatConfidence"),
                            value.optionalBoolean("downbeat"),
                            value.optionalLong("barIndex"),
                            value.optionalFloat("downbeatConfidence"),
                            value.optionalFloat("onsetStrength"),
                        )
                    }
                    DetectedBeatGridEvidence(
                        events, checkNotNull(cursor.getString(13)), cursor.getString(0),
                        window, timeline, cursor.optionalFloat(17), cursor.optionalFloat(18),
                    )
                }
                val result = BeatTrackingResult(
                    grid,
                    hypotheses,
                    cursor.getString(15)?.let(BeatTempoInterpretation::valueOf),
                    cursor.getInt(16) != 0,
                    cursor.getString(14),
                )
                val record = BeatGridCacheRecord(
                    key.uri, configurationVersion, result, window, timeline, cursor.getString(0),
                )
                check(record.state.name == cursor.getString(2)) { "Cached beat-grid state does not match its data" }
                beatGridMemory[key] = record
                BeatGridCacheRead.Available(record)
            } catch (e: Exception) {
                Log.e("TrackAnalysisCache", "Invalid cached beat grid for ${key.mediaId}", e)
                BeatGridCacheRead.Invalidated
            }
        }
    }

    /** Call on a background dispatcher. Only complete, validated tracker results are stored. */
    @Synchronized
    fun storeBeatGrid(key: AnalysisKey, record: BeatGridCacheRecord) {
        require(record.sourceIdentity == key.uri)
        require(record.algorithmVersion == MusicalBeatTracker.ALGORITHM_VERSION)
        require(record.result.grid == null || record.result.grid.algorithmVersion == record.algorithmVersion)
        val grid = record.result.grid
        val values = ContentValues().apply {
            put("media_id", key.mediaId)
            put("modified", key.modified)
            put("uri", key.uri)
            put("analyzer", key.analyzer)
            put("algorithm_version", record.algorithmVersion)
            put("configuration_version", record.configurationVersion)
            put("state", record.state.name)
            put("window_start_us", record.window.startUs)
            put("window_end_us", record.window.endUs)
            put("timeline_identity", record.timeline.identity)
            put("timeline_origin", record.timeline.origin.name)
            put("timeline_mapping", record.timeline.mappingToMedia3.name)
            put("relative_to_original", record.timeline.relativeToOriginalSource.name)
            put("leading_silence_included", record.timeline.leadingSilenceIncluded.name)
            put("encoder_priming_corrected", record.timeline.encoderPrimingCorrected.name)
            put("edge_trim_applied", record.timeline.edgeTrimApplied.name)
            if (record.timeline.edgeTrimOffsetUs == null) putNull("edge_trim_offset_us")
            else put("edge_trim_offset_us", record.timeline.edgeTrimOffsetUs)
            put("result_source", grid?.source)
            put("result_reason", record.result.reason)
            put("selected_interpretation", record.result.selected?.name)
            put("ambiguous", if (record.result.ambiguous) 1 else 0)
            if (grid?.musicalBeatConfidence == null) putNull("musical_beat_confidence")
            else put("musical_beat_confidence", grid.musicalBeatConfidence.toDouble())
            if (grid?.downbeatConfidence == null) putNull("downbeat_confidence")
            else put("downbeat_confidence", grid.downbeatConfidence.toDouble())
            put("hypotheses_json", JSONArray().apply {
                record.result.hypotheses.forEach { hypothesis ->
                    put(JSONObject().put("interpretation", hypothesis.interpretation.name)
                        .put("bpm", hypothesis.bpm).put("support", hypothesis.support.toDouble()))
                }
            }.toString())
            if (grid == null) putNull("events_json") else put("events_json", JSONArray().apply {
                grid.events.forEach { event ->
                    put(JSONObject().put("index", event.index).put("sourceTimeUs", event.sourceTimeUs)
                        .putNullable("musicalBeatConfidence", event.musicalBeatConfidence)
                        .putNullable("downbeat", event.downbeat)
                        .putNullable("barIndex", event.barIndex)
                        .putNullable("downbeatConfidence", event.downbeatConfidence)
                        .putNullable("onsetStrength", event.onsetStrength))
                }
            }.toString())
        }
        writableDatabase.transaction {
            check(insertWithOnConflict("beat_grid", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "Unable to persist beat grid"
            }
        }
        beatGridMemory[key] = record
    }

    /** Call on a background dispatcher. */
    @Synchronized
    fun store(key: AnalysisKey, result: TrackAnalysis) {
        val values = ContentValues().apply {
            put("media_id", key.mediaId); put("modified", key.modified); put("uri", key.uri)
            put("analyzer", key.analyzer)
            put("bpm", result.bpm); put("camelot", result.camelotKey)
            put("beat_offset", result.beatOffsetMs); put("confidence", result.confidence)
            put("window_start", result.windowStartMs); put("window_end", result.windowEndMs)
            put("vocal_energy", result.vocalEnergy)
            result.pulseProvenance?.let {
                put("evidence_source", it.source.name)
                put("evidence_algorithm_version", it.algorithmVersion)
                put("evidence_timeline_origin", it.origin.name)
                put("evidence_timeline_mapping", it.mappingToMedia3.name)
            }
            result.edgeAnalysis?.let {
                put("edge_duration", it.durationMs)
                put("leading_silence", it.leadingSilenceMs)
                put("trailing_silence", it.trailingSilenceMs)
                put("intro_point", it.introPointMs)
                put("outro_skip", it.outroSkipMs)
                put("start_period", it.startGrid?.beatPeriodMs)
                put("start_phase", it.startGrid?.phaseOffsetMs)
                put("start_confidence", it.startGrid?.confidence)
                put("end_period", it.endGrid?.beatPeriodMs)
                put("end_phase", it.endGrid?.phaseOffsetMs)
                put("end_confidence", it.endGrid?.confidence)
                put("start_window_end", it.startWindowEndMs)
                put("end_window_start", it.endWindowStartMs)
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("analysis", "media_id=? AND uri=? AND modified<>?",
                arrayOf(key.mediaId, key.uri, key.modified.toString()))
            db.insertWithOnConflict("analysis", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                .also { check(it != -1L) { "Unable to persist track analysis" } }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        memory.keys.removeAll { it.mediaId == key.mediaId && it.uri == key.uri && it.modified != key.modified }
        memory[key] = result
    }

    private fun JSONObject.putNullable(name: String, value: Number?): JSONObject =
        if (value == null) put(name, JSONObject.NULL) else put(name, value)

    private fun JSONObject.putNullable(name: String, value: Boolean?): JSONObject =
        if (value == null) put(name, JSONObject.NULL) else put(name, value)

    private fun JSONObject.optionalLong(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private fun JSONObject.optionalFloat(name: String): Float? =
        if (isNull(name)) null else getDouble(name).toFloat()

    private fun JSONObject.optionalBoolean(name: String): Boolean? =
        if (isNull(name)) null else getBoolean(name)

    private fun android.database.Cursor.optionalFloat(index: Int): Float? =
        if (isNull(index)) null else getFloat(index)
}
