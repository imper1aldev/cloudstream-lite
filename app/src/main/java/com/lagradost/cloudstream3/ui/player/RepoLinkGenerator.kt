package com.lagradost.cloudstream3.ui.player

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class Cache(
    val linkCache: MutableSet<ExtractorLink>,
    val subtitleCache: MutableSet<SubtitleData>,
    /** When it was last updated */
    @Volatile var lastCachedTimestamp: Long = unixTime,
    /** If it has fully loaded */
    var saturated: Boolean,
) {
    fun isFresh(now: Long = unixTime): Boolean = now - lastCachedTimestamp in 0..(20 * 60)
}

class RepoLinkGenerator(
    episodes: List<ResultEpisode>,
    val page: LoadResponse? = null,
) : VideoGenerator<ResultEpisode>(episodes) {
    companion object {
        const val TAG = "RepoLink"
        val cache = ConcurrentHashMap<Pair<String, Int>, Cache>()
    }

    override val hasCache = true
    override val canSkipLoading = true
    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    @Throws
    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        val current = videos.getOrNull(offset) ?: return false

        val key = current.apiName to current.id
        val currentCache = synchronized(cache) {
            // A new entry isolates in-flight callbacks from a previous extraction.
            cache[key]?.takeIf { !clearCache && it.isFresh() }
                ?: Cache(mutableSetOf(), mutableSetOf(), saturated = false).also { cache[key] = it }
        }
        val extractionJob = currentCoroutineContext()[Job]
        fun isCurrent() = extractionJob?.isActive != false && cache[key] === currentCache

        // These act as a general filter to prevent duplication of links or names
        // Avoid any possible ConcurrentModificationException
        val currentLinksUrls = ConcurrentHashMap.newKeySet<String>()
        val currentSubsUrls = ConcurrentHashMap.newKeySet<String>()
        // Use atomics as otherwise we get race conditions when incrementing, while rare it did actually happen!
        val lastCountedSuffix = ConcurrentHashMap<String, AtomicInteger>()

        synchronized(currentCache) {
            if (!isCurrent()) return false
            if (currentCache.linkCache.isNotEmpty()) {
                Log.d(TAG, "Reusing cached links for episode ${current.id}")
            }

            // call all callbacks
            currentCache.linkCache.forEach { link ->
                currentLinksUrls.add(link.url)
                if (sourceTypes.contains(link.type)) {
                    callback(link to null)
                }
            }

            currentCache.subtitleCache.forEach { sub ->
                currentSubsUrls.add(sub.url)
                lastCountedSuffix.getOrPut(sub.originalName) { AtomicInteger(0) }.incrementAndGet()
                subtitleCallback(sub)
            }

            // this stops all execution if links are cached
            // no extra get requests
            if (currentCache.saturated) {
                return true
            }
        }

        Log.d(TAG, "Extracting links for episode ${current.id}, forced=$clearCache")
        val result = APIRepository(
            getApiFromNameNull(current.apiName) ?: throw Exception("This provider does not exist")
        ).loadLinks(
            current.data,
            isCasting = isCasting,
            subtitleCallback = { file ->
                val correctFile = PlayerSubtitleHelper.getSubtitleData(file)
                if (correctFile.url.isBlank() || !currentSubsUrls.add(correctFile.url)) {
                    return@loadLinks
                }

                // this part makes sure that all names are unique for UX
                val nameDecoded = correctFile.originalName.html().toString()
                    .trim() // `%3Ch1%3Esub%20name…` → `<h1>sub name…` → `sub name…`
                val suffixCount =
                    lastCountedSuffix.getOrPut(nameDecoded) { AtomicInteger(0) }.incrementAndGet()

                val updatedFile =
                    correctFile.copy(originalName = nameDecoded, nameSuffix = "$suffixCount")

                synchronized(currentCache) {
                    if (isCurrent() && currentCache.subtitleCache.add(updatedFile)) {
                        subtitleCallback(updatedFile)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            },
            callback = { link ->
                if (link.url.isBlank() || !currentLinksUrls.add(link.url)) {
                    return@loadLinks
                }

                synchronized(currentCache) {
                    if (isCurrent() && currentCache.linkCache.add(link)) {
                        if (sourceTypes.contains(link.type)) {
                            callback(Pair(link, null))
                        }

                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            }
        )

        synchronized(currentCache) {
            if (!isCurrent()) return false
            currentCache.saturated = currentCache.linkCache.isNotEmpty()
            currentCache.lastCachedTimestamp = unixTime
        }

        return result
    }
}