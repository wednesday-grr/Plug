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
import androidx.media3.exoplayer.ExoPlayer

data class ResolvedTrackAudioMetadata(
    val bpm: Float?,
    val camelotKey: String?,
    val beatGrid: BeatPhaseAligner.Grid?,
)

object TrackAudioMetadataResolver {
    fun resolvePlayer(player: ExoPlayer, cached: TrackAnalysis?): ResolvedTrackAudioMetadata {
        val tags = mutableListOf(player.audioFormat?.metadata)
        for (group in player.currentTracks.groups) {
            for (index in 0 until group.length) {
                tags.add(group.getTrackFormat(index).metadata)
            }
        }
        return resolve(tags, cached, player.currentPosition)
    }

    fun resolve(tags: Iterable<Metadata?>, cached: TrackAnalysis?, positionMs: Long): ResolvedTrackAudioMetadata {
        val metadata = tags.filterNotNull()
        val taggedBpm = metadata.firstNotNullOfOrNull(BpmTags::read)
        val taggedKey = metadata.firstNotNullOfOrNull(MusicalKeyTags::read)?.camelot
        val bpm = taggedBpm ?: cached?.bpm
        // A phase measured at a different tempo or outside its window is not a valid grid.
        val grid = if (cached != null && bpm != null && cached.bpm != null &&
            kotlin.math.abs(bpm - cached.bpm) < 0.001f && cached.beatOffsetMs != null &&
            positionMs in cached.windowStartMs..cached.windowEndMs
        ) BeatPhaseAligner.Grid(60_000.0 / bpm, cached.beatOffsetMs, cached.confidence) else null
        return ResolvedTrackAudioMetadata(bpm, taggedKey ?: cached?.camelotKey, grid)
    }
}
