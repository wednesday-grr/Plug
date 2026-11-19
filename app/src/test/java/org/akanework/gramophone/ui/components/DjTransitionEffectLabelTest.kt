package org.akanework.gramophone.ui.components

import org.akanework.gramophone.R
import org.junit.Assert.assertEquals
import org.junit.Test

class DjTransitionEffectLabelTest {
    @Test
    fun mapsResolvedTransitionStylesToTheirDisplayLabels() {
        assertEquals(R.string.dj_bass_swap, djTransitionEffectLabelResource("bass_swap"))
        assertEquals(R.string.dj_filter_sweep, djTransitionEffectLabelResource("filter_sweep"))
        assertEquals(R.string.dj_echo_out, djTransitionEffectLabelResource("echo_out"))
        assertEquals(R.string.dj_vinyl_brake, djTransitionEffectLabelResource("vinyl_brake"))
        assertEquals(R.string.dj_vocal_swap, djTransitionEffectLabelResource("vocal_swap"))
        assertEquals(R.string.dj_crossfade, djTransitionEffectLabelResource("smooth"))
        assertEquals(R.string.dj_crossfade, djTransitionEffectLabelResource(null))
    }
}
