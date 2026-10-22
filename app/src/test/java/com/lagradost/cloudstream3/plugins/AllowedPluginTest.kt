package com.lagradost.cloudstream3.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensure only the exact allowed plugin identity passes the gate. */
class AllowedPluginTest {

    @Test
    fun `accepts exact allowed plugin from canonical repository`() {
        assertTrue(
            isAllowedPlugin(
                internalName = "NexoStreamProvider",
                repositoryUrl = ALLOWED_REPOSITORY_URL
            )
        )
    }

    @Test
    fun `rejects renamed plugin even from canonical repository`() {
        assertFalse(
            isAllowedPlugin(
                internalName = "NexoStreamProviderX",
                repositoryUrl = ALLOWED_REPOSITORY_URL
            )
        )
        assertFalse(
            isAllowedPlugin(
                internalName = "FakeProvider",
                repositoryUrl = ALLOWED_REPOSITORY_URL
            )
        )
    }

    @Test
    fun `rejects local plugin with null repository`() {
        assertFalse(
            isAllowedPlugin(
                internalName = "NexoStreamProvider",
                repositoryUrl = null
            )
        )
    }

    @Test
    fun `rejects same internalName from another repository`() {
        assertFalse(
            isAllowedPlugin(
                internalName = "NexoStreamProvider",
                repositoryUrl = "https://example.com/repo.json"
            )
        )
    }
}
