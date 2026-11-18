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
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import org.akanework.gramophone.logic.utils.PostAmpAudioOutputProvider
import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.akanework.gramophone.logic.utils.ReplayGainUtil
import org.akanework.gramophone.logic.utils.exoplayer.ExtendedAudioOutputProvider
import org.akanework.gramophone.logic.utils.exoplayer.PlugRenderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class PlugRenderFactoryTest {
    @Test
    fun captureRequiresPcmMixingMode() {
        val context = RuntimeEnvironment.getApplication()
        assertThrows(IllegalArgumentException::class.java) {
            PlugRenderFactory(context, {}, {}, onPcmTransient = {})
        }
        PlugRenderFactory(context, {}, {}, pcmMixing = true, onPcmTransient = {})
    }

    @Test
    fun factoriesOwnIndependentReplayGainSettings() {
        val context = RuntimeEnvironment.getApplication()
        val deckA = PlugRenderFactory(context, {}, {})
        val deckB = PlugRenderFactory(context, {}, {})
        val processorA = deckA.replayGainAudioProcessor
        val processorB = deckB.replayGainAudioProcessor

        assertNotSame(processorA, processorB)
        processorA.setMode(ReplayGainUtil.Mode.Track, true)
        processorA.setRgGain(4)
        processorA.setNonRgGain(-3)
        processorA.setBoostGain(2)
        processorA.setReduceGain(true)
        processorA.setOffloadEnabled(true)
        assertEquals(ReplayGainUtil.Mode.None, processorB.mode)
        assertEquals(0, processorB.rgGain)
        assertEquals(0, processorB.nonRgGain)
        assertEquals(0, processorB.boostGain)
        assertEquals(false, processorB.reduceGain)
        assertEquals(false, processorB.offloadEnabled)
    }

    @Test
    fun mixingSinkRejectsEncodedPassthroughAndOffload() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        val provider = PostAmpAudioOutputProvider(
            ExtendedAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()),
            ReplayGainAudioProcessor(), context, pcmMixing = true
        )
        try {
            val encoded = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setSampleRate(48000)
                .setChannelCount(2)
                .build()
            val encodedSupport = provider.getFormatSupport(
                AudioOutputProvider.FormatConfig.Builder(encoded).build()
            )
            assertEquals(AudioOutputProvider.FORMAT_UNSUPPORTED, encodedSupport.supportLevel)
            assertEquals(false, encodedSupport.isFormatSupportedForOffload)
            assertThrows(AudioOutputProvider.ConfigurationException::class.java) {
                provider.getOutputConfig(AudioOutputProvider.FormatConfig.Builder(encoded).build())
            }
            val raw = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelCount(2)
                .build()
            val rawSupport = provider.getFormatSupport(
                AudioOutputProvider.FormatConfig.Builder(raw).build()
            )
            assertEquals(false, rawSupport.isFormatSupportedForOffload)
            val highResolution = raw.buildUpon().setPcmEncoding(C.ENCODING_PCM_24BIT).build()
            assertEquals(
                AudioOutputProvider.FORMAT_UNSUPPORTED,
                provider.getFormatSupport(
                    AudioOutputProvider.FormatConfig.Builder(highResolution).build()
                ).supportLevel
            )
        } finally {
            provider.release()
        }
    }
}
