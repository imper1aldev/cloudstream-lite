package com.lagradost.cloudstream3.plugins

/** Single source of truth for the one allowed plugin identity. */
const val ALLOWED_PLUGIN_INTERNAL_NAME = "NexoStreamProvider"

/** API name of the allowed plugin, exported for later gates. */
const val ALLOWED_PLUGIN_API_NAME = "NexoStream"

/** Canonical repository URL the allowed plugin must come from. */
const val ALLOWED_REPOSITORY_URL =
    "https://raw.githubusercontent.com/imper1aldev/cs3-build/refs/heads/builds/repo.json"

/** Exact identity check; local plugins (url == null) and mismatches are rejected. */
fun isAllowedPlugin(internalName: String?, repositoryUrl: String?): Boolean {
    return internalName == ALLOWED_PLUGIN_INTERNAL_NAME && repositoryUrl == ALLOWED_REPOSITORY_URL
}

private val installationLock = kotlinx.coroutines.sync.Mutex()

/** Prepare the fixed provider without a repository/language wizard. Retry is safe. */
suspend fun ensureAllowedProvider(activity: android.app.Activity): Boolean {
    installationLock.lock()
    try {
        if (com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(ALLOWED_PLUGIN_API_NAME) != null) return true
        // Prefer the installed copy, including on offline restarts. All startup callers share this lock.
        if (PluginManager.loadSinglePlugin(activity, ALLOWED_PLUGIN_API_NAME) &&
            com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(ALLOWED_PLUGIN_API_NAME) != null) return true
        val repository = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(
            SEED_REPOSITORY_ICON_URL, SEED_REPOSITORY_NAME, ALLOWED_REPOSITORY_URL)
        val plugin = RepositoryManager.getRepoPlugins(repository)
            ?.firstOrNull { isAllowedPlugin(it.plugin.internalName, it.repositoryData.url) }?.plugin ?: return false
        return PluginManager.downloadPlugin(activity, plugin.url, plugin.fileHash,
            plugin.internalName, ALLOWED_REPOSITORY_URL, true) &&
            com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(ALLOWED_PLUGIN_API_NAME) != null
    } finally {
        installationLock.unlock()
    }
}
