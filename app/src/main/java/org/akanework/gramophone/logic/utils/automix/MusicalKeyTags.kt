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

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

data class MusicalKey private constructor(val camelotNumber: Int, val minor: Boolean) {
    val camelot: String get() = "$camelotNumber${if (minor) "A" else "B"}"
    val openKey: String
        get() = "${(camelotNumber + 4) % 12 + 1}${if (minor) "m" else "d"}"

    companion object {
        private val wheel = Regex("""^(1[0-2]|[1-9])([AaBbmd])$""")
        private val note = Regex(
            """^([A-Ga-g])([#b♯♭]?)(?:\s*(m|M|minor|major|min|maj))?$"""
        )
        private val majorWheel = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        private val minorWheel = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

        fun parse(value: String): MusicalKey? {
            val text = value.trim()
            wheel.matchEntire(text)?.let {
                val number = it.groupValues[1].toInt()
                val suffix = it.groupValues[2]
                val minor = suffix == "A" || suffix == "a" || suffix == "m"
                val camelotNumber = if (suffix == "m" || suffix == "d") (number + 6) % 12 + 1
                    else number
                return MusicalKey(camelotNumber, minor)
            }
            val match = note.matchEntire(text) ?: return null
            val pitch = when (match.groupValues[1].uppercase()) {
                "C" -> 0
                "D" -> 2
                "E" -> 4
                "F" -> 5
                "G" -> 7
                "A" -> 9
                "B" -> 11
                else -> return null
            }
            val accidental = when (match.groupValues[2]) {
                "#", "♯" -> 1
                "b", "♭" -> -1
                else -> 0
            }
            val scale = match.groupValues[3]
            val minor = scale == "m" || scale.equals("minor", true) || scale.equals("min", true)
            val index = (pitch + accidental + 12) % 12
            return MusicalKey(if (minor) minorWheel[index] else majorWheel[index], minor)
        }
    }
}

object MusicalKeyTags {
    fun read(metadata: Metadata?): MusicalKey? {
        if (metadata == null) return null
        for (index in 0 until metadata.length()) {
            val values = when (val entry = metadata[index]) {
                is TextInformationFrame -> when {
                    entry.id == "TKEY" || entry.id == "TKE" -> entry.values
                    (entry.id == "TXXX" || entry.id == "TXX") && entry.description.isKeyName() ->
                        entry.values
                    else -> emptyList()
                }
                is VorbisComment ->
                    if (entry.key.isKeyName()) listOf(entry.value) else emptyList()
                else -> emptyList()
            }
            for (value in values) {
                MusicalKey.parse(value)?.let { return it }
            }
        }
        return null
    }

    private fun String?.isKeyName(): Boolean =
        this.equals("INITIALKEY", ignoreCase = true) || this.equals("KEY", ignoreCase = true)
}
