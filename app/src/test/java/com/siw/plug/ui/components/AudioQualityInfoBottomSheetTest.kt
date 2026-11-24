/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.ui.components

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.TextView
import com.siw.plug.R
import com.siw.plug.logic.utils.automix.EffectDecisionReason
import com.siw.plug.logic.utils.automix.EffectExecutionStatus
import com.siw.plug.logic.utils.automix.EffectSelectionStatus
import com.siw.plug.logic.utils.automix.TransitionExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class AudioQualityInfoBottomSheetTest {
    @Test
    fun rendersLoadingUnavailableAndErrorSeparately() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Gramophone)
        val sheet = AudioQualityInfoBottomSheet(context) { null }
        fun message(): String = (sheet.content.getChildAt(1) as TextView).text.toString()

        sheet.render(AudioQualityInfoUiState.Loading)
        assertEquals(context.getString(R.string.transition_info_loading), message())
        sheet.render(AudioQualityInfoUiState.Unavailable)
        assertEquals(context.getString(R.string.transition_info_unavailable), message())
        sheet.render(AudioQualityInfoUiState.Error)
        assertEquals(context.getString(R.string.transition_info_error), message())
        sheet.dismiss()
    }

    @Test
    fun rendersScheduledAndSkippedWithoutClaimingExecution() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Gramophone)
        val sheet = AudioQualityInfoBottomSheet(context) { null }
        val effect = DisplayEffect("echo_out", EffectSelectionStatus.SELECTED,
            listOf(EffectDecisionReason.USER_ADDITIONAL_EFFECT), null, null, null, null, null, null,
            EffectExecutionStatus.PLANNED, null, null, null)
        val skipped = DisplayEffect("filter_sweep", EffectSelectionStatus.SKIPPED,
            listOf(EffectDecisionReason.FILTER_SLOT_CONFLICT), null, null, null, null, null, null,
            null, null, null, null)
        sheet.render(AudioQualityInfoUiState.Available(DisplayTransition(
            1, TransitionExecutionStatus.COMPLETED, null, null, 5000, "fade",
            listOf(EffectDecisionReason.BPM_UNAVAILABLE), null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, listOf(effect, skipped)
        )))
        val rendered = (0 until sheet.content.childCount).mapNotNull {
            (sheet.content.getChildAt(it) as? TextView)?.text?.toString()
        }
        assertTrue(rendered.any { it.contains(context.getString(R.string.transition_info_planned)) })
        assertTrue(rendered.any { it.contains(context.getString(R.string.transition_info_skipped_label)) })
        assertTrue(rendered.any { it.contains(context.getString(R.string.transition_info_no_data)) })
        assertFalse(rendered.any { it.contains("Echo — Completed") })
        sheet.dismiss()
    }

    @Test
    fun rendersTrackAnalysisWhenThereIsNoTransition() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Gramophone)
        val sheet = AudioQualityInfoBottomSheet(context) { null }
        sheet.render(AudioQualityInfoUiState.TrackAnalysis(
            DisplayTrackAnalysis("Current song", "existing", "READY", 128f, "8B", false),
            DisplayTrackAnalysis("Next song", "essentia", "ANALYZING", null, null, false),
        ))
        val rendered = (0 until sheet.content.childCount).mapNotNull {
            (sheet.content.getChildAt(it) as? TextView)?.text?.toString()
        }
        assertTrue(rendered.any { it.contains("Current song") })
        assertTrue(rendered.any { it.contains("Next song") })
        assertTrue(rendered.any { it.contains("128") })
        assertTrue(rendered.any { it.contains("8B") })
        assertTrue(rendered.any { it.contains(context.getString(R.string.automix_analyzer_existing)) })
        assertTrue(rendered.any { it.contains(context.getString(R.string.automix_analyzer_essentia)) })
        assertTrue(rendered.any { it.contains(context.getString(R.string.transition_info_no_transition)) })
        assertFalse(rendered.any { it.contains(context.getString(R.string.transition_info_unavailable)) })
        sheet.dismiss()
    }
}
