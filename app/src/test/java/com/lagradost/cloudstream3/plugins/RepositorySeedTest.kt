package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seeding of the canonical Spanish repository on first run (T1.2). */
class RepositorySeedTest {

    private val seed = RepositoryData(
        "https://avatars.githubusercontent.com/u/23511335?v=4",
        "Spanish providers repository",
        ALLOWED_REPOSITORY_URL
    )

    @Test
    fun `empty existing list yields the canonical seed`() {
        val appended = RepositoryManager.seedRepositories(emptyList())
        assertEquals(listOf(seed), appended)
    }

    @Test
    fun `already seeded list appends nothing`() {
        val existing = listOf(seed)
        val appended = RepositoryManager.seedRepositories(existing)
        assertTrue(appended.isEmpty())
        // Combined view shows the repo exactly once.
        assertEquals(1, (existing + appended).count { it.url == ALLOWED_REPOSITORY_URL })
    }

    @Test
    fun `unrelated repos are preserved and seed is appended`() {
        val other = RepositoryData("Other repo", "https://example.com/other.json")
        val appended = RepositoryManager.seedRepositories(listOf(other))
        assertEquals(listOf(seed), appended)
        val combined = listOf(other) + appended
        assertEquals(2, combined.size)
        assertEquals(1, combined.count { it.url == ALLOWED_REPOSITORY_URL })
    }
}
