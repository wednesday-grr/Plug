/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone.ui.components

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.util.Log
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.PlugPlaybackService
import org.akanework.gramophone.logic.dpToPx
import org.akanework.gramophone.logic.utils.automix.EffectDecisionReason
import org.akanework.gramophone.logic.utils.automix.EffectExecutionStatus
import org.akanework.gramophone.logic.utils.automix.EffectLifecycleState
import org.akanework.gramophone.logic.utils.automix.EffectSelectionStatus
import org.akanework.gramophone.logic.utils.automix.TransitionExecutionStatus
import java.text.NumberFormat
import kotlin.math.abs

internal data class DisplayEffect(
    val id: String,
    val selection: EffectSelectionStatus,
    val reasons: List<EffectDecisionReason>,
    val beatOut: Float?,
    val beatIn: Float?,
    val gridOut: Float?,
    val gridIn: Float?,
    val minimumGrid: Float?,
    val maximumTempoDeviation: Float?,
    val execution: EffectExecutionStatus?,
    val processedFrames: Long?,
    val sampleRate: Int?,
    val executionDetail: String?,
    val tempoInterpretation: String? = null,
    val tempoRatio: Float? = null,
    val effectiveIncomingBpm: Float? = null,
    val pitchPreserved: Boolean? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val timingLimitation: String? = null,
)
internal data class DisplayBeatEffect(
    val id: String,
    val available: Boolean,
    val evaluated: Boolean,
    val selected: Boolean,
    val state: EffectLifecycleState,
    val gridConfidence: Float?,
    val beatPositionMs: Long?,
    val beatIndex: Long?,
    val loopStartMs: Long?,
    val loopEndMs: Long?,
    val tempoInterpretation: String?,
    val tempoRatio: Float?,
    val reason: String?,
    val timingLimitation: String?,
)
internal data class DisplayTransition(
    val generation: Long,
    val status: TransitionExecutionStatus,
    val outgoing: String?,
    val incoming: String?,
    val duration: Long,
    val style: String,
    val styleReasons: List<EffectDecisionReason>,
    val analyzer: String?,
    val outgoingBpm: Float?,
    val incomingBpm: Float?,
    val outgoingGrid: Float?,
    val incomingGrid: Float?,
    val outgoingKey: String?,
    val incomingKey: String?,
    val outgoingVocal: Float?,
    val incomingVocal: Float?,
    val selectorDifference: Float?,
    val selectorThreshold: Float?,
    val selectorRising: Float?,
    val selectorRisingThreshold: Float?,
    val selectorVocalThreshold: Float?,
    val effects: List<DisplayEffect>,
    val beatEffect: DisplayBeatEffect? = null,
)

internal data class DisplayTrackAnalysis(
    val title: String?,
    val analyzer: String?,
    val status: String?,
    val bpm: Float?,
    val key: String?,
    val failed: Boolean,
)

internal sealed interface AudioQualityInfoUiState {
    data object Loading : AudioQualityInfoUiState
    data object Unavailable : AudioQualityInfoUiState
    data object Error : AudioQualityInfoUiState
    data class Available(val transition: DisplayTransition?, val beatEffect: DisplayBeatEffect? = null) :
        AudioQualityInfoUiState
    data class TrackAnalysis(val current: DisplayTrackAnalysis, val next: DisplayTrackAnalysis?) :
        AudioQualityInfoUiState
}

internal class AudioQualityInfoBottomSheet(
    context: Context,
    private val player: () -> MediaBrowser?,
) : BottomSheetDialog(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = MutableStateFlow<AudioQualityInfoUiState>(AudioQualityInfoUiState.Loading)
    private var updateJob: Job? = null
    internal val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = 24.dpToPx(context)
        setPadding(padding, padding, padding, padding)
    }

    init {
        setContentView(ScrollView(context).apply { addView(content) })
        setOnShowListener {
            scope.launch { state.collect { render(it) } }
            updateJob = scope.launch {
                while (true) {
                    refresh()
                    delay(1000)
                }
            }
        }
        setOnDismissListener {
            updateJob?.cancel()
            scope.cancel()
        }
    }

    private suspend fun refresh() {
        val browser = player()
        if (browser == null) {
            state.value = AudioQualityInfoUiState.Unavailable
            return
        }
        try {
            val result = browser.sendCustomCommand(
                SessionCommand(PlugPlaybackService.SERVICE_GET_TRANSITION_INFO, Bundle.EMPTY), Bundle.EMPTY
            ).await()
            if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                Log.w("AudioQualityInfo", "Transition info request failed: ${result.resultCode}")
                state.value = AudioQualityInfoUiState.Error
                return
            }
            val data = result.extras
            if (!data.containsKey("generation")) {
                parseBeatEffect(data)?.let {
                    state.value = AudioQualityInfoUiState.Available(null, it)
                    return
                }
                refreshTrackAnalysis(browser)
                return
            }
            val status = TransitionExecutionStatus.valueOf(requireNotNull(data.getString("status")))
            val active = status == TransitionExecutionStatus.ACTIVE
            val expectedId = data.getString(if (active) "outgoing_id" else "incoming_id")
            val expectedIndex = data.getInt(if (active) "outgoing_index" else "incoming_index", -1)
            if (player() !== browser || browser.currentMediaItemIndex != expectedIndex ||
                browser.currentMediaItem?.mediaId != expectedId
            ) {
                state.value = AudioQualityInfoUiState.Unavailable
                return
            }
            fun value(key: String) = if (data.containsKey(key)) data.getFloat(key) else null
            @Suppress("DEPRECATION")
            val effects = data.getParcelableArrayList<Bundle>("effects").orEmpty().map {
                fun evidence(key: String) = if (it.containsKey(key)) it.getFloat(key) else null
                val selection = EffectSelectionStatus.valueOf(requireNotNull(it.getString("selection")))
                val execution = it.getString("execution")?.let(EffectExecutionStatus::valueOf)
                check((selection == EffectSelectionStatus.SELECTED && execution != null) ||
                    (selection == EffectSelectionStatus.SKIPPED && execution == null))
                DisplayEffect(
                    requireNotNull(it.getString("id")),
                    selection,
                    it.getStringArrayList("reasons").orEmpty().map(EffectDecisionReason::valueOf),
                    evidence("out_bpm"), evidence("in_bpm"), evidence("out_grid"),
                    evidence("in_grid"), evidence("minimum_grid"), evidence("max_tempo_deviation"),
                    execution,
                    it.takeIf { bundle -> bundle.containsKey("processed_frames") }
                        ?.getLong("processed_frames"),
                    it.takeIf { bundle -> bundle.containsKey("sample_rate") }
                        ?.getInt("sample_rate"),
                    it.getString("execution_detail"),
                    it.getString("tempo_interpretation"), evidence("tempo_ratio"),
                    evidence("effective_incoming_bpm"),
                    it.takeIf { bundle -> bundle.containsKey("pitch_preserved") }
                        ?.getBoolean("pitch_preserved"),
                    it.takeIf { bundle -> bundle.containsKey("loop_start_ms") }
                        ?.getLong("loop_start_ms"),
                    it.takeIf { bundle -> bundle.containsKey("loop_end_ms") }
                        ?.getLong("loop_end_ms"),
                    it.getString("timing_limitation"),
                )
            }
            state.value = AudioQualityInfoUiState.Available(DisplayTransition(
                data.getLong("generation"), status,
                data.getString("outgoing_title"), data.getString("incoming_title"),
                data.getLong("duration"), requireNotNull(data.getString("style")),
                data.getStringArrayList("style_reasons").orEmpty().map(EffectDecisionReason::valueOf),
                data.getString("analyzer"), value("outgoing_bpm"), value("incoming_bpm"),
                value("outgoing_grid"), value("incoming_grid"), data.getString("outgoing_key"),
                data.getString("incoming_key"), value("outgoing_vocal"), value("incoming_vocal"),
                value("selector_difference"), value("selector_threshold"), value("selector_rising"),
                value("selector_rising_threshold"), value("selector_vocal_threshold"), effects,
                parseBeatEffect(data),
            ), parseBeatEffect(data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AudioQualityInfo", "Unable to retrieve transition info", e)
            state.value = AudioQualityInfoUiState.Error
        }
    }

    private suspend fun refreshTrackAnalysis(browser: MediaBrowser) {
        val result = browser.sendCustomCommand(
            SessionCommand(PlugPlaybackService.SERVICE_GET_DJ_METRICS, Bundle.EMPTY), Bundle.EMPTY
        ).await()
        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
            Log.w("AudioQualityInfo", "Track analysis request failed: ${result.resultCode}")
            state.value = AudioQualityInfoUiState.Error
            return
        }

        val data = result.extras
        val index = data.getInt("index", -1)
        val mediaId = data.getString("media_id")
        if (index < 0 || mediaId == null || browser.currentMediaItemIndex != index ||
            browser.currentMediaItem?.mediaId != mediaId
        ) {
            state.value = AudioQualityInfoUiState.Unavailable
            return
        }
        fun track(prefix: String, title: String?, failed: Boolean) = DisplayTrackAnalysis(
            title = title,
            analyzer = data.getString("${prefix}analyzer") ?: data.getString("analyzer"),
            status = data.getString("${prefix}analysis_status"),
            bpm = if (data.containsKey("${prefix}bpm")) data.getFloat("${prefix}bpm") else null,
            key = data.getString("${prefix}key"),
            failed = failed,
        )
        val current = track("", data.getString("title"), data.getBoolean("analysis_failed"))
        val next = if (data.getBoolean("next_available")) {
            val nextId = data.getString("next_media_id")
            if (nextId == null) null else DisplayTrackAnalysis(
                title = data.getString("next_title"),
                analyzer = data.getString("analyzer"),
                status = data.getString("next_analysis_status"),
                bpm = if (data.containsKey("next_bpm")) data.getFloat("next_bpm") else null,
                key = data.getString("next_key"),
                failed = data.getBoolean("next_analysis_failed"),
            )
        } else null
        state.value = AudioQualityInfoUiState.TrackAnalysis(current, next)
    }

    private fun parseBeatEffect(data: Bundle): DisplayBeatEffect? {
        val bundle = data.getBundle("beat_effect") ?: return null
        val id = bundle.getString("id") ?: return null
        fun long(key: String) = if (bundle.containsKey(key)) bundle.getLong(key) else null
        fun float(key: String) = if (bundle.containsKey(key)) bundle.getFloat(key) else null
        return DisplayBeatEffect(
            id,
            bundle.getBoolean("available"),
            bundle.getBoolean("evaluated"),
            bundle.getBoolean("selected"),
            EffectLifecycleState.valueOf(requireNotNull(bundle.getString("state"))),
            float("grid_confidence"), long("beat_position_ms"), long("beat_index"), long("loop_start_ms"),
            long("loop_end_ms"), bundle.getString("tempo_interpretation"),
            float("tempo_ratio"), bundle.getString("reason"),
            bundle.getString("timing_limitation"),
        )
    }

    internal fun render(state: AudioQualityInfoUiState) {
        content.removeAllViews()
        heading(context.getString(R.string.transition_info_title))
        when (state) {
            AudioQualityInfoUiState.Loading -> text(context.getString(R.string.transition_info_loading))
            AudioQualityInfoUiState.Unavailable -> text(context.getString(R.string.transition_info_unavailable))
            AudioQualityInfoUiState.Error -> text(context.getString(R.string.transition_info_error))
            is AudioQualityInfoUiState.Available -> {
                state.transition?.let(::renderTransition)
                state.beatEffect?.let(::renderBeatEffect)
            }
            is AudioQualityInfoUiState.TrackAnalysis -> renderTrackAnalysis(state)
        }
    }

    private fun renderTrackAnalysis(state: AudioQualityInfoUiState.TrackAnalysis) {
        heading(context.getString(R.string.transition_info_track_analysis))
        text(context.getString(R.string.transition_info_no_transition))
        renderTrack(state.current, R.string.transition_info_current_track)
        state.next?.let { renderTrack(it, R.string.transition_info_next_track) }
            ?: text(context.getString(R.string.transition_info_no_next_track))
        text(context.getString(R.string.transition_info_analyzer_note))
    }

    private fun renderTrack(track: DisplayTrackAnalysis, label: Int) {
        val unknown = context.getString(R.string.transition_info_no_data)
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
        heading(context.getString(label))
        fun row(resource: Int, value: String?) =
            text(context.getString(R.string.transition_info_row, context.getString(resource), value ?: unknown))
        row(R.string.transition_info_outgoing, track.title)
        row(R.string.transition_info_status, context.getString(when {
            track.failed || track.status == "FAILED" -> R.string.transition_info_analysis_failed
            track.status == "ANALYZING" -> R.string.transition_info_analysis_analyzing
            track.status == "READY" -> R.string.transition_info_analysis_ready
            track.status == "PENDING" -> R.string.transition_info_analysis_pending
            else -> R.string.transition_info_analysis_unavailable
        }))
        row(R.string.transition_info_analyzer, when (track.analyzer) {
            "existing" -> context.getString(R.string.automix_analyzer_existing)
            "essentia" -> context.getString(R.string.automix_analyzer_essentia)
            else -> null
        })
        row(R.string.transition_info_outgoing_bpm, track.bpm?.let(number::format))
        row(R.string.transition_info_outgoing_key, track.key)
    }

    private fun renderTransition(t: DisplayTransition) {
        val unknown = context.getString(R.string.transition_info_no_data)
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
        fun format(value: Float?) = value?.let(number::format) ?: unknown
        fun row(label: Int, value: String?) =
            text(context.getString(R.string.transition_info_row, context.getString(label), value ?: unknown))
        row(R.string.transition_info_status, context.getString(
            if (t.status == TransitionExecutionStatus.ACTIVE) R.string.transition_info_active else R.string.transition_info_completed))
        heading(context.getString(R.string.transition_info_overview))
        row(R.string.transition_info_type, context.getString(djTransitionEffectLabelResource(t.style)))
        row(R.string.transition_info_outgoing, t.outgoing)
        row(R.string.transition_info_incoming, t.incoming)
        row(R.string.transition_info_duration, context.getString(R.string.transition_info_seconds,
            number.format(t.duration / 1000.0)))
        row(R.string.transition_info_outgoing_bpm, format(t.outgoingBpm))
        row(R.string.transition_info_incoming_bpm, format(t.incomingBpm))
        row(R.string.transition_info_bpm_difference, format(
            if (t.outgoingBpm != null && t.incomingBpm != null) abs(t.outgoingBpm - t.incomingBpm) else null))
        val analyzerName = when (t.analyzer) {
            "existing" -> context.getString(R.string.automix_analyzer_existing)
            "essentia" -> context.getString(R.string.automix_analyzer_essentia)
            else -> null
        }
        row(R.string.transition_info_outgoing_analyzer, analyzerName)
        row(R.string.transition_info_incoming_analyzer, analyzerName)
        text(context.getString(R.string.transition_info_analyzer_note))
        t.styleReasons.forEach { text(reason(it)) }
        if (t.selectorDifference != null && t.selectorThreshold != null) {
            text(context.getString(R.string.transition_info_threshold,
                format(t.selectorDifference), format(t.selectorThreshold)))
        }
        if (t.selectorRising != null && t.selectorRisingThreshold != null) {
            text(context.getString(R.string.transition_info_rising_threshold,
                format(t.selectorRising), format(t.selectorRisingThreshold)))
        }
        if (t.selectorVocalThreshold != null) {
            text(context.getString(R.string.transition_info_vocal_threshold,
                format(t.outgoingVocal), format(t.incomingVocal), format(t.selectorVocalThreshold)))
        }
        heading(context.getString(R.string.transition_info_scheduled))
        text(context.getString(R.string.transition_info_unconfirmed))
        val selected = t.effects.filter { it.selection == EffectSelectionStatus.SELECTED }
        if (selected.isEmpty()) text(unknown)
        selected.forEach { effect(it, t) }
        t.beatEffect?.let(::renderBeatEffect)
        val skipped = t.effects.filter { it.selection == EffectSelectionStatus.SKIPPED }
        if (skipped.isNotEmpty()) {
            heading(context.getString(R.string.transition_info_skipped))
            skipped.forEach { effect(it, t) }
        }
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val toggle = TextView(context).apply {
            setText(R.string.transition_info_details)
            setPadding(0, 16.dpToPx(context), 0, 16.dpToPx(context))
            isClickable = true
            isFocusable = true
            setOnClickListener { details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        content.addView(toggle)
        details.visibility = View.GONE
        content.addView(details)
        fun detail(label: Int, value: String?) {
            details.addView(TextView(context).apply {
                text = context.getString(R.string.transition_info_row, context.getString(label), value ?: unknown)
                setPadding(0, 0, 0, 8.dpToPx(context))
            })
        }
        detail(R.string.transition_info_outgoing_grid, format(t.outgoingGrid))
        detail(R.string.transition_info_incoming_grid, format(t.incomingGrid))
        detail(R.string.transition_info_outgoing_key, t.outgoingKey)
        detail(R.string.transition_info_incoming_key, t.incomingKey)
        detail(R.string.transition_info_outgoing_vocal, format(t.outgoingVocal))
        detail(R.string.transition_info_incoming_vocal, format(t.incomingVocal))
        detail(R.string.transition_info_bpm_confidence, null)
        detail(R.string.transition_info_key_compatibility, null)
        detail(R.string.transition_info_energy, null)
        detail(R.string.transition_info_tempo_stability, null)
        detail(R.string.transition_info_analyzer_version, null)
        detail(R.string.transition_info_warnings, null)
    }

    private fun effect(effect: DisplayEffect, transition: DisplayTransition) {
        val name = when (effect.id) {
            "beatmatch" -> context.getString(R.string.transition_info_beatmatch)
            "crossfade" -> context.getString(R.string.dj_crossfade)
            "half_time_double_time" -> context.getString(R.string.transition_info_half_double)
            "loop_transition" -> context.getString(R.string.automix_transition_loop)
            else -> context.getString(djTransitionEffectLabelResource(effect.id))
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val toggle = TextView(context).apply {
            val label = when {
                effect.selection == EffectSelectionStatus.SKIPPED -> R.string.transition_info_skipped_label
                effect.execution == EffectExecutionStatus.PLANNED -> R.string.transition_info_planned
                effect.execution == EffectExecutionStatus.PREPARED -> R.string.transition_info_prepared
                effect.execution == EffectExecutionStatus.EXECUTING -> R.string.transition_info_executing
                effect.execution == EffectExecutionStatus.COMPLETED -> R.string.transition_info_executed
                effect.execution == EffectExecutionStatus.FAILED -> R.string.transition_info_execution_failed
                else -> R.string.transition_info_planned
            }
            text = context.getString(R.string.transition_info_effect, name, context.getString(label))
            contentDescription = context.getString(R.string.transition_info_effect_details, name)
            isClickable = true
            isFocusable = true
            setPadding(0, 8.dpToPx(context), 0, 8.dpToPx(context))
            setOnClickListener { details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        content.addView(toggle)
        effect.reasons.firstOrNull()?.let { text(reason(it)) }
        content.addView(details)
        val unknown = context.getString(R.string.transition_info_no_data)
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
        fun format(value: Float?) = value?.let(number::format) ?: unknown
        fun detail(value: String) {
            details.addView(TextView(context).apply {
                text = value
                setPadding(0, 0, 0, 8.dpToPx(context))
            })
        }
        effect.reasons.drop(1).forEach { detail(reason(it)) }
        effect.processedFrames?.let {
            detail(context.getString(R.string.transition_info_execution_frames, it,
                effect.sampleRate?.toString() ?: context.getString(R.string.transition_info_no_data)))
        }
        effect.executionDetail?.let(::detail)
        effect.tempoInterpretation?.let {
            detail(context.getString(R.string.transition_info_tempo_interpretation, it))
        }
        effect.tempoRatio?.let {
            detail(context.getString(R.string.transition_info_tempo_ratio, format(it)))
        }
        effect.effectiveIncomingBpm?.let {
            detail(context.getString(R.string.transition_info_effective_incoming_bpm, format(it)))
        }
        effect.pitchPreserved?.let {
            detail(context.getString(R.string.transition_info_pitch_preserved, it.toString()))
        }
        if (effect.loopStartMs != null && effect.loopEndMs != null) {
            detail(context.getString(R.string.transition_info_loop_region,
                effect.loopStartMs, effect.loopEndMs))
        }
        effect.timingLimitation?.let {
            detail(context.getString(R.string.transition_info_timing_limitation, it))
        }
        fun row(label: Int, value: Float?) = detail(context.getString(R.string.transition_info_row,
            context.getString(label), format(value)))
        if (effect.id == "beatmatch" || effect.id == "half_time_double_time") {
            row(R.string.transition_info_outgoing_bpm, effect.beatOut)
            row(R.string.transition_info_incoming_bpm, effect.beatIn)
            row(R.string.transition_info_outgoing_grid, effect.gridOut)
            row(R.string.transition_info_incoming_grid, effect.gridIn)
            row(R.string.transition_info_min_grid, effect.minimumGrid)
            val deviation = if (effect.beatOut != null && effect.beatIn != null && effect.beatIn > 0f)
                abs(effect.beatOut / effect.beatIn - 1f) * 100f else null
            row(R.string.transition_info_tempo_deviation, deviation)
            row(R.string.transition_info_max_tempo_deviation, effect.maximumTempoDeviation?.times(100f))
        } else {
            row(R.string.transition_info_outgoing_bpm, transition.outgoingBpm)
            row(R.string.transition_info_incoming_bpm, transition.incomingBpm)
            if (effect.reasons.any { it.name.startsWith("BPM_") } &&
                transition.selectorDifference != null && transition.selectorThreshold != null
            ) {
                detail(context.getString(R.string.transition_info_threshold,
                    format(transition.selectorDifference), format(transition.selectorThreshold)))
            }
            if (effect.reasons.contains(EffectDecisionReason.BPM_RISING) ||
                effect.reasons.contains(EffectDecisionReason.BPM_NOT_RISING_FAST)
            ) {
                detail(context.getString(R.string.transition_info_rising_threshold,
                    format(transition.selectorRising), format(transition.selectorRisingThreshold)))
            }
            if (effect.reasons.contains(EffectDecisionReason.VOCAL_ENERGY_HIGH) ||
                effect.reasons.contains(EffectDecisionReason.VOCAL_THRESHOLD_NOT_MET_OR_UNAVAILABLE)
            ) {
                detail(context.getString(R.string.transition_info_vocal_threshold,
                    format(transition.outgoingVocal), format(transition.incomingVocal),
                    format(transition.selectorVocalThreshold)))
            }
        }
    }

    private fun reason(code: EffectDecisionReason): String = context.getString(when (code) {
        EffectDecisionReason.USER_PRESET -> R.string.transition_reason_user_preset
        EffectDecisionReason.USER_ADDITIONAL_EFFECT -> R.string.transition_reason_user_additional
        EffectDecisionReason.CROSSFADE_BASE_TRANSITION -> R.string.transition_reason_crossfade
        EffectDecisionReason.BPM_UNAVAILABLE -> R.string.transition_reason_bpm_unavailable
        EffectDecisionReason.BPM_DIFFERENCE_TOO_LARGE -> R.string.transition_reason_bpm_large
        EffectDecisionReason.BPM_COMPATIBLE -> R.string.transition_reason_bpm_close
        EffectDecisionReason.BPM_MODERATE_DIFFERENCE -> R.string.transition_reason_bpm_moderate
        EffectDecisionReason.BPM_DIFFERENCE_WITHIN_RANGE -> R.string.transition_reason_bpm_echo
        EffectDecisionReason.BPM_RISING -> R.string.transition_reason_bpm_rising
        EffectDecisionReason.BPM_NOT_RISING_FAST -> R.string.transition_reason_bpm_not_rising
        EffectDecisionReason.BEAT_ALIGNMENT_UNAVAILABLE -> R.string.transition_reason_alignment_unavailable
        EffectDecisionReason.VOCAL_ENERGY_HIGH -> R.string.transition_reason_vocal_high
        EffectDecisionReason.VOCAL_THRESHOLD_NOT_MET_OR_UNAVAILABLE -> R.string.transition_reason_vocal_not_high
        EffectDecisionReason.FILTER_SLOT_CONFLICT -> R.string.transition_reason_filter_conflict
        EffectDecisionReason.BEAT_GRID_COMPATIBLE -> R.string.transition_reason_grid_compatible
        EffectDecisionReason.BEAT_GRID_UNAVAILABLE -> R.string.transition_reason_grid_unavailable
        EffectDecisionReason.BEAT_GRID_LOW_CONFIDENCE -> R.string.transition_reason_grid_low
        EffectDecisionReason.INSUFFICIENT_ANALYSIS_DATA -> R.string.transition_reason_insufficient
        EffectDecisionReason.BPM_HALF_TIME_SELECTED -> R.string.transition_reason_half_time
        EffectDecisionReason.BPM_DOUBLE_TIME_SELECTED -> R.string.transition_reason_double_time
        EffectDecisionReason.BPM_CANDIDATE_OUT_OF_RANGE -> R.string.transition_reason_speed_limit
        EffectDecisionReason.LOOP_GRID_UNAVAILABLE -> R.string.transition_reason_loop_grid
        EffectDecisionReason.LOOP_FALLBACK_CROSSFADE -> R.string.transition_reason_loop_fallback
        EffectDecisionReason.LOUDNESS_MATCH_APPLIED -> R.string.transition_reason_loudness_applied
        EffectDecisionReason.LOUDNESS_DATA_UNAVAILABLE -> R.string.transition_reason_loudness_unavailable
        EffectDecisionReason.LOUDNESS_WITHIN_DEADBAND -> R.string.transition_reason_loudness_deadband
        EffectDecisionReason.LOUDNESS_SOURCES_NOT_COMPARABLE -> R.string.transition_reason_loudness_not_comparable
        EffectDecisionReason.LOOP_TRANSITION_READY -> R.string.transition_reason_loop_ready
        EffectDecisionReason.BEAT_JUMP_REJECTED -> R.string.transition_reason_jump_rejected
        EffectDecisionReason.BEAT_LOOP_REJECTED -> R.string.transition_reason_loop_rejected
        EffectDecisionReason.ENERGY_MATCH_APPLIED -> R.string.transition_reason_energy_applied
        EffectDecisionReason.ENERGY_WITHIN_THRESHOLD -> R.string.transition_reason_energy_threshold
        EffectDecisionReason.ENERGY_DATA_UNAVAILABLE -> R.string.transition_reason_energy_unavailable
    })

    private fun renderBeatEffect(effect: DisplayBeatEffect) {
        val title = when (effect.id) {
            "beat_jump" -> R.string.transition_info_beat_jump
            "beat_loop" -> R.string.transition_info_beat_loop
            "half_time_double_time" -> R.string.transition_info_half_double
            "loop_transition" -> R.string.automix_transition_loop
            else -> R.string.transition_info_scheduled
        }
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
        heading(context.getString(R.string.transition_info_runtime_effect, context.getString(title)))
        text(context.getString(R.string.transition_info_availability, effect.available.toString()))
        text(context.getString(R.string.transition_info_evaluated, effect.evaluated.toString()))
        text(context.getString(R.string.transition_info_selected, effect.selected.toString()))
        text(context.getString(R.string.transition_info_effect_state, effect.state.name))
        effect.reason?.let(::text)
        effect.gridConfidence?.let {
            text(context.getString(R.string.transition_info_grid_confidence, number.format(it)))
        }
        effect.beatPositionMs?.let {
            text(context.getString(R.string.transition_info_beat_position, it))
        }
        effect.beatIndex?.let {
            text(context.getString(R.string.transition_info_beat_index, it))
        }
        if (effect.loopStartMs != null && effect.loopEndMs != null) {
            text(context.getString(R.string.transition_info_loop_region,
                effect.loopStartMs, effect.loopEndMs))
        }
        effect.tempoInterpretation?.let {
            text(context.getString(R.string.transition_info_tempo_interpretation, it))
        }
        effect.tempoRatio?.let {
            text(context.getString(R.string.transition_info_tempo_ratio, number.format(it)))
        }
        effect.timingLimitation?.let {
            text(context.getString(R.string.transition_info_timing_limitation, it))
        }
    }

    private fun heading(value: String) {
        content.addView(TextView(context).apply {
            text = value
            textSize = 20f
            setPadding(0, 16.dpToPx(context), 0, 8.dpToPx(context))
        })
    }

    private fun text(value: String) {
        content.addView(TextView(context).apply {
            text = value
            setPadding(0, 0, 0, 8.dpToPx(context))
        })
    }
}
