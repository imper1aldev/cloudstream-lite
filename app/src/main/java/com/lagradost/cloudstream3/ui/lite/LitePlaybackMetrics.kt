package com.lagradost.cloudstream3.ui.lite

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

data class LitePlaybackSample(val startupMs: Long?, val bufferingMs: Long, val errors: Int)

/** Session-local observations; unknown performance stays unknown. Owned by the player UI thread. */
class LitePlaybackMetrics(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Sample(val host: String, val format: String, val selectedAt: Long) {
        var startupMs: Long? = null
        var played = false
        var bufferingMs = 0L
        var bufferingAt: Long? = null
        var errors = 0
        fun stopBuffering(now: Long) {
            bufferingAt?.let { bufferingMs += (now - it).coerceAtLeast(0) }
            bufferingAt = null
        }
        fun snapshot(now: Long) = LitePlaybackSample(startupMs,
            bufferingMs + (bufferingAt?.let { (now - it).coerceAtLeast(0) } ?: 0), errors)
    }
    private val samples = mutableMapOf<String, Sample>()
    private var selected: Sample? = null
    private var player: ExoPlayer? = null
    private var listener: AnalyticsListener? = null
    private var generation = 0L
    private var closed = false

    fun select(id: String, host: String, format: String, nowMs: Long) {
        if (closed) return
        detach()
        selected = Sample(host, format, nowMs).also { samples[id] = it }
    }

    fun attach(player: Any?) {
        if (closed || this.player === player) return
        detach()
        val exoPlayer = player as? ExoPlayer ?: return
        val sample = selected ?: return
        val token = generation
        val observer = object : AnalyticsListener {
            private fun current() = !closed && token == generation && selected === sample
            override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                if (current() && isPlaying) sample.played = true
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (current() && sample.startupMs == null) {
                    sample.startupMs = (nowMs() - sample.selectedAt).coerceAtLeast(0)
                }
            }
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (!current()) return
                if (state == Player.STATE_BUFFERING) {
                    if (sample.bufferingAt == null) sample.bufferingAt = nowMs()
                } else sample.stopBuffering(nowMs())
            }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                if (!current()) return
                sample.stopBuffering(nowMs())
                sample.errors++
            }
        }
        this.player = exoPlayer
        listener = observer
        exoPlayer.addAnalyticsListener(observer)
    }

    private fun detach() {
        generation++
        selected?.stopBuffering(nowMs())
        listener?.let { player?.removeAnalyticsListener(it) }
        player = null
        listener = null
    }

    fun snapshot(id: String): LitePlaybackSample? = samples[id]?.snapshot(nowMs())

    internal fun hasPlayed(id: String): Boolean = samples[id]?.played == true

    @Suppress("UNUSED_PARAMETER")
    fun reset(networkEpoch: Long) {
        detach()
        samples.clear()
        selected = null
    }

    fun close() {
        if (closed) return
        reset(0)
        closed = true
    }
}
