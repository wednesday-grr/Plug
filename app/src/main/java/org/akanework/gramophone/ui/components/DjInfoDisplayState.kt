package org.akanework.gramophone.ui.components

internal class DjInfoDisplayState {
    private companion object {
        const val FINAL_SECTION_MS = 15_000L
    }

    private var elapsedMs = 0L
    private var lastTimeMs: Long? = null
    private var currentMediaId: String? = null
    private var stableDurationMs = 0L
    private var finalSectionLocked = false

    val isFinalSectionLocked: Boolean
        get() = finalSectionLocked

    fun reset() {
        elapsedMs = 0L
        lastTimeMs = null
        currentMediaId = null
        stableDurationMs = 0L
        finalSectionLocked = false
    }

    fun showDj(
        nowMs: Long,
        playing: Boolean,
        durationMs: Long,
        positionMs: Long,
        mediaId: String? = null,
    ): Boolean {
        if (mediaId != null && mediaId != currentMediaId) {
            reset()
            currentMediaId = mediaId
        }
        val previous = lastTimeMs
        if (previous != null) elapsedMs += (nowMs - previous).coerceAtLeast(0)
        lastTimeMs = if (playing) nowMs else null
        if (durationMs > FINAL_SECTION_MS) stableDurationMs = durationMs
        val duration = stableDurationMs.takeIf { it > FINAL_SECTION_MS } ?: durationMs
        val remainingMs = duration - positionMs.coerceAtLeast(0)
        if (duration > FINAL_SECTION_MS && remainingMs <= FINAL_SECTION_MS) {
            finalSectionLocked = true
        }
        return finalSectionLocked || (elapsedMs / 10_000L) % 2L == 1L
    }
}
