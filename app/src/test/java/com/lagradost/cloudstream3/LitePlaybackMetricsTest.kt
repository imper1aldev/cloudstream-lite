package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.lite.LitePlaybackMetrics
import com.lagradost.cloudstream3.ui.lite.LitePlaybackSample
import org.junit.Assert.*
import org.junit.Test

class LitePlaybackMetricsTest {
    @Test fun selectedCandidateStartsWithoutInventedMetrics() {
        val metrics = LitePlaybackMetrics { 150 }
        metrics.select("a", "one.test", "MP4", 100)
        assertEquals(LitePlaybackSample(null, 0, 0), metrics.snapshot("a"))
        assertNull(metrics.snapshot("other"))
    }
    @Test fun resetDropsSessionSamplesEvenOnSameNetwork() {
        val metrics = LitePlaybackMetrics { 150 }
        metrics.select("a", "one.test", "MP4", 100)
        metrics.reset(1)
        assertNull(metrics.snapshot("a"))
        metrics.select("b", "two.test", "HLS", 150)
        assertEquals(LitePlaybackSample(null, 0, 0), metrics.snapshot("b"))
        metrics.reset(1)
        assertNull(metrics.snapshot("b"))
    }
    @Test fun closeIsIdempotentAndPreventsNewSessions() {
        val metrics = LitePlaybackMetrics { 150 }
        metrics.select("a", "one.test", "MP4", 100)
        metrics.close()
        metrics.close()
        metrics.select("b", "two.test", "HLS", 150)
        assertNull(metrics.snapshot("a"))
        assertNull(metrics.snapshot("b"))
    }
}
