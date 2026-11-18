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
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.akanework.gramophone.logic.utils.automix.AutomixPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class AutomixPlayerTest {
    @Test
    fun bothDecksUseTheRequestedSession() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val sessionId = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .generateAudioSessionId()
        val player = AutomixPlayer(
            context, ReplayGainAudioProcessor().snapshotSettings(), audioSessionId = sessionId
        )
        try {
            assertEquals(sessionId to sessionId, player.deckAudioSessionIds())
            player.updateAutomixConfig(true, false, 5)
            player.stop()
            assertEquals(sessionId to sessionId, player.deckAudioSessionIds())
        } finally {
            player.release()
        }
    }

    @Test
    fun queueVolumeAndControlsRemainUsableWithoutASecondTrack() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val player = AutomixPlayer(context, ReplayGainAudioProcessor().snapshotSettings())
        val first = MediaItem.fromUri("file:///one.mp3")
        val second = MediaItem.fromUri("file:///two.mp3")
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), false
            )
            player.setMediaItems(listOf(first, second), 0, 1000)
            player.updateAutomixConfig(true, true, 5)
            player.setNextMediaItem(second)
            player.volume = 0.6f
            assertEquals(0.6f, player.volume, 0f)
            player.mute()
            assertEquals(0f, player.volume, 0f)
            player.unmute()
            assertEquals(0.6f, player.volume, 0f)
            player.seekTo(1, 5000)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(5000L, player.currentPosition)
            assertEquals(1, player.currentMediaItemIndex)
            player.setNextMediaItem(null)
            player.repeatMode = Player.REPEAT_MODE_ALL
            assertEquals(2, player.mediaItemCount)
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            player.stop()
        } finally {
            player.release()
        }
    }

    @Test
    fun rejectsInvalidConfiguration() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val player = AutomixPlayer(
            context, ReplayGainAudioProcessor().snapshotSettings()
        )
        try {
            assertThrows(IllegalArgumentException::class.java) {
                player.updateAutomixConfig(true, true, 0)
            }
            assertThrows(IllegalArgumentException::class.java) { player.volume = Float.NaN }
            assertThrows(IllegalArgumentException::class.java) {
                player.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_ALARM).build(), true
                )
            }
        } finally {
            player.release()
        }
    }

    @Test
    fun focusLossPausesAndGainResumesOnlyWhilePlaybackIsDesired() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioShadow = shadowOf(manager)
        val player = AutomixPlayer(context, ReplayGainAudioProcessor().snapshotSettings())
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), true
            )
            player.setMediaItem(MediaItem.fromUri("file:///one.mp3"))
            player.play()
            shadowOf(Looper.getMainLooper()).idle()
            val listener = audioShadow.lastAudioFocusRequest.listener
            assertTrue(player.playWhenReady)
            player.volume = 0.6f
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(player.playWhenReady)
            assertEquals(0.6f, player.volume, 0f)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.playWhenReady)
            player.play()
            assertFalse(player.playWhenReady)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(player.playWhenReady)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            shadowOf(Looper.getMainLooper()).idle()
            player.pause()
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.playWhenReady)
        } finally {
            player.release()
        }
    }

    @Test
    fun deniedFocusDoesNotStartPlayback() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val player = AutomixPlayer(context, ReplayGainAudioProcessor().snapshotSettings())
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), true
            )
            shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            player.play()
            assertFalse(player.playWhenReady)
        } finally {
            player.release()
        }
    }

    @Test
    fun noisyOutputPausesPlaybackAndDoesNotResumeOnFocusGain() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val player = AutomixPlayer(context, ReplayGainAudioProcessor().snapshotSettings())
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), true
            )
            player.play()
            shadowOf(Looper.getMainLooper()).idle()
            val listener = shadowOf(manager).lastAudioFocusRequest.listener
            context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.playWhenReady)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.playWhenReady)
        } finally {
            player.release()
        }
    }
}
