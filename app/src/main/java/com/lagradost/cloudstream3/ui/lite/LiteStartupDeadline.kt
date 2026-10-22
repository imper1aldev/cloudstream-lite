package com.lagradost.cloudstream3.ui.lite

/** Per automatic attempt. Pauses and background time never consume the startup budget. */
internal class LiteStartupDeadline(nowMs: Long) {
    private var previousMs = nowMs
    private var previouslyActive = true
    private var activeMs = 0L
    private var idleMs = 0L
    private var bufferedHighWater = 0L
    private var expired = false
    var started = false
        private set

    fun update(nowMs: Long, active: Boolean, playing: Boolean, bufferedMs: Long): Boolean {
        val delta = (nowMs - previousMs).coerceAtLeast(0)
        previousMs = nowMs
        if (playing) started = true
        if (started) return false
        if (active && previouslyActive) {
            activeMs += delta
            idleMs += delta
        }
        previouslyActive = active
        if (bufferedMs > bufferedHighWater) {
            bufferedHighWater = bufferedMs
            idleMs = 0
        }
        if (active && (idleMs >= 7_000 || activeMs >= 30_000)) expired = true
        return active && expired
    }
}
