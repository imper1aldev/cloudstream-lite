package com.lagradost.cloudstream3

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.lagradost.cloudstream3.ui.lite.LitePlaybackMetrics
import com.lagradost.cloudstream3.ui.lite.LitePlaybackSample
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class LitePlaybackMetricsListenerTest {
    @Test fun actualPlaybackIsLatchedAndFirstFrameAloneIsInsufficient() {
        val metrics=LitePlaybackMetrics { 100 }
        val probe=Probe()
        metrics.select("a","one.test","M3U8",0);metrics.attach(probe.player)
        probe.frame()
        assertFalse(metrics.hasPlayed("a"))
        val observer=probe.listeners.single()
        observer.onIsPlayingChanged(probe.event,true)
        observer.onIsPlayingChanged(probe.event,false)
        assertTrue("A short play must survive later buffering",metrics.hasPlayed("a"))
        metrics.select("b","two.test","M3U8",100);metrics.attach(probe.player)
        observer.onIsPlayingChanged(probe.event,true)
        assertFalse("Old callbacks must not finish the next startup",metrics.hasPlayed("b"))
        metrics.reset(1)
        assertFalse(metrics.hasPlayed("a"));assertFalse(metrics.hasPlayed("b"))
        metrics.close()
    }
    private class Probe {
        val listeners = mutableListOf<AnalyticsListener>()
        var adds = 0
        var removes = 0
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "addAnalyticsListener" -> { adds++; listeners.add(args!![0] as AnalyticsListener); null }
                "removeAnalyticsListener" -> { removes++; listeners.remove(args!![0] as AnalyticsListener); null }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> null
            }
        } as ExoPlayer
        val event = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
        fun state(value: Int) = listeners.toList().forEach { it.onPlaybackStateChanged(event, value) }
        fun frame() = listeners.toList().forEach { it.onRenderedFirstFrame(event, Any(), 0) }
        fun error() = listeners.toList().forEach { it.onPlayerError(event, PlaybackException("fixture", null, 1000)) }
    }

    @Test fun firstFrameMeasuresFromSelectionAndOnlyOnce() {
        var time = 100L
        val metrics = LitePlaybackMetrics { time }
        val probe = Probe()
        metrics.select("a", "one.test", "M3U8", time)
        metrics.attach(probe.player)
        assertEquals(LitePlaybackSample(null, 0, 0), metrics.snapshot("a"))
        time = 145
        probe.state(Player.STATE_READY)
        assertNull(metrics.snapshot("a")!!.startupMs)
        time = 160
        probe.frame()
        time = 300
        probe.frame()
        assertEquals(60L, metrics.snapshot("a")!!.startupMs)
        metrics.close()
    }

    @Test fun bufferingIntervalsAndErrorsComeFromRealListenerCallbacks() {
        var time = 100L
        val metrics = LitePlaybackMetrics { time }
        val probe = Probe()
        metrics.select("a", "one.test", "M3U8", time)
        metrics.attach(probe.player)
        probe.state(Player.STATE_BUFFERING)
        time = 120
        probe.state(Player.STATE_BUFFERING)
        time = 150
        assertEquals(50L, metrics.snapshot("a")!!.bufferingMs)
        probe.state(Player.STATE_READY)
        time = 170
        probe.state(Player.STATE_BUFFERING)
        time = 190
        probe.error()
        time = 500
        assertEquals(LitePlaybackSample(null, 70, 1), metrics.snapshot("a"))
        metrics.close()
    }

    @Test fun replacementNullAndCloseDetachWithoutDuplicateListeners() {
        val metrics = LitePlaybackMetrics { 100 }
        val first = Probe()
        val second = Probe()
        metrics.select("a", "one.test", "MP4", 0)
        metrics.attach(first.player)
        metrics.attach(first.player)
        assertEquals(1, first.adds)
        metrics.attach(second.player)
        assertEquals(1, first.removes)
        metrics.attach(null)
        metrics.attach(null)
        assertEquals(1, second.removes)
        metrics.attach(second.player)
        metrics.close()
        metrics.close()
        metrics.attach(first.player)
        metrics.select("b", "two.test", "MP4", 100)
        assertTrue(first.listeners.isEmpty())
        assertTrue(second.listeners.isEmpty())
        assertNull(metrics.snapshot("a"))
        assertNull(metrics.snapshot("b"))
    }

    @Test fun staleSelectionAndPlayerEventsCannotContaminateNextCandidate() {
        var time = 100L
        val metrics = LitePlaybackMetrics { time }
        val first = Probe()
        metrics.select("a", "one.test", "M3U8", 0)
        metrics.attach(first.player)
        val stale = first.listeners.single()
        first.frame()
        metrics.select("b", "two.test", "MP4", time)
        val second = Probe()
        metrics.attach(second.player)
        time = 125
        stale.onPlayerError(first.event, PlaybackException("stale", null, 1000))
        stale.onRenderedFirstFrame(first.event, Any(), 0)
        second.frame()
        assertEquals(LitePlaybackSample(100, 0, 0), metrics.snapshot("a"))
        assertEquals(LitePlaybackSample(25, 0, 0), metrics.snapshot("b"))
        metrics.select("b", "different.test", "M3U8", time)
        assertEquals(LitePlaybackSample(null, 0, 0), metrics.snapshot("b"))
        metrics.close()
    }

    @Test fun networkAndEpisodeResetClearSamplesAndInvalidateQueuedCallbacks() {
        var time = 100L
        val metrics = LitePlaybackMetrics { time }
        val probe = Probe()
        metrics.select("same-id", "one.test", "MP4", 0)
        metrics.attach(probe.player)
        val stale = probe.listeners.single()
        metrics.reset(1)
        assertTrue(probe.listeners.isEmpty())
        assertNull(metrics.snapshot("same-id"))
        metrics.select("same-id", "one.test", "MP4", time)
        metrics.attach(probe.player)
        time = 140
        stale.onRenderedFirstFrame(probe.event, Any(), 0)
        assertNull(metrics.snapshot("same-id")!!.startupMs)
        probe.frame()
        assertEquals(40L, metrics.snapshot("same-id")!!.startupMs)
        metrics.reset(1) // Same network epoch still starts a fresh episode/session.
        assertNull(metrics.snapshot("same-id"))
        metrics.close()
    }
}
