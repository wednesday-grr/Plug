/*
 *     Copyright (C) 2026 The Gramophone authors
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.siw.plug.logic.utils.automix

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

/**
 * Reads a tagged tempo, not a beat grid: tags do not establish the phase of the beats.
 */
object BpmTags {
    fun read(metadata: Metadata?): Float? {
        if (metadata == null) return null
        for (index in 0 until metadata.length()) {
            val entry = metadata[index]
            val values = when (entry) {
                is TextInformationFrame -> when {
                    entry.id == "TBPM" || entry.id == "TBP" -> entry.values
                    (entry.id == "TXXX" || entry.id == "TXX") &&
                        entry.description.isTempoKey() -> entry.values
                    else -> emptyList()
                }
                is VorbisComment ->
                    if (entry.key.isTempoKey()) listOf(entry.value) else emptyList()
                else -> emptyList()
            }
            for (value in values) {
                val bpm = value.trim().toFloatOrNull()
                if (bpm != null && bpm.isFinite() && bpm > 0f) return bpm
            }
        }
        return null
    }

    private fun String?.isTempoKey(): Boolean =
        this.equals("BPM", ignoreCase = true) || this.equals("TEMPO", ignoreCase = true)
}
