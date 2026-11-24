package com.siw.plug.logic.utils.automix.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class StemEffect(val id: String) {
    INSTRUMENTAL_DROP("instrumental_drop"),
    INSTRUMENTAL_FADE("instrumental_fade"),
    VOCAL_TO_INSTRUMENTAL("vocal_to_instrumental"),
    INSTRUMENTAL_TO_VOCAL("instrumental_to_vocal"),
}

/** Linear gains of the four sources in a stem transition. */
data class StemGains(val aVocal: Float, val aInstrumental: Float, val bVocal: Float, val bInstrumental: Float) {
    val activeSources: Int
        get() = listOf(aVocal, aInstrumental, bVocal, bInstrumental).count { it > ACTIVE_THRESHOLD }

    companion object { const val ACTIVE_THRESHOLD = 1e-3f }
}

/** Per-stem automation curves for the four stem transitions. Progress runs 0 (outgoing track only) to 1 (incoming track only). */
object StemTransitionMath {
    /** Minimum time over which any gain may fall to zero; shorter edges would click. */
    const val MIN_EDGE_MS = 20f

    private fun ramp(p: Float, from: Float, to: Float): Float = ((p - from) / (to - from)).coerceIn(0f, 1f)
    private fun smooth(x: Float) = x * x * (3f - 2f * x)
    private fun fadeOut(p: Float, from: Float, to: Float) = cos(ramp(p, from, to) * PI.toFloat() / 2f).coerceIn(0f, 1f)
    private fun fadeIn(p: Float, from: Float, to: Float) = sin(ramp(p, from, to) * PI.toFloat() / 2f).coerceIn(0f, 1f)
    private fun hold(p: Float, from: Float, to: Float) = smooth(ramp(p, from, to))

    /** Where in the transition the instrumental drop lands. */
    const val DROP_POINT = 0.75f

    fun gains(effect: StemEffect, progress: Float): StemGains {
        val p = progress.coerceIn(0f, 1f)
        return when (effect) {
            // Outgoing vocal leaves first, both instrumentals cross over the whole transition, incoming vocal arrives last.
            StemEffect.INSTRUMENTAL_FADE -> StemGains(
                aVocal = fadeOut(p, 0f, 0.4f), aInstrumental = fadeOut(p, 0f, 1f),
                bVocal = fadeIn(p, 0.5f, 1f), bInstrumental = fadeIn(p, 0f, 1f),
            )
            // Outgoing instrumental is held, then cut as the incoming instrumental lands on the drop point.
            StemEffect.INSTRUMENTAL_DROP -> StemGains(
                aVocal = fadeOut(p, 0f, 0.25f), aInstrumental = 1f - hold(p, DROP_POINT - 0.02f, DROP_POINT + 0.02f),
                bVocal = fadeIn(p, DROP_POINT + 0.1f, 1f), bInstrumental = hold(p, DROP_POINT - 0.02f, DROP_POINT + 0.02f),
            )
            // Outgoing vocal exits early; only instrumentals overlap until the incoming vocal enters at the end.
            StemEffect.VOCAL_TO_INSTRUMENTAL -> StemGains(
                aVocal = fadeOut(p, 0f, 0.3f), aInstrumental = fadeOut(p, 0.3f, 1f),
                bVocal = fadeIn(p, 0.85f, 1f), bInstrumental = fadeIn(p, 0.3f, 1f),
            )
            // Outgoing instrumental exits first leaving its vocal exposed; incoming instrumental then arrives under it
            // before the outgoing vocal leaves and the incoming vocal enters.
            StemEffect.INSTRUMENTAL_TO_VOCAL -> StemGains(
                aVocal = fadeOut(p, 0.55f, 0.7f), aInstrumental = fadeOut(p, 0f, 0.35f),
                bVocal = fadeIn(p, 0.75f, 1f), bInstrumental = fadeIn(p, 0.5f, 0.8f),
            )
        }
    }

    /** Largest possible vocal overlap (A vocal * B vocal) over the transition, sampled; used to keep vocals from clashing. */
    fun peakVocalOverlap(effect: StemEffect, steps: Int = 200): Float =
        (0..steps).maxOf { gains(effect, it / steps.toFloat()).let { g -> minOf(g.aVocal, g.bVocal) } }
}

/** Transparent safety limiter for the summed mix: identity below the knee, smooth tanh-style saturation above it. */
object StemSoftLimiter {
    private const val KNEE = 0.9f

    fun process(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (1f - KNEE)
        val limited = KNEE + (1f - KNEE) * kotlin.math.tanh(over)
        return if (x < 0) -limited else limited
    }
}

/** Deck gains while a stem player owns the middle of a transition: decks only sound in the edge ramps. */
object StemHandOver {
    data class DeckGains(val outgoing: Float, val incoming: Float)

    /** Null until the player has produced audible output, so callers keep the normal deck envelope. */
    fun deckGains(playedFrames: Long, totalFrames: Long, edgeFrames: Long): DeckGains? {
        if (totalFrames <= 0L || playedFrames <= 0L || edgeFrames <= 0L) return null
        val played = playedFrames.coerceAtMost(totalFrames)
        val outgoing = 1f - (played.toFloat() / edgeFrames).coerceIn(0f, 1f)
        val incoming = 1f - ((totalFrames - played).toFloat() / edgeFrames).coerceIn(0f, 1f)
        return DeckGains(outgoing, incoming)
    }
}
