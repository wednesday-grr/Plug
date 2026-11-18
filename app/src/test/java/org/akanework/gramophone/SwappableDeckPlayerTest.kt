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
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.akanework.gramophone.logic.utils.automix.SwappableDeckPlayer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class SwappableDeckPlayerTest {
    @Test
    fun handoffExposesNextItemAndOneAutomaticTransition() {
        val context = RuntimeEnvironment.getApplication()
        val outgoing = ExoPlayer.Builder(context).build()
        val incoming = ExoPlayer.Builder(context).build()
        val first = MediaItem.fromUri("file:///one.mp3")
        val second = MediaItem.fromUri("file:///two.mp3")
        outgoing.setMediaItems(listOf(first, second), 0, 1000)
        incoming.setMediaItems(listOf(first, second), 1, 2000)
        val wrapper = SwappableDeckPlayer(outgoing)
        val transitions = mutableListOf<Pair<MediaItem?, Int>>()
        wrapper.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions.add(mediaItem to reason)
            }
        })
        try {
            wrapper.handoff(incoming)
            assertEquals(2, wrapper.mediaItemCount)
            assertEquals(1, wrapper.currentMediaItemIndex)
            assertEquals(2000L, wrapper.currentPosition)
            assertEquals(listOf(second to Player.MEDIA_ITEM_TRANSITION_REASON_AUTO), transitions)
            outgoing.clearMediaItems()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(second to Player.MEDIA_ITEM_TRANSITION_REASON_AUTO), transitions)
            assertEquals(second, wrapper.currentMediaItem)
        } finally {
            outgoing.release()
            wrapper.release()
        }
    }

    @Test
    fun reportsUserVolumeInsteadOfDeckFadeGain() {
        val context = RuntimeEnvironment.getApplication()
        val outgoing = ExoPlayer.Builder(context).build()
        val incoming = ExoPlayer.Builder(context).build()
        val wrapper = SwappableDeckPlayer(outgoing)
        try {
            wrapper.setExposedVolume(0.7f)
            outgoing.volume = 0.1f
            incoming.volume = 0.4f
            wrapper.handoff(incoming)
            assertEquals(0.7f, wrapper.volume, 0f)
        } finally {
            outgoing.release()
            wrapper.release()
        }
    }
}
