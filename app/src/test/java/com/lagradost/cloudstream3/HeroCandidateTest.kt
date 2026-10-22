package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.home.selectHeroCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroCandidateTest {

    private class FakeSearchResponse(
        override val name: String,
        override val url: String,
    ) : SearchResponse {
        override val apiName: String = "test"
        override var type: TvType? = TvType.Movie
        override var posterUrl: String? = null
        override var posterHeaders: Map<String, String>? = null
        override var id: Int? = null
        override var quality: SearchQuality? = null
        override var score: Score? = null
    }

    private fun item(url: String) = FakeSearchResponse(name = url, url = url)

    @Test
    fun `no rows yields null`() {
        assertNull(selectHeroCandidate(emptyList(), emptySet()))
    }

    @Test
    fun `first item of first non-empty row is the candidate`() {
        val rows = listOf(
            emptyList(),
            listOf(item("a"), item("b")),
            listOf(item("c")),
        )
        assertEquals("a", selectHeroCandidate(rows, emptySet())?.url)
    }

    @Test
    fun `item already in continue watching is skipped`() {
        val rows = listOf(
            listOf(item("resume1")),
            listOf(item("fresh"), item("x")),
        )
        assertEquals("fresh", selectHeroCandidate(rows, setOf("resume1"))?.url)
    }

    @Test
    fun `all items in continue watching falls back to first item of first row`() {
        val rows = listOf(
            listOf(item("resume1")),
            listOf(item("resume2")),
        )
        assertEquals("resume1", selectHeroCandidate(rows, setOf("resume1", "resume2"))?.url)
    }
}
