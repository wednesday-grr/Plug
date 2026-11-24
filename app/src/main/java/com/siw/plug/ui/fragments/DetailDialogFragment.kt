/*
 *     Copyright (C) 2024 Akane Foundation
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

package com.siw.plug.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.siw.plug.R
import com.siw.plug.logic.PlugPlaybackService
import com.siw.plug.logic.enableEdgeToEdgePaddingListener
import com.siw.plug.logic.getBitrate
import com.siw.plug.logic.getFile
import com.siw.plug.logic.hasImprovedMediaStore
import com.siw.plug.logic.toLocaleString
import com.siw.plug.logic.toMediaStoreId
import com.siw.plug.logic.ui.placeholderScaleToFit
import com.siw.plug.logic.utils.CalculationUtils.convertDurationToTimeStamp
import com.siw.plug.logic.utils.MediaItemList
import java.util.UUID

class DetailDialogFragment : BaseFragment(false) {
    private data class BeatUiStatus(val name: String, val beatCount: Int?)

    private val beatAnalysisOwnerId = UUID.randomUUID().toString()
    private var beatAnalysisItem: MediaItem? = null
    private var beatAnalysisController: MediaController? = null
    private var beatAnalysisStatusJob: Job? = null
    private var beatAnalysisActive = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_info_song, container, false)
        rootView.findViewById<AppBarLayout>(R.id.appbarlayout).enableEdgeToEdgePaddingListener()
        rootView.findViewById<View>(R.id.scrollView).enableEdgeToEdgePaddingListener()
        rootView.findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        val id = requireArguments().getString("Id")?.toMediaStoreId()
        val mediaItem = runBlocking { mainActivity.reader.idMapFlow.map { it[id] }.first() }
        if (mediaItem == null) {
            parentFragmentManager.popBackStack()
            return null
        }
        beatAnalysisItem = mediaItem
        val mediaMetadata = mediaItem.mediaMetadata
        val albumCoverImageView = rootView.findViewById<ImageView>(R.id.album_cover)
        val titleTextView = rootView.findViewById<TextView>(R.id.title)
        val artistTextView = rootView.findViewById<TextView>(R.id.artist)
        val albumArtistTextView = rootView.findViewById<TextView>(R.id.album_artist)
        val discNumberTextView = rootView.findViewById<TextView>(R.id.disc_number)
        val trackNumberTextView = rootView.findViewById<TextView>(R.id.track_num)
        val genreTextView = rootView.findViewById<TextView>(R.id.genre)
        val genreBox = rootView.findViewById<View>(R.id.genre_box)
        val yearTextView = rootView.findViewById<TextView>(R.id.date)
        val albumTextView = rootView.findViewById<TextView>(R.id.album)
        val durationTextView = rootView.findViewById<TextView>(R.id.duration)
        val mimeTypeTextView = rootView.findViewById<TextView>(R.id.mime)
        val pathTextView = rootView.findViewById<TextView>(R.id.path)
        val bitRateTextView = rootView.findViewById<TextView>(R.id.bit_rate)
        albumCoverImageView.load(mediaMetadata.artworkUri) {
            placeholderScaleToFit(R.drawable.ic_default_cover)
            crossfade(true)
            error(R.drawable.ic_default_cover)
        }
        titleTextView.text = mediaMetadata.title
        artistTextView.text = mediaMetadata.artist
        albumTextView.text = mediaMetadata.albumTitle
        if (mediaMetadata.albumArtist != null) {
            albumArtistTextView.text = mediaMetadata.albumArtist
        }
        discNumberTextView.text = mediaMetadata.discNumber?.toLocaleString()
        trackNumberTextView.text = mediaMetadata.trackNumber?.toLocaleString()
        if (hasImprovedMediaStore()) {
            if (mediaMetadata.genre != null) {
                genreTextView.text = mediaMetadata.genre
            }
        } else genreBox.visibility = View.GONE
        if (mediaMetadata.releaseYear != null || mediaMetadata.recordingYear != null) {
            yearTextView.text =
                (mediaMetadata.releaseYear ?: mediaMetadata.recordingYear)?.toLocaleString()
        }
        mediaMetadata.durationMs?.let { durationTextView.text = convertDurationToTimeStamp(it) }
        mimeTypeTextView.text = mediaItem.localConfiguration?.mimeType ?: "(null)"
        pathTextView.text = mediaItem.getFile()?.path
            ?: mediaItem.requestMetadata.mediaUri?.toString() ?: "(null)"
        val context = requireContext().applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val bitrate = mediaItem.getBitrate(context) // disk access
            withContext(Dispatchers.Main) {
                bitRateTextView.text = if (bitrate != null) {
                    getString(R.string.bitrate_format, bitrate / 1000)
                } else {
                    getString(R.string.bitrate_unknown)
                }
            }
        }
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val button = view.findViewById<MaterialButton>(R.id.analyze_beats)
        button.isEnabled = false
        button.setOnClickListener {
            val operation = if (beatAnalysisActive) "cancel" else "request"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    sendBeatAnalysisCommand(operation)?.let(::updateBeatAnalysisStatus)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("BeatAnalysis", "Could not $operation beat analysis", e)
                    showBeatAnalysisError()
                }
            }
        }
        mainActivity.controllerViewModel.addControllerCallback(viewLifecycleOwner.lifecycle) { controller, _ ->
            beatAnalysisController = controller
            button.isEnabled = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    sendBeatAnalysisCommand("status")?.let(::updateBeatAnalysisStatus)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("BeatAnalysis", "Could not load beat analysis status", e)
                    showBeatAnalysisError()
                }
            }
        }
    }

    override fun onDestroyView() {
        beatAnalysisStatusJob?.cancel()
        val controller = beatAnalysisController
        if (beatAnalysisActive && controller != null && isAdded) {
            mainActivity.lifecycleScope.launch {
                try {
                    sendBeatAnalysisCommand("cancel", controller)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("BeatAnalysis", "Could not cancel detail-screen request", e)
                }
            }
        }
        beatAnalysisActive = false
        beatAnalysisController = null
        beatAnalysisItem = null
        super.onDestroyView()
    }

    private suspend fun sendBeatAnalysisCommand(
        operation: String,
        controller: MediaController? = beatAnalysisController,
    ): BeatUiStatus? {
        controller ?: return null
        val args = Bundle().apply {
            putString("operation", operation)
            putString("owner_id", beatAnalysisOwnerId)
            beatAnalysisItem?.let { putBinder("item", MediaItemList(listOf(it))) }
        }
        val result = controller.sendCustomCommand(
            SessionCommand(PlugPlaybackService.SERVICE_BEAT_ANALYSIS, Bundle.EMPTY),
            args,
        ).await()
        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
            throw IllegalStateException("Beat analysis request rejected: ${result.resultCode}")
        }
        val status = result.extras.getString("beat_analysis_status") ?: return null
        val count = if (result.extras.containsKey("beat_analysis_count")) {
            result.extras.getInt("beat_analysis_count")
        } else null
        return BeatUiStatus(status, count)
    }

    private fun updateBeatAnalysisStatus(result: BeatUiStatus) {
        val button = view?.findViewById<MaterialButton>(R.id.analyze_beats) ?: return
        val statusView = view?.findViewById<TextView>(R.id.beat_analysis_status) ?: return
        val status = result.name
        beatAnalysisActive = status == "PENDING" || status == "ANALYZING"
        when (status) {
            "PENDING" -> {
                button.setText(R.string.beat_analysis_cancel)
                statusView.setText(R.string.beat_analysis_pending)
                pollBeatAnalysisStatus()
            }
            "ANALYZING" -> {
                button.setText(R.string.beat_analysis_cancel)
                statusView.setText(R.string.beat_analysis_running)
                pollBeatAnalysisStatus()
            }
            "DETECTED" -> {
                beatAnalysisStatusJob?.cancel()
                button.setText(R.string.beat_analysis_start)
                button.isEnabled = false
                statusView.text = getString(
                    R.string.beat_analysis_ready,
                    result.beatCount ?: 0,
                )
            }
            "EMPTY" -> {
                beatAnalysisStatusJob?.cancel()
                button.setText(R.string.beat_analysis_start)
                button.isEnabled = false
                statusView.setText(R.string.beat_analysis_empty)
            }
            "UNAVAILABLE" -> {
                beatAnalysisStatusJob?.cancel()
                button.setText(R.string.beat_analysis_start)
                button.isEnabled = false
                statusView.setText(R.string.beat_analysis_unavailable)
            }
            "FAILED" -> {
                beatAnalysisStatusJob?.cancel()
                button.setText(R.string.beat_analysis_start)
                button.isEnabled = true
                statusView.setText(R.string.beat_analysis_failed)
            }
            else -> {
                beatAnalysisStatusJob?.cancel()
                button.setText(R.string.beat_analysis_start)
                button.isEnabled = true
                statusView.text = ""
            }
        }
    }

    private fun pollBeatAnalysisStatus() {
        if (beatAnalysisStatusJob?.isActive == true) return
        beatAnalysisStatusJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && beatAnalysisActive) {
                delay(500)
                try {
                    sendBeatAnalysisCommand("status")?.let(::updateBeatAnalysisStatus)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("BeatAnalysis", "Could not refresh beat analysis status", e)
                    beatAnalysisStatusJob = null
                    showBeatAnalysisError()
                    return@launch
                }
            }
        }
    }

    private fun showBeatAnalysisError() {
        view?.findViewById<MaterialButton>(R.id.analyze_beats)?.apply {
            setText(R.string.beat_analysis_start)
            isEnabled = beatAnalysisController != null
        }
        view?.findViewById<TextView>(R.id.beat_analysis_status)
            ?.setText(R.string.beat_analysis_error)
        beatAnalysisActive = false
    }
}