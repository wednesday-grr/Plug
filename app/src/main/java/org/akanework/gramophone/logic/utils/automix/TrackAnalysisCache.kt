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
import androidx.media3.common.MediaItem
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

class TrackAnalysisCache(context: Context) : SQLiteOpenHelper(context, "track_analysis.db", null, 4) {
    private val memory = ConcurrentHashMap<AnalysisKey, TrackAnalysis>()
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
            onCreate(db)
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
    }

    fun peek(key: AnalysisKey): TrackAnalysis? = memory[key]?.takeIf { isCurrent(key, it) }

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
                "end_period", "end_phase", "end_confidence", "start_window_end", "end_window_start"
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
            )
            if (!isCurrent(key, result)) return null
            memory[key] = result
            return result
        }
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
}
