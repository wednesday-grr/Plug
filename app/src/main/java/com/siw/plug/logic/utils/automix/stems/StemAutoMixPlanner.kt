package com.siw.plug.logic.utils.automix.stems

/** Evidence about one track that the planner may use. Null means "not analysed"; the planner never assumes a value. */
data class StemTrackEvidence(
    val stemsReady: Boolean,
    val integratedLufs: Double?,
    val bpm: Double?,
    val beatGridConfidence: Float?,
    val keyCamelot: String?,
)

data class StemPlanInput(
    val outgoing: StemTrackEvidence,
    val incoming: StemTrackEvidence,
    val transitionMs: Long,
    /** Incoming minus outgoing opening/ending energy in dB, null when not measured reliably. */
    val energyDeltaDb: Double?,
    val deviceEligible: Boolean,
    val enabled: Boolean,
)

enum class StemPlanReason {
    DISABLED, DEVICE_INELIGIBLE, STEMS_MISSING, TRANSITION_TOO_SHORT, LOUDNESS_UNKNOWN, LOUDNESS_MISMATCH,
    BEAT_EVIDENCE_MISSING, TEMPO_MISMATCH, KEY_INCOMPATIBLE, SELECTED,
}

data class StemPlan(val effect: StemEffect?, val reason: StemPlanReason, val detail: String)

/**
 * Chooses a stem transition from independent evidence (stem availability, BS.1770 loudness, beat-grid confidence plus
 * tempo, key relation, energy trend). Anything missing or weak returns a null effect so the caller falls back to the
 * ordinary two-deck crossfade.
 */
object StemAutoMixPlanner {
    const val MIN_TRANSITION_MS = 4_000L
    const val MAX_LUFS_DELTA = 6.0
    const val MIN_BEAT_CONFIDENCE = 0.5f
    /** Stem playback runs at 1.0x, so incoming tempo cannot be pitch-shifted to match: demand near-identical tempo. */
    const val MAX_TEMPO_RATIO_ERROR = 0.02
    const val ENERGY_RISE_DB = 3.0

    fun plan(input: StemPlanInput): StemPlan {
        if (!input.enabled) return StemPlan(null, StemPlanReason.DISABLED, "Stem AutoMix is off")
        if (!input.deviceEligible) return StemPlan(null, StemPlanReason.DEVICE_INELIGIBLE, "Device not eligible")
        val a = input.outgoing
        val b = input.incoming
        if (!a.stemsReady || !b.stemsReady) return StemPlan(null, StemPlanReason.STEMS_MISSING, "Stems not cached for both tracks")
        if (input.transitionMs < MIN_TRANSITION_MS) return StemPlan(null, StemPlanReason.TRANSITION_TOO_SHORT, "Transition under ${MIN_TRANSITION_MS}ms")
        val la = a.integratedLufs
        val lb = b.integratedLufs
        if (la == null || lb == null) return StemPlan(null, StemPlanReason.LOUDNESS_UNKNOWN, "BS.1770 loudness missing")
        if (kotlin.math.abs(la - lb) > MAX_LUFS_DELTA) return StemPlan(null, StemPlanReason.LOUDNESS_MISMATCH, "Loudness differs by more than ${MAX_LUFS_DELTA} LU")
        val ba = a.bpm
        val bb = b.bpm
        val ca = a.beatGridConfidence
        val cb = b.beatGridConfidence
        if (ba == null || bb == null || ca == null || cb == null || ca < MIN_BEAT_CONFIDENCE || cb < MIN_BEAT_CONFIDENCE) {
            return StemPlan(null, StemPlanReason.BEAT_EVIDENCE_MISSING, "Beat grid unavailable or low confidence")
        }
        if (kotlin.math.abs(ba / bb - 1.0) > MAX_TEMPO_RATIO_ERROR) return StemPlan(null, StemPlanReason.TEMPO_MISMATCH, "Tempos differ; stem player cannot time-stretch")
        val relation = CamelotRelation.of(a.keyCamelot, b.keyCamelot)
        if (relation == CamelotRelation.CLASH) return StemPlan(null, StemPlanReason.KEY_INCOMPATIBLE, "Keys clash")
        val rise = input.energyDeltaDb
        val effect = when {
            rise != null && rise >= ENERGY_RISE_DB && relation != CamelotRelation.UNKNOWN -> StemEffect.INSTRUMENTAL_DROP
            relation == CamelotRelation.SAME || relation == CamelotRelation.ADJACENT -> StemEffect.INSTRUMENTAL_FADE
            rise != null && rise < 0 -> StemEffect.VOCAL_TO_INSTRUMENTAL
            else -> StemEffect.INSTRUMENTAL_TO_VOCAL
        }
        return StemPlan(effect, StemPlanReason.SELECTED, "Selected from loudness, beat grid, key and energy evidence")
    }
}

enum class CamelotRelation {
    SAME, ADJACENT, CLASH, UNKNOWN;

    companion object {
        private val rx = Regex("^(\\d{1,2})([AB])$")

        fun of(a: String?, b: String?): CamelotRelation {
            val ma = a?.let { rx.matchEntire(it.trim().uppercase()) } ?: return UNKNOWN
            val mb = b?.let { rx.matchEntire(it.trim().uppercase()) } ?: return UNKNOWN
            val na = ma.groupValues[1].toInt()
            val nb = mb.groupValues[1].toInt()
            if (na !in 1..12 || nb !in 1..12) return UNKNOWN
            val d = minOf((na - nb + 12) % 12, (nb - na + 12) % 12)
            val sameMode = ma.groupValues[2] == mb.groupValues[2]
            return when {
                d == 0 -> SAME
                d == 1 && sameMode -> ADJACENT
                else -> CLASH
            }
        }
    }
}
