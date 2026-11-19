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

data class AnalysisKey(val mediaId: String, val modified: Long, val uri: String) {
    companion object {
        fun from(item: MediaItem): AnalysisKey? {
            val uri = item.localConfiguration?.uri ?: return null
            val modified = item.mediaMetadata.modifiedDate ?: return null
            if (item.mediaId.isBlank() || uri.scheme !in listOf("content", "file")) return null
            return AnalysisKey(item.mediaId, modified, uri.toString())
        }
    }
}

class TrackAnalysisCache(context: Context) : SQLiteOpenHelper(context, "track_analysis.db", null, 2) {
    private val memory = ConcurrentHashMap<AnalysisKey, TrackAnalysis>()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE analysis (
            media_id TEXT NOT NULL, modified INTEGER NOT NULL, uri TEXT NOT NULL,
            bpm REAL, camelot TEXT, beat_offset REAL, confidence REAL NOT NULL,
            window_start INTEGER NOT NULL, window_end INTEGER NOT NULL, vocal_energy REAL,
            PRIMARY KEY(media_id, modified, uri))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL("ALTER TABLE analysis ADD COLUMN vocal_energy REAL")
        }
    }

    fun peek(key: AnalysisKey): TrackAnalysis? = memory[key]

    /** Call on a background dispatcher. */
    fun load(key: AnalysisKey): TrackAnalysis? {
        memory[key]?.let { return it }
        readableDatabase.query(
            "analysis", arrayOf(
                "bpm", "camelot", "beat_offset", "confidence", "window_start", "window_end",
                "vocal_energy"
            ),
            "media_id=? AND modified=? AND uri=?", arrayOf(key.mediaId, key.modified.toString(), key.uri),
            null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val result = TrackAnalysis(
                if (cursor.isNull(0)) null else cursor.getFloat(0),
                if (cursor.isNull(1)) null else cursor.getString(1),
                if (cursor.isNull(2)) null else cursor.getDouble(2),
                cursor.getFloat(3), cursor.getLong(4), cursor.getLong(5),
                if (cursor.isNull(6)) null else cursor.getFloat(6),
            )
            memory[key] = result
            return result
        }
    }

    /** Call on a background dispatcher. */
    fun store(key: AnalysisKey, result: TrackAnalysis) {
        val values = ContentValues().apply {
            put("media_id", key.mediaId); put("modified", key.modified); put("uri", key.uri)
            put("bpm", result.bpm); put("camelot", result.camelotKey)
            put("beat_offset", result.beatOffsetMs); put("confidence", result.confidence)
            put("window_start", result.windowStartMs); put("window_end", result.windowEndMs)
            put("vocal_energy", result.vocalEnergy)
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
        memory.keys.removeAll { it.mediaId == key.mediaId && it.uri == key.uri && it != key }
        memory[key] = result
    }
}
