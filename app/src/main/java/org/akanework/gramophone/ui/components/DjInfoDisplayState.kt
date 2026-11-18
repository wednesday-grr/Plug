package org.akanework.gramophone.ui.components

internal class DjInfoDisplayState {
    private var elapsedMs = 0L
    private var lastTimeMs: Long? = null

    fun reset() {
        elapsedMs = 0L
        lastTimeMs = null
    }

    fun showDj(nowMs: Long, playing: Boolean, durationMs: Long, positionMs: Long): Boolean {
        val previous = lastTimeMs
        if (previous != null) elapsedMs += (nowMs - previous).coerceAtLeast(0)
        lastTimeMs = if (playing) nowMs else null
        val finalSection = durationMs > 15_000L &&
            (durationMs - positionMs.coerceAtLeast(0)) <= 15_000L
        return finalSection || (elapsedMs / 10_000L) % 2L == 1L
    }
}
