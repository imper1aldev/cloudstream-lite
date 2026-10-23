package com.lagradost.cloudstream3.ui.tv

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.ui.lite.LiteFavorites
import com.lagradost.cloudstream3.ui.lite.filterAndSortFavorites
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.FavoritesData
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns presentation and file observations, never deletion deadlines or another favorites store. */
class NexoTvFavoritesViewModel : ViewModel() {
    private val visible = MutableLiveData<List<FavoritesData>>(emptyList())
    val items: LiveData<List<FavoritesData>> = visible
    private var all: List<FavoritesData> = emptyList()
    private var downloaded: Set<Int> = emptySet()
    private var files: Map<Int?, List<DownloadEpisodeCached>> = emptyMap()
    var statuses: Map<Int, DownloadType> = emptyMap(); private set
    var query = ""; private set
    var onlyDownloaded = false; private set
    var sorting = ListSorting.entries.getOrNull(DataStoreHelper.librarySortingMode) ?: ListSorting.UpdatedNew; private set
    var hasLoaded = false; private set
    private var context: Context? = null
    private var job: Job? = null
    private val library: (Boolean) -> Unit = { refresh() }
    private val status: (Pair<Int, DownloadType>) -> Unit = { refresh() }
    private val deleted: (Int) -> Unit = { refresh() }
    fun start(context: Context, query: String, onlyDownloaded: Boolean) {
        if (this.context == null) {
            this.context = context.applicationContext
            this.query = query; this.onlyDownloaded = onlyDownloaded
            MainActivity.reloadLibraryEvent += library
            MainActivity.reloadAccountEvent += library
            VideoDownloadManager.downloadStatusEvent += status
            VideoDownloadManager.downloadDeleteEvent += deleted
        }
        refresh()
    }
    fun setQuery(value: String) { if (value != query) { query = value; filter() } }
    fun toggleDownloaded() { onlyDownloaded = !onlyDownloaded; filter() }
    fun setSorting(value: ListSorting) { sorting = value; DataStoreHelper.librarySortingMode = value.ordinal; filter() }
    fun localEpisodes(id: Int?) = files[id].orEmpty()
    fun refresh() {
        val ctx = context ?: return
        viewModelScope.launch {
            job?.cancel()
            job = launch {
                val account = DataStoreHelper.currentAccount
                val data = withContext(Dispatchers.IO) {
                    val favorites = DataStoreHelper.getAllFavorites()
                    val entries = favorites.associate { favorite -> favorite.id to LiteFavorites.episodes(favorite.id ?: 0).mapNotNull { episode ->
                        VideoDownloadManager.getDownloadFileInfo(ctx, episode.id)?.takeIf { it.fileLength > 0 }?.let { episode to it }
                    } }
                    favorites to entries
                }
                if (account != DataStoreHelper.currentAccount) { refresh(); return@launch }
                hasLoaded = true
                all = data.first
                downloaded = data.second.filterValues { entries -> entries.any { (_, file) -> file.fileLength > 1024 && file.fileLength + 1024 >= file.totalBytes } }.keys.filterNotNull().toSet()
                statuses = data.second.values.flatten().associate { (episode, file) -> episode.id to
                    if (file.fileLength > 1024 && file.fileLength + 1024 >= file.totalBytes) DownloadType.IsDone else DownloadType.IsPaused } + VideoDownloadManager.downloadStatus.toMap()
                files = all.associate { favorite -> favorite.id to LiteFavorites.episodes(favorite.id ?: 0).filter { episode ->
                    episode.id in data.second[favorite.id].orEmpty().map { it.first.id } || statuses[episode.id] in listOf(DownloadType.IsPending, DownloadType.IsDownloading)
                }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode })) }
                filter()
            }
        }
    }
    private fun filter() { visible.value = filterAndSortFavorites(all, query, downloaded, onlyDownloaded, sorting) }
    override fun onCleared() {
        MainActivity.reloadLibraryEvent -= library; MainActivity.reloadAccountEvent -= library
        VideoDownloadManager.downloadStatusEvent -= status; VideoDownloadManager.downloadDeleteEvent -= deleted
        context = null
        super.onCleared()
    }
}
