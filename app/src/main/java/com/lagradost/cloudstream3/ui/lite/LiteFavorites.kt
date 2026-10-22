package com.lagradost.cloudstream3.ui.lite

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.RESULT_FAVORITES_STATE_DATA
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.*

/** Process-owned grace period: survives navigation; deadlines survive process restarts. */
object LiteFavorites {
    const val GRACE_MS = 10_000L
    private const val PENDING = "lite_favorite_removals"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()

    fun episodes(parentId: Int): List<DownloadEpisodeCached> =
        getKeys("$DOWNLOAD_EPISODE_CACHE/$parentId")?.mapNotNull { getKey<DownloadEpisodeCached>(it) }.orEmpty()

    fun added(id: Int) {
        val key = "${DataStoreHelper.currentAccount}/$id"
        removeKey(PENDING, key)
        scope.launch { jobs.remove(key)?.cancel() }
        changed()
    }

    fun removed(context: Context, id: Int) {
        val account = DataStoreHelper.currentAccount
        val key = "$account/$id"
        val deadline = System.currentTimeMillis() + GRACE_MS
        setKey(PENDING, key, deadline)
        scope.launch { schedule(context.applicationContext, account.toString(), id, deadline) }
        changed()
    }

    private fun schedule(context: Context, account: String, id: Int, deadline: Long) {
        val key = "$account/$id"
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
            val favoriteInAnyAccount = DataStoreHelper.getAccounts(context).any { profile ->
                    getKey<DataStoreHelper.FavoritesData>("${profile.keyIndex}/$RESULT_FAVORITES_STATE_DATA", id.toString()) != null
                }
            if (canDeleteFavoriteDownloads(System.currentTimeMillis(), deadline, getKey(PENDING, key), favoriteInAnyAccount)) {
                val ids = episodes(id).map { it.id }.toSet() + DownloadQueueManager.queue.value.filter { it.parentId == id }.map { it.id }
                ids.forEach(DownloadQueueManager::cancelDownload)
                VideoDownloadManager.deleteFilesAndUpdateSettings(context, ids, scope) {
                    removeKey(PENDING, key)
                    changed()
                }
            } else {
                removeKey(PENDING, key)
            }
            jobs.remove(key)
        }
    }

    fun initialize(context: Context) {
        val pendingIds = getKeys(PENDING).orEmpty().mapNotNull { it.substringAfterLast('/').toIntOrNull() }.toSet()
        getKeys(PENDING).orEmpty().forEach { path ->
            val key = path.removePrefix("$PENDING/")
            val id = key.substringAfterLast('/').toIntOrNull() ?: return@forEach
            val account = key.substringBeforeLast('/')
            val deadline = getKey<Long>(path) ?: return@forEach
            schedule(context.applicationContext, account, id, deadline)
        }
        scope.launch {
            val existing = withContext(Dispatchers.IO) {
                getKeys(DOWNLOAD_HEADER_CACHE).orEmpty().mapNotNull { getKey<DownloadHeaderCached>(it) }
                    .filter { header -> header.id !in pendingIds && episodes(header.id).any { VideoDownloadManager.getDownloadFileInfo(context, it.id)?.fileLength?.let { size -> size > 0 } == true } }
            }
            existing.forEach { header ->
                if (getKey<Long>(PENDING, "${DataStoreHelper.currentAccount}/${header.id}") == null) add(header)
            }
        }
    }

    fun accepted(item: DownloadQueueWrapper) {
        val data = item.downloadItem ?: return
        val header = DownloadHeaderCached(data.apiName, data.resultUrl, data.resultType,
            data.resultName, data.resultPoster, System.currentTimeMillis(), data.resultId)
        setKey(DOWNLOAD_HEADER_CACHE, data.resultId.toString(), header)
        setKey("$DOWNLOAD_EPISODE_CACHE/${data.resultId}", data.episode.id.toString(), DownloadEpisodeCached(
            data.episode.name, data.episode.poster, data.episode.episode, data.episode.season,
            data.resultId, data.episode.score, data.episode.description, System.currentTimeMillis(), data.episode.id))
        add(header)
    }

    private fun add(header: DownloadHeaderCached) {
        if (DataStoreHelper.getFavoritesData(header.id) != null) return
        val now = System.currentTimeMillis()
        DataStoreHelper.setFavoritesData(header.id, DataStoreHelper.FavoritesData(
            now, header.id, now, header.name, header.url, header.apiName, header.type, header.poster, null))
    }

    private fun changed() {
        MainActivity.reloadLibraryEvent(true)
        MainActivity.bookmarksUpdatedEvent(true)
    }
}
