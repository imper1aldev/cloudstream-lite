package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.lite.*
import org.junit.Assert.*
import org.junit.Test

class LitePlaybackDecisionTest {
    @Test fun waitsForLatAndFallsBackOnlyAtCompletion() {
        val cast = LiteCandidate("cast", parseLiteLinkName("[A][CAST] Voe"), 1080)
        val lat = LiteCandidate("lat", parseLiteLinkName("[A][LAT] Voe"), 720)
        assertNull(chooseLiteStart(listOf(cast), false, false))
        assertEquals("lat", chooseLiteStart(listOf(cast, lat), false, false))
        assertEquals("cast", chooseLiteStart(listOf(cast), true, false))
        assertNull(chooseLiteStart(listOf(lat), false, true))
        assertNull(chooseLiteStart(listOf(lat), true, true))
        assertNull(chooseLiteStart(emptyList(), false, false))
        assertNull(chooseLiteStart(emptyList(), true, false))
        val sub = LiteCandidate("sub", parseLiteLinkName("[A][SUB] Voe"), 720)
        val unknown = LiteCandidate("unknown", parseLiteLinkName("[A][UNK] Voe"), 1080)
        assertNull(chooseLiteStart(listOf(sub, unknown), false, false))
        assertEquals("unknown", chooseLiteStart(listOf(sub, unknown), true, false))
    }

    @Test fun failedCandidatesAreNeverAutomaticallySelected() {
        val failed = LiteCandidate("failed", parseLiteLinkName("[A][LAT] Voe"), 1080, failed = true)
        val cast = LiteCandidate("cast", parseLiteLinkName("[A][CAST] Voe"), 720)
        repeat(2) {
            assertNull(chooseLiteStart(listOf(failed), false, false))
            assertNull(chooseLiteStart(listOf(failed), true, false))
            assertNull(chooseLiteStart(listOf(failed, cast), false, false))
            assertEquals("cast", chooseLiteStart(listOf(failed, cast), true, false))
        }
    }

    @Test fun animeAv1SubAndDubWaitForCompletionBecauseNeitherIsLatAudio() {
        val sub = LiteCandidate("sub", parseLiteLinkName("[AnimeAv1][SUB] HLS"), 1080)
        val dub = LiteCandidate("dub", parseLiteLinkName("[AnimeAv1][DUB] HLS"), 720)
        listOf(sub, dub).forEach {
            assertNull(chooseLiteStart(listOf(it), false, false))
            assertEquals(it.id, chooseLiteStart(listOf(it), true, false))
        }
        assertEquals("dub", chooseLiteStart(listOf(sub, dub), true, false))
    }

    @Test fun automaticStartUsesTheSameRankingAsTheSelector() {
        val high = LiteCandidate("high", parseLiteLinkName("[A][LAT] Voe"), 1080)
        val degraded = high.copy(id = "degraded", quality = 2160, degraded = true)
        val low = high.copy(id = "low", quality = 720)
        assertEquals("high", chooseLiteStart(listOf(low, degraded, high), false, false))
    }

    @Test fun onlyNexoStreamPhoneAndTabletLayoutsUseLitePlayback() {
        assertTrue(usesLitePlayback(true, "NexoStream"))
        assertFalse(usesLitePlayback(false, "NexoStream"))
        assertFalse(usesLitePlayback(true, "OtherProvider"))
        assertFalse(usesLitePlayback(true, null))
        assertFalse(usesLitePlayback(true, ""))
        assertFalse(usesLitePlayback(true, "content://local/video"))
        assertFalse(usesLitePlayback(true, "file:///local/video.mp4"))
    }
}
