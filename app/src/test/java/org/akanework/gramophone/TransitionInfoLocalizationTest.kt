/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.akanework.gramophone

import android.app.Application
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [25])
class TransitionInfoLocalizationTest {
    @Test
    fun unsupportedLocalesFallBackToEnglishMissingDataAndReasons() {
        val context = RuntimeEnvironment.getApplication()
        fun resources(locale: Locale) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(locale) }
        ).resources
        val english = resources(Locale.ENGLISH)
        val thai = resources(Locale.forLanguageTag("th"))
        assertEquals("No data", english.getString(R.string.transition_info_no_data))
        assertEquals("No data", thai.getString(R.string.transition_info_no_data))
        assertEquals("Beatmatching was skipped because a beat grid was unavailable.",
            english.getString(R.string.transition_reason_grid_unavailable))
        assertEquals("Beatmatching was skipped because a beat grid was unavailable.",
            thai.getString(R.string.transition_reason_grid_unavailable))
    }
}
