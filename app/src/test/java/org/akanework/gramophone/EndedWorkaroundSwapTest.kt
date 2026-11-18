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

package org.akanework.gramophone

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.akanework.gramophone.logic.QueueBoard
import org.akanework.gramophone.logic.utils.CircularShuffleOrder
import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.akanework.gramophone.logic.utils.automix.AutomixPlayer
import org.akanework.gramophone.logic.utils.automix.DeckTransition
import org.akanework.gramophone.logic.utils.exoplayer.EndedWorkaroundPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class EndedWorkaroundSwapTest {
    @Test
    fun shuffleChangeInvalidatesPreparedQueueEvenWhenNextItemIsUnchanged() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val automix = AutomixPlayer(context, ReplayGainAudioProcessor().snapshotSettings())
        val wrapper = EndedWorkaroundPlayer(
            context, context.getSharedPreferences("queue-invalidation-test", Context.MODE_PRIVATE),
            automix, { null }, QueueBoard(GramophonePlaybackService())
        )
        val items = (0..3).map { MediaItem.fromUri("file:///$it.mp3") }
        try {
            automix.activeExoPlayer.shuffleOrder = CircularShuffleOrder(wrapper, 0, 0, 1L)
            automix.setMediaItems(items, 0, 0)
            automix.activeExoPlayer.shuffleOrder =
                CircularShuffleOrder(wrapper, intArrayOf(0, 1, 2, 3), 1L, null)
            automix.shuffleModeEnabled = true
            automix.setNextMediaItem(items[1])
            AutomixPlayer::class.java.getDeclaredMethod("prepareStandby", MediaItem::class.java)
                .apply { isAccessible = true }.invoke(automix, items[1])
            val prepared = AutomixPlayer::class.java.getDeclaredField("preparedItem")
                .apply { isAccessible = true }
            assertEquals(items[1], prepared.get(automix))
            automix.activeExoPlayer.shuffleOrder =
                CircularShuffleOrder(wrapper, intArrayOf(0, 1, 3, 2), 1L, null)
            automix.setNextMediaItem(items[1])
            assertNull(prepared.get(automix))
            assertEquals(items[1], automix.pendingNextMediaItem)
        } finally {
            wrapper.release()
        }
    }

    @Test
    fun automixHandoffAdvancesOnceAndKeepsFollowingQueueItem() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val automix = AutomixPlayer(context, ReplayGainAudioProcessor().snapshotSettings())
        val wrapper = EndedWorkaroundPlayer(
            context, context.getSharedPreferences("handoff-test", Context.MODE_PRIVATE),
            automix, { null }, QueueBoard(GramophonePlaybackService())
        )
        val items = listOf(
            MediaItem.fromUri("file:///same.mp3"), MediaItem.fromUri("file:///same.mp3"),
            MediaItem.fromUri("file:///following.mp3")
        )
        val transitions = mutableListOf<Int>()
        try {
            automix.activeExoPlayer.shuffleOrder = CircularShuffleOrder(wrapper, 0, 0, 1L)
            automix.setMediaItems(items, 0, 1000)
            shadowOf(Looper.getMainLooper()).idle()
            wrapper.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    transitions.add(reason)
                }
            })
            wrapper.currentTimeline
            val decksField = AutomixPlayer::class.java.getDeclaredField("decks").apply { isAccessible = true }
            val decks = decksField.get(automix)
            val standby = decks.javaClass.getDeclaredField("b").apply { isAccessible = true }
                .get(decks) as ExoPlayer
            standby.setMediaItems(items, 1, 2000)
            val transition = AutomixPlayer::class.java.getDeclaredField("transition")
                .apply { isAccessible = true }.get(automix) as DeckTransition
            transition.beginPreparation()
            transition.markReady()
            transition.start(1, 0)
            AutomixPlayer::class.java.getDeclaredMethod("handoff", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(automix, 1L)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, wrapper.currentMediaItemIndex)
            assertEquals(2000L, wrapper.currentPosition)
            assertEquals(listOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO), transitions)
            assertEquals(items[2], automix.pendingNextMediaItem)
            assertSame(standby, wrapper.exoPlayer)
        } finally {
            wrapper.release()
        }
    }

    @Test
    fun automixTracksQueueRepeatAndShuffleOrder() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val automix = AutomixPlayer(context, ReplayGainAudioProcessor().snapshotSettings())
        val wrapper = EndedWorkaroundPlayer(
            context, context.getSharedPreferences("automix-swap-test", Context.MODE_PRIVATE),
            automix, { null }, QueueBoard(GramophonePlaybackService())
        )
        val items = listOf(
            MediaItem.fromUri("file:///one.mp3"), MediaItem.fromUri("file:///two.mp3"),
            MediaItem.fromUri("file:///three.mp3")
        )
        try {
            automix.activeExoPlayer.shuffleOrder = CircularShuffleOrder(wrapper, 0, 0, 1L)
            automix.setMediaItems(items, 0, 0)
            automix.activeExoPlayer.shuffleOrder =
                CircularShuffleOrder(wrapper, intArrayOf(0, 2, 1), 1L, null)
            automix.shuffleModeEnabled = true
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(items[2], automix.pendingNextMediaItem)
            automix.repeatMode = Player.REPEAT_MODE_ONE
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(automix.pendingNextMediaItem)
            automix.repeatMode = Player.REPEAT_MODE_ALL
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(items[2], automix.pendingNextMediaItem)
        } finally {
            wrapper.release()
        }
    }

    @Test
    fun swapsDelegateWithoutReplacingQueueIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val old = ExoPlayer.Builder(context).build()
        val replacement = ExoPlayer.Builder(context).build()
        val wrapper = EndedWorkaroundPlayer(
            context, context.getSharedPreferences("swap-test", Context.MODE_PRIVATE),
            old, { null }, QueueBoard(GramophonePlaybackService())
        )
        try {
            val items = listOf(
                MediaItem.fromUri("file:///one.mp3"), MediaItem.fromUri("file:///two.mp3")
            )
            old.setMediaItems(items, 1, 12000)
            replacement.setMediaItems(items, 1, 12000)
            wrapper.currentQueueId = 42
            wrapper.currentTitle = "Queue"
            wrapper.currentIsPinned = true
            wrapper.currentIsOriginal = true
            wrapper.replaceExoPlayer(replacement, false)
            assertSame(replacement, wrapper.exoPlayer)
            assertEquals(42L, wrapper.currentQueueId)
            assertEquals("Queue", wrapper.currentTitle)
            assertEquals(true, wrapper.currentIsPinned)
            assertEquals(true, wrapper.currentIsOriginal)
            assertEquals(2, wrapper.mediaItemCount)
            assertEquals(1, wrapper.currentMediaItemIndex)
            assertEquals(12000L, wrapper.currentPosition)
        } finally {
            old.release()
            wrapper.release()
        }
    }
}
