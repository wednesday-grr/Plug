package com.siw.plug.ui.components

import com.siw.plug.R
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
        assertEquals(R.string.automix_transition_eq, djTransitionEffectLabelResource("eq_transition"))
        assertEquals(R.string.automix_transition_high_pass_intro, djTransitionEffectLabelResource("high_pass_intro"))
        assertEquals(R.string.automix_transition_reverb_tail, djTransitionEffectLabelResource("reverb_tail"))
        assertEquals(R.string.automix_transition_tremolo_fade, djTransitionEffectLabelResource("tremolo_fade"))
        assertEquals(R.string.automix_transition_fade, djTransitionEffectLabelResource("fade"))
        assertEquals(R.string.automix_transition_rise, djTransitionEffectLabelResource("rise"))
        assertEquals(R.string.dj_crossfade, djTransitionEffectLabelResource("smooth"))
        assertEquals(R.string.dj_crossfade, djTransitionEffectLabelResource(null))
    }
}
