package com.lagradost.cloudstream3.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.material.snackbar.Snackbar
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached
import com.lagradost.cloudstream3.ui.download.*
import com.lagradost.cloudstream3.ui.lite.*
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class FavoritesFragment : BaseComposeFragment() {
    @Composable override fun Content() {
        val context = LocalContext.current
        val view = LocalView.current
        var favorites by remember { mutableStateOf(DataStoreHelper.getAllFavorites()) }
        var downloaded by remember { mutableStateOf(setOf<Int>()) }
        var revision by remember { mutableStateOf(0) }
        var onlyDownloaded by rememberSaveable { mutableStateOf(arguments?.getBoolean("downloaded") == true) }
        var query by rememberSaveable { mutableStateOf("") }
        var sorting by remember { mutableStateOf(ListSorting.entries.getOrNull(DataStoreHelper.librarySortingMode) ?: ListSorting.UpdatedNew) }
        var sortMenu by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(DataStoreHelper.getAllFavorites().firstOrNull { it.id == arguments?.getInt("parentId") }) }
        var localEpisodes by remember { mutableStateOf(emptyList<DownloadEpisodeCached>()) }
        var statuses by remember { mutableStateOf(VideoDownloadManager.downloadStatus.toMap()) }
        DisposableEffect(Unit) {
            val update: (Boolean) -> Unit = { favorites = DataStoreHelper.getAllFavorites(); revision++ }
            val status: (Pair<Int, DownloadType>) -> Unit = { statuses = statuses + it; revision++ }
            val deleted: (Int) -> Unit = { statuses = statuses - it; revision++ }
            MainActivity.reloadLibraryEvent += update
            VideoDownloadManager.downloadStatusEvent += status
            VideoDownloadManager.downloadDeleteEvent += deleted
            onDispose {
                MainActivity.reloadLibraryEvent -= update
                VideoDownloadManager.downloadStatusEvent -= status
                VideoDownloadManager.downloadDeleteEvent -= deleted
            }
        }
        LaunchedEffect(favorites, revision, selected?.id) {
            val files = withContext(Dispatchers.IO) {
                favorites.associate { favorite -> favorite.id to LiteFavorites.episodes(favorite.id ?: 0).mapNotNull { ep ->
                    VideoDownloadManager.getDownloadFileInfo(context, ep.id)?.takeIf { it.fileLength > 0 }?.let { ep to it }
                } }
            }
            downloaded = files.filterValues { list -> list.any { (_, file) -> file.fileLength > 1024 && file.fileLength + 1024 >= file.totalBytes } }.keys.filterNotNull().toSet()
            val selectedFiles = files[selected?.id].orEmpty().map { it.first.id }.toSet()
            localEpisodes = LiteFavorites.episodes(selected?.id ?: 0).filter {
                it.id in selectedFiles || statuses[it.id] in listOf(DownloadType.IsPending, DownloadType.IsDownloading) }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode }))
            val persisted = files.values.flatten().associate { (ep, file) -> ep.id to
                if (file.fileLength > 1024 && file.fileLength + 1024 >= file.totalBytes) DownloadType.IsDone else DownloadType.IsPaused }
            statuses = persisted + VideoDownloadManager.downloadStatus
        }
        val filtered = favorites.filter { it.name.contains(query, ignoreCase = true) && (!onlyDownloaded || it.id in downloaded) }
        val sorted = when (sorting) {
            ListSorting.AlphabeticalA -> filtered.sortedBy { it.name.lowercase() }
            ListSorting.AlphabeticalZ -> filtered.sortedByDescending { it.name.lowercase() }
            ListSorting.RatingHigh -> filtered.sortedByDescending { it.score?.toInt(100) ?: 0 }
            ListSorting.RatingLow -> filtered.sortedBy { it.score?.toInt(100) ?: 0 }
            ListSorting.ReleaseDateNew -> filtered.sortedByDescending { it.year ?: 0 }
            ListSorting.ReleaseDateOld -> filtered.sortedBy { it.year ?: 0 }
            ListSorting.UpdatedOld -> filtered.sortedBy { it.latestUpdatedTime }
            else -> filtered.sortedByDescending { it.latestUpdatedTime }
        }
        Column(Modifier.fillMaxSize().background(LiteColors.background)) {
            LiteHeader(stringResource(R.string.favorites_list_name), { openLiteSearch(view) })
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.title_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = LiteSpacing.screenPadding))
            Row(Modifier.fillMaxWidth().padding(horizontal = LiteSpacing.screenPadding),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                FilterChip(onlyDownloaded, { onlyDownloaded = !onlyDownloaded },
                    label = { Text(stringResource(R.string.lite_downloaded_filter)) })
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(stringResource(R.string.lite_sort)) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        ListSorting.entries.filter { it != ListSorting.Query }.forEach { method ->
                            DropdownMenuItem(text = { Text(stringResource(method.stringRes)) }, onClick = {
                                sorting = method; DataStoreHelper.librarySortingMode = method.ordinal; sortMenu = false
                            })
                        }
                    }
                }
            }
            if (sorted.isEmpty()) LiteEmptyState(stringResource(R.string.no_data))
            else LiteGrid(sorted, { item -> loadResult(item.url, item.apiName, item.name) },
                onLongClick = { item -> selected = item as? DataStoreHelper.FavoritesData })
        }
        selected?.let { favorite ->
            ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = LiteColors.background) {
                Text(favorite.name, style = LiteType.rowTitle, modifier = Modifier.padding(LiteSpacing.screenPadding))
                TextButton(onClick = { selected = null; loadResult(favorite.url, favorite.apiName, favorite.name) }) { Text(stringResource(R.string.lite_hero_details)) }
                TextButton(onClick = {
                    DataStoreHelper.removeFavoritesData(favorite.id)
                    selected = null
                    Snackbar.make(view, context.getString(R.string.favorite_removed).format(favorite.name), 10_000)
                        .setAction(R.string.lite_undo) { DataStoreHelper.setFavoritesData(favorite.id, favorite) }.show()
                }) { Text(stringResource(R.string.action_remove_from_favorites)) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(localEpisodes, key = { it.id }) { episode ->
                        var menu by remember(episode.id) { mutableStateOf(false) }
                        val status = statuses[episode.id]
                        Row(Modifier.fillMaxWidth().padding(horizontal = LiteSpacing.screenPadding), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(episode.name ?: "${episode.season ?: 1} · ${episode.episode}", Modifier.weight(1f))
                            if (status == DownloadType.IsDone) TextButton(onClick = { DownloadButtonSetup.handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_PLAY_FILE, episode)) }) { Text(stringResource(R.string.lite_play)) }
                            Box {
                                LiteDownloadIcon(episode.id, status, { menu = true })
                                DropdownMenu(menu, { menu = false }) {
                                    val action = if (status == DownloadType.IsDownloading) DOWNLOAD_ACTION_PAUSE_DOWNLOAD else DOWNLOAD_ACTION_RESUME_DOWNLOAD
                                    if (status != DownloadType.IsDone) DropdownMenuItem(text = { Text(stringResource(if (action == DOWNLOAD_ACTION_PAUSE_DOWNLOAD) R.string.pause else R.string.resume)) }, onClick = {
                                        menu = false; DownloadButtonSetup.handleDownloadClick(DownloadClickEvent(action, episode))
                                    })
                                    if (status != DownloadType.IsDone) DropdownMenuItem(text = { Text(stringResource(R.string.cancel)) }, onClick = {
                                        menu = false; com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager.cancelDownload(episode.id)
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                        menu = false; DownloadButtonSetup.handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_DELETE_FILE, episode))
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
