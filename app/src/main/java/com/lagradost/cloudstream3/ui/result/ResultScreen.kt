package com.lagradost.cloudstream3.ui.result

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.services.SubscriptionWorkManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_SHARE
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_CANCEL_PENDING
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_DELETE_FILE
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PAUSE_DOWNLOAD
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_RESUME_DOWNLOAD
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.lite.LiteEmptyState
import com.lagradost.cloudstream3.ui.lite.LiteIconAction
import com.lagradost.cloudstream3.ui.lite.LiteDownloadIcon
import com.lagradost.cloudstream3.ui.lite.liteGlass
import com.lagradost.cloudstream3.ui.lite.LitePosterCard
import com.lagradost.cloudstream3.ui.lite.LiteColors
import com.lagradost.cloudstream3.ui.lite.LiteErrorState
import com.lagradost.cloudstream3.ui.lite.LiteGradients
import com.lagradost.cloudstream3.ui.lite.LiteRadii
import com.lagradost.cloudstream3.ui.lite.LiteSkeleton
import com.lagradost.cloudstream3.ui.lite.LiteSpacing
import com.lagradost.cloudstream3.ui.lite.LiteType
import com.lagradost.cloudstream3.ui.lite.LiteWidthClass
import com.lagradost.cloudstream3.ui.lite.liteCardBorder
import com.lagradost.cloudstream3.ui.lite.rememberLiteBreakpoint
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.AppContextUtils.isNetworkAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.isCastApiAvailable
import com.lagradost.cloudstream3.ui.result.ResultFragment.updateUIEvent
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream4.compose.Screen
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import java.text.DateFormat
import java.util.Date
import java.net.URLEncoder
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lite Details screen (T4.1). Consumes the EXISTING [ResultViewModel2] public
 * state and fires actions only through its PUBLIC API (`load`, `handleAction`).
 *
 * The play handoff is untouched: [ResultViewModel2.handleAction] →
 * `handleEpisodeClickEvent` → `ACTION_PLAY_EPISODE_IN_PLAYER` →
 * `GeneratorPlayer.newInstance` stays byte-for-byte the legacy contract. This
 * screen only *invokes* it, never reimplements or reroutes it.
 *
 * Header uses only ResultViewModel2's public page, movie, resume, and watch-state
 * streams. Its play callbacks preserve the existing action handoff unchanged.
 *
 * TV/emulator never reaches this screen — [ResultFragment2] proxies the legacy
 * [ResultTrailerPlayer] on those layouts and only calls [Content] on phone/tablet.
 */
object ResultScreen : Screen {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val owner = LocalViewModelStoreOwner.current
        // Navigation hands the composable a NavBackStackEntry as its local owner,
        // not the hosting Fragment, so resolve the Fragment from the view tree to
        // read the same arguments the legacy fragment used.
        val composeView = LocalView.current
        val fragment: Fragment? = remember(owner, composeView) {
            FragmentManager.findFragment<Fragment>(composeView)
        }
        // Same args the legacy fragment reads; captured once so the start-action
        // bundle removal inside getStoredData() does not race recomposition.
        val storedData = remember(fragment) { fragment?.getStoredData() }
        val viewModel: ResultViewModel2 = viewModel { ResultViewModel2() }
        val syncModel: SyncViewModel = viewModel { SyncViewModel() }

        fun reload() {
            val sd = storedData ?: return
            Log.i("T4.1Details", "reload url=${sd.url} api=${sd.apiName}")
            viewModel.load(
                fragment?.activity,
                sd.url,
                sd.apiName,
                sd.showFillers,
                sd.dubStatus,
                sd.start,
            )
        }

        LaunchedEffect(Unit) {
            // Legacy reloadViewModel parity: only load when not already loaded.
            Log.i(
                "T4.1Details",
                "effect restart=${storedData?.restart} hasLoaded=${viewModel.hasLoaded()}"
            )
            if (storedData != null && (storedData.restart || !viewModel.hasLoaded())) reload()
        }

        val page by viewModel.page.observeAsStateCompat(null)
        val movie by viewModel.movie.observeAsStateCompat(null)
        val resume by viewModel.resumeWatching.observeAsStateCompat(null)
        val watchStatus by viewModel.watchStatus.observeAsStateCompat(WatchType.NONE)
        val favoriteStatus by viewModel.favoriteStatus.observeAsStateCompat(null)
        val subscribeStatus by viewModel.subscribeStatus.observeAsStateCompat(null)
        val recommendations by viewModel.recommendations.observeAsStateCompat(emptyList())
        val synced by syncModel.synced.observeAsStateCompat(emptyList())
        val syncMetadata by syncModel.metadata.observeAsStateCompat(null)
        val episodesPage by viewModel.episodes.observeAsStateCompat(null)
        val seasonSelections by viewModel.seasonSelections.observeAsStateCompat(emptyList())
        val selectedSeason by viewModel.selectedSeason.observeAsStateCompat(null)
        val rangeSelections by viewModel.rangeSelections.observeAsStateCompat(emptyList())
        val selectedRange by viewModel.selectedRange.observeAsStateCompat(null)
        val sortSelections by viewModel.sortSelections.observeAsStateCompat(emptyList())
        val selectedSorting by viewModel.selectedSorting.observeAsStateCompat(null)
        val episodesCountText by viewModel.episodesCountText.observeAsStateCompat(null)
        val dubSelections by viewModel.dubSubSelections.observeAsStateCompat(emptyList())
        val selectedDub by viewModel.selectedDubStatus.observeAsStateCompat(null)
        var lastEpisodes by remember(storedData?.url, selectedSeason, selectedRange, selectedDub) {
            mutableStateOf(emptyList<ResultEpisode>())
        }
        LaunchedEffect(episodesPage, selectedSeason, selectedRange, selectedDub) {
            (episodesPage as? Resource.Success)?.value?.let { lastEpisodes = it.toList() }
        }
        DisposableEffect(viewModel, syncModel) {
            val refresh: (Int?) -> Unit = { viewModel.reloadEpisodes(); syncModel.updateUserData() }
            val favoriteRefresh: (Boolean) -> Unit = { viewModel.refreshFavorite() }
            com.lagradost.cloudstream3.MainActivity.reloadLibraryEvent += favoriteRefresh
            updateUIEvent += refresh
            onDispose {
                updateUIEvent -= refresh
                com.lagradost.cloudstream3.MainActivity.reloadLibraryEvent -= favoriteRefresh
            }
        }
        val downloadStatuses = remember { mutableStateOf(VideoDownloadManager.downloadStatus.toMap()) }
        val deletedDownloadIds = remember { mutableStateOf(emptySet<Int>()) }
        val downloadIds = (listOfNotNull((movie as? Resource.Success)?.value?.second?.id) +
            (episodesPage as? Resource.Success)?.value.orEmpty().map { it.id }).distinct()
        LaunchedEffect(downloadIds) {
            val stored = withContext(Dispatchers.IO) {
                downloadIds.mapNotNull { id ->
                    VideoDownloadManager.getDownloadFileInfo(context, id)?.let { file ->
                        id to if (file.fileLength > 1024L && file.fileLength + 1024L >= file.totalBytes)
                            VideoDownloadManager.DownloadType.IsDone else VideoDownloadManager.DownloadType.IsPaused
                    }
                }.toMap()
            }
            // Persistent files survive process restarts; current event states take priority.
            downloadStatuses.value = stored.filterKeys { it !in deletedDownloadIds.value } + downloadStatuses.value
        }
        DisposableEffect(Unit) {
            val statusObserver: (Pair<Int, VideoDownloadManager.DownloadType>) -> Unit = { event ->
                // Queue events precede mutation of the global map; use their payload.
                deletedDownloadIds.value = deletedDownloadIds.value - event.first
                downloadStatuses.value = downloadStatuses.value +
                    VideoDownloadManager.downloadStatus.filterKeys { it !in deletedDownloadIds.value } + event
            }
            val deleteObserver: (Int) -> Unit = { id ->
                deletedDownloadIds.value = deletedDownloadIds.value + id
                downloadStatuses.value = downloadStatuses.value - id
            }
            VideoDownloadManager.downloadStatusEvent += statusObserver
            VideoDownloadManager.downloadDeleteEvent += deleteObserver
            onDispose {
                VideoDownloadManager.downloadStatusEvent -= statusObserver
                VideoDownloadManager.downloadDeleteEvent -= deleteObserver
            }
        }
        val playerAction = storedData?.playerAction ?: ACTION_PLAY_EPISODE_IN_PLAYER

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LiteColors.background)
        ) {
            val current = page
            when (current) {
                null, is Resource.Loading -> Column(Modifier.fillMaxSize()) {
                    DetailsBackBar { fragment?.activity?.onBackPressedDispatcher?.onBackPressed() }
                    ResultLoading(Modifier.weight(1f))
                }
                is Resource.Failure -> {
                    val offline = !context.isNetworkAvailable()
                    Column(Modifier.fillMaxSize()) {
                        DetailsBackBar { fragment?.activity?.onBackPressedDispatcher?.onBackPressed() }
                        LiteErrorState(
                            modifier = Modifier.weight(1f),
                            message = stringResource(
                                if (offline) R.string.no_internet_connection else R.string.error
                            ),
                            onRetry = { reload() },
                        )
                    }
                }

                is Resource.Success -> {
                    val data = current.value
                    val favoriteAdded = stringResource(R.string.favorite_added)
                    val favoriteRemoved = stringResource(R.string.favorite_removed)
                    val subscriptionNew = stringResource(R.string.subscription_new)
                    val subscriptionDeleted = stringResource(R.string.subscription_deleted)
                    LaunchedEffect(data.url, data.syncData) {
                        if (syncModel.addSyncs(data.syncData)) {
                            syncModel.updateMetaAndUser()
                            syncModel.updateSynced()
                        } else {
                            syncModel.addFromUrl(data.url)
                        }
                    }
                    LaunchedEffect(syncMetadata) {
                        val metadata = syncMetadata
                        if (metadata is Resource.Success) {
                            viewModel.setMeta(metadata.value, syncModel.getSyncs())
                        }
                    }
                    val moviePlay = (movie as? Resource.Success)?.value
                    DetailsContent(
                        modifier = Modifier.fillMaxSize(),
                        data = data,
                        moviePlay = moviePlay,
                        resume = resume,
                        watchStatus = watchStatus,
                        favoriteStatus = favoriteStatus,
                        subscribeStatus = subscribeStatus,
                        episodes = (episodesPage as? Resource.Success)?.value ?: lastEpisodes,
                        episodesLoaded = episodesPage is Resource.Success,
                        recommendations = recommendations.orEmpty(),
                        syncedAccounts = synced.orEmpty().filter { it.isSynced && it.hasAccount },
                        seasonSelections = seasonSelections.orEmpty(),
                        selectedSeason = selectedSeason,
                        rangeSelections = rangeSelections.orEmpty(),
                        selectedRange = selectedRange,
                        sortSelections = sortSelections.orEmpty(),
                        selectedSorting = selectedSorting,
                        episodesCount = episodesCountText?.asStringNull(context),
                        downloadStatuses = downloadStatuses.value,
                        canDownload = viewModel.currentRepo?.api?.hasDownloadSupport == true,
                        canCast = viewModel.currentRepo?.api?.hasChromecastSupport == true && context.isCastApiAvailable(),
                        dubSelections = dubSelections.orEmpty().mapNotNull { (label, status) -> label?.asStringNull(context)?.let { it to status } },
                        selectedDub = selectedDub?.asStringNull(context),
                        onDubSelected = viewModel::changeDubStatus,
                        syncModel = syncModel,
                        onPlay = { ep ->
                            // Legacy movie path (ResultFragmentPhone:878-882).
                            viewModel.handleAction(EpisodeClickEvent(ACTION_CLICK_DEFAULT, ep))
                        },
                        onResume = { ep ->
                            // Legacy resumeAction (ResultFragmentPhone:1431-1441) —
                            // playerAction is StoredData.playerAction, unchanged.
                            viewModel.handleAction(EpisodeClickEvent(playerAction, ep))
                        },
                        onWatchStatus = { status -> viewModel.updateWatchStatus(status, context) },
                        onBack = { fragment?.activity?.onBackPressedDispatcher?.onBackPressed() },
                        onShare = { shareResult(context, data) },
                        onFavorite = {
                            val originalFavorite = com.lagradost.cloudstream3.utils.DataStoreHelper.getAllFavorites().firstOrNull { it.url == data.url }
                            viewModel.toggleFavoriteStatus(context) { newStatus ->
                                if (newStatus != null) {
                                    val message = if (newStatus) favoriteAdded else favoriteRemoved
                                    if (newStatus) Toast.makeText(context, message.format(data.title), Toast.LENGTH_SHORT).show()
                                    else com.google.android.material.snackbar.Snackbar.make(composeView, message.format(data.title), 10_000)
                                        .setAction(R.string.lite_undo) {
                                            originalFavorite?.let { com.lagradost.cloudstream3.utils.DataStoreHelper.setFavoritesData(it.id, it) }
                                        }.show()
                                }
                            }
                        },
                        onSubscribe = {
                            viewModel.toggleSubscriptionStatus(context) { newStatus ->
                                if (newStatus != null) {
                                    if (newStatus) SubscriptionWorkManager.enqueuePeriodicWork(context)
                                    val message = if (newStatus) subscriptionNew else subscriptionDeleted
                                    Toast.makeText(context, message.format(data.title), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDownload = { episode ->
                            (fragment as? ResultFragment2)?.requestDownload {
                                viewModel.handleAction(EpisodeClickEvent(ACTION_DOWNLOAD_EPISODE, episode))
                            } ?: viewModel.handleAction(EpisodeClickEvent(ACTION_DOWNLOAD_EPISODE, episode))
                        },
                        onSyncRefresh = syncModel::updateMetaAndUser,
                        onRecommendationClick = { response ->
                            SearchHelper.handleSearchClickCallback(
                                SearchClickCallback(SEARCH_ACTION_LOAD, android.view.View(context), -1, response)
                            )
                        },
                        onEpisodeClick = { episode, action ->
                            if (action == ACTION_DOWNLOAD_EPISODE) {
                                (fragment as? ResultFragment2)?.requestDownload {
                                    viewModel.handleAction(EpisodeClickEvent(action, episode))
                                } ?: viewModel.handleAction(EpisodeClickEvent(action, episode))
                            } else viewModel.handleAction(EpisodeClickEvent(action, episode))
                        },
                        onSeasonSelected = viewModel::changeSeason,
                        onRangeSelected = viewModel::changeRange,
                        onSortSelected = viewModel::setSort,
                    )
                }
            }
        }
        ResultActionDialogs(viewModel, viewModel.currentRepo?.api?.hasDownloadSupport == true) { action ->
            (fragment as? ResultFragment2)?.requestDownload(action) ?: action()
        }
    }

    @Composable
    private fun DetailsContent(
        modifier: Modifier,
        data: ResultData,
        moviePlay: Pair<com.lagradost.cloudstream3.utils.UiText, ResultEpisode>?,
        resume: ResumeWatchingStatus?,
        watchStatus: WatchType?,
        favoriteStatus: Boolean?,
        subscribeStatus: Boolean?,
        episodes: List<ResultEpisode>,
        episodesLoaded: Boolean,
        recommendations: List<SearchResponse>,
        syncedAccounts: List<CurrentSynced>,
        seasonSelections: List<Pair<com.lagradost.cloudstream3.utils.UiText?, Int>>,
        selectedSeason: com.lagradost.cloudstream3.utils.UiText?,
        rangeSelections: List<Pair<com.lagradost.cloudstream3.utils.UiText?, EpisodeRange>>,
        selectedRange: com.lagradost.cloudstream3.utils.UiText?,
        sortSelections: List<Pair<com.lagradost.cloudstream3.utils.UiText, EpisodeSortType>>,
        selectedSorting: com.lagradost.cloudstream3.utils.UiText?,
        episodesCount: String?,
        downloadStatuses: Map<Int, VideoDownloadManager.DownloadType>,
        canDownload: Boolean,
        canCast: Boolean,
        dubSelections: List<Pair<String, DubStatus>>,
        selectedDub: String?,
        onDubSelected: (DubStatus) -> Unit,
        syncModel: SyncViewModel,
        onBack: () -> Unit,
        onShare: () -> Unit,
        onFavorite: () -> Unit,
        onSubscribe: () -> Unit,
        onDownload: (ResultEpisode) -> Unit,
        onSyncRefresh: () -> Unit,
        onRecommendationClick: (SearchResponse) -> Unit,
        onPlay: (ResultEpisode) -> Unit,
        onResume: (ResultEpisode) -> Unit,
        onWatchStatus: (WatchType) -> Unit,
        onEpisodeClick: (ResultEpisode, Int) -> Unit,
        onSeasonSelected: (Int) -> Unit,
        onRangeSelected: (EpisodeRange) -> Unit,
        onSortSelected: (EpisodeSortType) -> Unit,
    ) {
        val context = LocalContext.current
        val breakpoint = rememberLiteBreakpoint()
        val configuration = LocalConfiguration.current
        val seasonLabel = stringResource(R.string.season)
        val metadata = listOfNotNull(
            data.yearText?.asStringNull(context),
            data.ratingText?.asStringNull(context),
            data.durationText?.asStringNull(context),
            data.contentRatingText?.asStringNull(context),
            data.tags.joinToString(", ").takeIf { it.isNotBlank() },
        ).filter { it.isNotBlank() }
        val posterUrl = data.posterImage ?: data.posterBackgroundImage ?: data.backgroundPosterUrl
        val backdrop = rememberImageRequest(
            data.posterBackgroundImage ?: data.backgroundPosterUrl ?: posterUrl,
            data.posterHeaders,
        )
        val logo = rememberImageRequest(data.logoUrl?.takeIf { it.isNotBlank() }, data.posterHeaders)
        val poster = rememberImageRequest(posterUrl, data.posterHeaders)
        var bookmarkMenuExpanded by remember { mutableStateOf(false) }
        var downloadMenuExpanded by remember { mutableStateOf(false) }
        var syncDialogExpanded by remember { mutableStateOf(false) }
        val primaryAction = resume?.let { r ->
            val label = if (r.isMovie) {
                stringResource(R.string.continue_watching)
            } else {
                context.getNameFull(r.result.name, r.result.episode, r.result.season)
            }
            label to { onResume(r.result) }
        } ?: moviePlay?.let { (label, episode) ->
            (label.asStringNull(context) ?: data.title) to { onPlay(episode) }
        } ?: episodes.firstOrNull()?.let { episode ->
            data.title to { onEpisodeClick(episode, ACTION_CLICK_DEFAULT) }
        }

        Column(modifier = modifier.background(LiteColors.background)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(LiteColors.background),
                verticalArrangement = Arrangement.spacedBy(LiteSpacing.rowSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            item {
                DetailsFeatureHeader(
                    data = data,
                    backdrop = backdrop,
                    logo = logo,
                    poster = poster,
                    metadata = metadata,
                    onBack = onBack,
                    onShare = onShare,
                )
            }
            item {
            DetailsActionBar(
                primaryLabel = stringResource(if (resume != null) R.string.continue_watching else R.string.lite_play),
                onPrimaryAction = primaryAction?.takeIf { !data.comingSoon }?.second,
                canCast = canCast,
                canDownload = canDownload && !data.comingSoon,
                canSync = syncedAccounts.any { it.hasAccount && it.isSynced },
                hasSubscribe = subscribeStatus != null,
                isSubscribed = subscribeStatus == true,
                isFavorite = favoriteStatus,
                downloadEpisode = moviePlay?.second,
                downloadStatus = downloadEpisodeStatus(moviePlay?.second, downloadStatuses),
                optionsEpisode = if (data.comingSoon) null else resume?.result ?: moviePlay?.second,
                onOptions = { episode -> onEpisodeClick(episode, ACTION_SHOW_OPTIONS) },
                downloadMenuExpanded = downloadMenuExpanded,
                onDownloadMenuExpanded = { downloadMenuExpanded = it },
                onFavorite = onFavorite,
                onSubscribe = onSubscribe,
                onDownload = { episode ->
                    val status = downloadEpisodeStatus(episode, downloadStatuses)
                    if (status == null || status == VideoDownloadManager.DownloadType.IsStopped || status == VideoDownloadManager.DownloadType.IsFailed) {
                        onDownload(episode)
                    } else {
                        downloadMenuExpanded = true
                    }
                },
                onDownloadAction = { action, episode ->
                    DownloadButtonSetup.handleDownloadClick(
                        DownloadClickEvent(action, episode.toDownloadEpisode())
                    )
                },
                onSync = {
                    onSyncRefresh()
                    syncDialogExpanded = true
                },
            )
            resume?.takeIf { !it.isMovie }?.let {
                Text(context.getNameFull(it.result.name, it.result.episode, it.result.season),
                    modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = LiteSpacing.screenPadding),
                    style = LiteType.metadata, color = LiteColors.onSurfaceVariant)
            }
            }
            item {
                DetailsSynopsis(data)
            }

            if (!data.comingSoon && episodes.isNotEmpty()) {
                item {
                    EpisodesHeaderAndSelectors(
                        count = episodesCount,
                        seasonSelections = seasonSelections.orEmpty().mapNotNull { (text, season) ->
                            (text?.asStringNull(context) ?: "$seasonLabel $season") to season
                        },
                        selectedSeason = selectedSeason?.asStringNull(context),
                        rangeSelections = rangeSelections.orEmpty().mapNotNull { (text, range) ->
                            text?.asStringNull(context)?.let { it to range }
                        },
                        selectedRange = selectedRange?.asStringNull(context),
                        sortSelections = sortSelections.orEmpty().map { (text, sort) ->
                            (text.asStringNull(context) ?: "") to sort
                        }.filter { it.first.isNotBlank() },
                        selectedSorting = selectedSorting?.asStringNull(context),
                        onSeasonSelected = onSeasonSelected,
                        onRangeSelected = onRangeSelected,
                        onSortSelected = onSortSelected,
                        dubSelections = dubSelections,
                        selectedDub = selectedDub,
                        onDubSelected = onDubSelected,
                    )
                }
                items(items = episodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        image = rememberImageRequest(episode.poster, data.posterHeaders),
                        downloadStatus = downloadStatuses[episode.id],
                        onClick = { onEpisodeClick(episode, ACTION_CLICK_DEFAULT) },
                        onLongClick = { onEpisodeClick(episode, ACTION_SHOW_OPTIONS) },
                        onDownload = if (canDownload) ({ onEpisodeClick(episode, ACTION_DOWNLOAD_EPISODE) }) else null,
                        onDownloadAction = { action ->
                            DownloadButtonSetup.handleDownloadClick(DownloadClickEvent(action, episode.toDownloadEpisode()))
                        },
                    )
                }
                item { Spacer(Modifier.height(LiteSpacing.screenPadding)) }
            } else if (!data.comingSoon && episodesLoaded && moviePlay == null) {
                item {
                    LiteEmptyState(
                        title = data.noEpisodesFoundText?.asStringNull(context)
                            ?: stringResource(R.string.episodes),
                    )
                }
            }

            val cast = data.actors.orEmpty()
            if (!data.comingSoon && cast.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiteSpacing.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(LiteSpacing.small),
                    ) {
                        Text(stringResource(R.string.lite_cast), style = LiteType.rowTitle, color = LiteColors.onSurface)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap)) {
                            items(cast) { actor ->
                                Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                                    Box(
                                        Modifier.size(64.dp).clip(CircleShape).background(LiteColors.surfaceHighest),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(actor.actor.name.take(1), style = LiteType.heroTitle, color = LiteColors.onSurface)
                                        actor.actor.image?.let { image ->
                                            AsyncImage(model = rememberImageRequest(image, data.posterHeaders),
                                                contentDescription = null, contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                    Text(actor.actor.name, style = LiteType.action, color = LiteColors.onSurface,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    actor.roleString?.takeIf { it.isNotBlank() }?.let { role ->
                                        Text(role, style = LiteType.metadata, color = LiteColors.onSurfaceVariant,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!data.comingSoon && recommendations.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                        Text(
                            text = stringResource(R.string.lite_recommendations),
                            style = LiteType.rowTitle,
                            color = LiteColors.onSurface,
                            modifier = Modifier.padding(horizontal = LiteSpacing.screenPadding),
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = LiteSpacing.screenPadding),
                            horizontalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap),
                        ) {
                            items(recommendations, key = { it.url }) { recommendation ->
                                LitePosterCard(
                                    imageUrl = recommendation.posterUrl,
                                    contentDescription = recommendation.name,
                                    modifier = Modifier.width(112.dp),
                                    onClick = { onRecommendationClick(recommendation) },
                                )
                            }
                        }
                    }
                }
            }
            }
            if (syncDialogExpanded) {
                ResultSyncDialog(syncModel, syncedAccounts.filter { it.hasAccount && it.isSynced },
                    onDismiss = { syncDialogExpanded = false })
            }
        }
    }

    @Composable
    private fun DetailsFeatureHeader(
        data: ResultData,
        backdrop: ImageRequest?,
        logo: ImageRequest?,
        poster: ImageRequest?,
        metadata: List<String>,
        onBack: () -> Unit,
        onShare: () -> Unit,
    ) {
        val bp = rememberLiteBreakpoint()
        val config = LocalConfiguration.current
        val headerHeight = if (bp.isCompact && !bp.isLandscape) 340.dp
            else min(config.screenHeightDp * 0.55f, 440f).coerceAtLeast(240f).dp
        val posterWidth = min(140f, (headerHeight.value - 96f) / 1.5f).dp
        Box(
            modifier = Modifier.fillMaxWidth().height(headerHeight),
        ) {
            AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(LiteGradients.heroScrim))
            Row(
                modifier = Modifier.fillMaxWidth().zIndex(1f).padding(LiteSpacing.screenPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).liteGlass(CircleShape)) {
                    Icon(painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = stringResource(R.string.go_back), tint = LiteColors.onSurface)
                }
                IconButton(onClick = onShare, modifier = Modifier.size(48.dp).liteGlass(CircleShape)) {
                    Icon(painterResource(R.drawable.ic_outline_share_24),
                        contentDescription = stringResource(R.string.result_share), tint = LiteColors.onSurface)
                }
            }
            Row(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(LiteSpacing.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (!bp.isCompact && poster != null) {
                    AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(LiteRadii.heroShape).liteCardBorder())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                    if (data.comingSoon) {
                        Text(stringResource(R.string.lite_coming_soon), style = LiteType.action,
                            color = LiteColors.onSurface,
                            modifier = Modifier.liteGlass(LiteRadii.chipShape).padding(8.dp))
                    }
                    if (logo != null) {
                        var loaded by remember(data.logoUrl) { mutableStateOf(false) }
                        Box(Modifier.fillMaxWidth().height(72.dp)) {
                            if (!loaded) Text(data.title, style = LiteType.detailsTitleLarge,
                                color = LiteColors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            AsyncImage(model = logo, contentDescription = data.title, contentScale = ContentScale.Fit,
                                onSuccess = { loaded = true }, onError = { loaded = false },
                                alignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        Text(data.title, style = LiteType.detailsTitleLarge, color = LiteColors.onSurface,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (metadata.isNotEmpty()) {
                        Text(metadata.joinToString(" • "), style = LiteType.metadata,
                            color = LiteColors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailsSynopsis(data: ResultData) {
        val context = LocalContext.current
        var expanded by rememberSaveable(data.url) { mutableStateOf(false) }
        var overflows by remember(data.url) { mutableStateOf(false) }
        val plot = data.plotText.asStringNull(context)?.html()?.toString().orEmpty()
        if (plot.isNotBlank()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = LiteSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                Text(stringResource(R.string.synopsis), style = LiteType.rowTitle, color = LiteColors.onSurface)
                Text(plot, style = LiteType.description, color = LiteColors.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
                if (expanded || overflows) TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.lite_less else R.string.lite_more))
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DetailsActionBar(
        primaryLabel: String,
        onPrimaryAction: (() -> Unit)?,
        canCast: Boolean,
        canDownload: Boolean,
        canSync: Boolean,
        hasSubscribe: Boolean,
        isSubscribed: Boolean,
        isFavorite: Boolean?,
        downloadEpisode: ResultEpisode?,
        downloadStatus: VideoDownloadManager.DownloadType?,
        optionsEpisode: ResultEpisode?,
        onOptions: (ResultEpisode) -> Unit,
        downloadMenuExpanded: Boolean,
        onDownloadMenuExpanded: (Boolean) -> Unit,
        onFavorite: () -> Unit,
        onSubscribe: () -> Unit,
        onDownload: (ResultEpisode) -> Unit,
        onDownloadAction: (Int, ResultEpisode) -> Unit,
        onSync: () -> Unit,
    ) {
        var moreExpanded by remember { mutableStateOf(false) }
        FlowRow(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = LiteSpacing.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(LiteSpacing.small, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(LiteSpacing.small),
            itemVerticalAlignment = Alignment.Top,
        ) {
            onPrimaryAction?.let {
                PrimaryAction(primaryLabel, modifier = Modifier.weight(1f), onClick = it)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LiteSpacing.small), verticalAlignment = Alignment.Top) {
                if (isFavorite != null) LiteIconAction(
                    icon = if (isFavorite) R.drawable.ic_baseline_favorite_24 else R.drawable.ic_baseline_favorite_border_24,
                    label = stringResource(R.string.lite_favorite), selected = isFavorite, onClick = onFavorite, adaptiveWidth = true,
                )
                downloadEpisode?.takeIf { canDownload }?.let { episode ->
                    Box {
                        LiteDownloadIcon(episode.id, downloadStatus, onClick = { onDownload(episode) }, showLabel = true)
                        DropdownMenu(expanded = downloadMenuExpanded, onDismissRequest = { onDownloadMenuExpanded(false) }) {
                            downloadActions(downloadStatus).forEach { (action, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) },
                                    onClick = { onDownloadMenuExpanded(false); onDownloadAction(action, episode) })
                            }
                        }
                    }
                }
                if (optionsEpisode != null || hasSubscribe || canCast || canSync) Box {
                    LiteIconAction(R.drawable.ic_baseline_more_vert_24, stringResource(R.string.lite_more_actions), { moreExpanded = true }, adaptiveWidth = true)
                    DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                        optionsEpisode?.let { episode ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.lite_options)) },
                                onClick = { moreExpanded = false; onOptions(episode) })
                        }
                        if (hasSubscribe) DropdownMenuItem(
                            text = { Text(stringResource(if (isSubscribed) R.string.lite_following else R.string.lite_follow)) },
                            onClick = { moreExpanded = false; onSubscribe() },
                            leadingIcon = { Icon(painterResource(if (isSubscribed) R.drawable.ic_baseline_notifications_active_24 else R.drawable.baseline_notifications_none_24), null) },
                        )
                        if (canSync) DropdownMenuItem(text = { Text(stringResource(R.string.lite_action_sync)) },
                            onClick = { moreExpanded = false; onSync() })
                        if (canCast) Box(Modifier.padding(horizontal = LiteSpacing.small)) { ResultCastRouteAction() }
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailsBackBar(onBack: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().background(LiteColors.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                    contentDescription = stringResource(R.string.go_back),
                    tint = LiteColors.onSurface,
                )
            }
        }
    }

    @Composable
    private fun SecondaryAction(label: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            shape = LiteRadii.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = LiteColors.surfaceHighest,
                contentColor = LiteColors.onSurface,
            ),
            modifier = Modifier.defaultMinSize(minHeight = LiteSpacing.minTouchTarget),
        ) {
            Text(label, style = LiteType.action, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    private fun downloadActions(
        status: VideoDownloadManager.DownloadType?,
    ): List<Pair<Int, Int>> = when (status) {
        VideoDownloadManager.DownloadType.IsDone -> listOf(
            DOWNLOAD_ACTION_PLAY_FILE to R.string.popup_play_file,
            DOWNLOAD_ACTION_DELETE_FILE to R.string.popup_delete_file,
        )

        VideoDownloadManager.DownloadType.IsDownloading -> listOf(
            DOWNLOAD_ACTION_PAUSE_DOWNLOAD to R.string.popup_pause_download,
            DOWNLOAD_ACTION_DELETE_FILE to R.string.popup_delete_file,
        )

        VideoDownloadManager.DownloadType.IsPaused -> listOf(
            DOWNLOAD_ACTION_RESUME_DOWNLOAD to R.string.popup_resume_download,
            DOWNLOAD_ACTION_DELETE_FILE to R.string.popup_delete_file,
        )

        VideoDownloadManager.DownloadType.IsPending -> listOf(
            DOWNLOAD_ACTION_CANCEL_PENDING to R.string.cancel,
        )

        null, VideoDownloadManager.DownloadType.IsStopped,
        VideoDownloadManager.DownloadType.IsFailed -> emptyList()
    }

    private fun downloadEpisodeStatus(
        episode: ResultEpisode?,
        statuses: Map<Int, VideoDownloadManager.DownloadType>,
    ) = episode?.let { statuses[it.id] }

    private fun ResultEpisode.toDownloadEpisode() = DownloadObjects.DownloadEpisodeCached(
        name = name,
        poster = poster,
        episode = episode,
        season = season,
        id = id,
        parentId = parentId,
        score = score,
        description = description,
        cacheTime = System.currentTimeMillis(),
    )

    private fun shareResult(context: android.content.Context, data: ResultData) {
        val nameBase64 = base64Encode(data.apiName.asString(context).toByteArray(Charsets.UTF_8))
        val urlBase64 = base64Encode(data.url.toByteArray(Charsets.UTF_8))
        val encodedUri = URLEncoder.encode("$APP_STRING_SHARE:$nameBase64?$urlBase64", "UTF-8")
        val redirectUrl = "https://recloudstream.github.io/csredirect?redirectto=$encodedUri"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, data.title)
            putExtra(Intent.EXTRA_TEXT, redirectUrl)
        }
        context.startActivity(Intent.createChooser(intent, data.title))
    }

    @Composable
    private fun EpisodesHeaderAndSelectors(
        count: String?,
        seasonSelections: List<Pair<String, Int>>,
        selectedSeason: String?,
        rangeSelections: List<Pair<String, EpisodeRange>>,
        selectedRange: String?,
        sortSelections: List<Pair<String, EpisodeSortType>>,
        selectedSorting: String?,
        onSeasonSelected: (Int) -> Unit,
        onRangeSelected: (EpisodeRange) -> Unit,
        onSortSelected: (EpisodeSortType) -> Unit,
        dubSelections: List<Pair<String, DubStatus>>,
        selectedDub: String?,
        onDubSelected: (DubStatus) -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LiteSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(LiteSpacing.small),
        ) {
            Text(
                text = count ?: stringResource(R.string.episodes),
                style = LiteType.rowTitle,
                color = LiteColors.onSurface,
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                if (dubSelections.size > 1 && selectedDub != null) {
                    EpisodeSelector(selectedDub, dubSelections, onDubSelected)
                }
                if (seasonSelections.size > 1 && selectedSeason != null) {
                    EpisodeSelector(selectedSeason, seasonSelections, onSeasonSelected)
                }
                if (rangeSelections.size > 1 && selectedRange != null) {
                    EpisodeSelector(selectedRange, rangeSelections, onRangeSelected)
                }
                if (sortSelections.size > 1 && selectedSorting != null) {
                    EpisodeSelector(selectedSorting, sortSelections, onSortSelected)
                }
            }
        }
    }

    @Composable
    private fun <T> EpisodeSelector(
        selected: String,
        options: List<Pair<String, T>>,
        onSelected: (T) -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(
                onClick = { expanded = true },
                shape = LiteRadii.buttonShape,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = LiteColors.onSurface,
                ),
                modifier = Modifier.liteGlass(LiteRadii.buttonShape)
                    .defaultMinSize(minHeight = LiteSpacing.minTouchTarget),
            ) {
                Text(selected, style = LiteType.action, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(LiteSpacing.small))
                Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_down_24), contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onSelected(value)
                        },
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun EpisodeRow(
        episode: ResultEpisode,
        image: ImageRequest?,
        downloadStatus: VideoDownloadManager.DownloadType?,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        onDownload: (() -> Unit)?,
        onDownloadAction: (Int) -> Unit,
    ) {
        var downloadMenu by remember(episode.id) { mutableStateOf(false) }
        val title = episode.name?.takeIf { it.isNotBlank() }
            ?.let { "${episode.episode}. $it" }
            ?: "${stringResource(R.string.episode)} ${episode.episode}"
        val description = episode.description?.html()?.toString()?.takeIf { it.isNotBlank() }
        val position = episode.getDisplayPosition()
        val watched = episode.videoWatchState == VideoWatchState.Watched ||
            (position > 0 && position >= episode.duration)
        val progress = if (episode.duration > 0 && !watched) {
            (position.toFloat() / episode.duration).coerceIn(0f, 1f)
        } else 0f
        val locale = LocalLocale.current.platformLocale
        val date = episode.airDate?.let {
            DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(it))
        }
        val downloadLabel = when (downloadStatus) {
            VideoDownloadManager.DownloadType.IsDone -> stringResource(R.string.downloaded)
            VideoDownloadManager.DownloadType.IsDownloading,
            VideoDownloadManager.DownloadType.IsPending -> stringResource(R.string.downloading)
            VideoDownloadManager.DownloadType.IsPaused -> stringResource(R.string.download_paused)
            VideoDownloadManager.DownloadType.IsFailed -> stringResource(R.string.download_failed)
            null, VideoDownloadManager.DownloadType.IsStopped -> null
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LiteSpacing.screenPadding)
                .clip(LiteRadii.cardShape)
                .background(LiteColors.surface)
                .liteCardBorder()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .defaultMinSize(minHeight = LiteSpacing.minTouchTarget)
                .padding(LiteSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(LiteSpacing.small),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(LiteRadii.cardShape)
                    .background(LiteColors.surfaceHighest),
            ) {
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(
                    painterResource(R.drawable.ic_baseline_play_arrow_24),
                    contentDescription = null,
                    tint = LiteColors.onSurface,
                    modifier = Modifier.align(Alignment.Center).size(32.dp).liteGlass(CircleShape).padding(4.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LiteSpacing.base)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = LiteType.rowTitle,
                        color = LiteColors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (watched) {
                        Text("✓", color = LiteColors.accent, style = LiteType.action)
                    }
                    IconButton(onClick = onLongClick) {
                        Icon(painterResource(R.drawable.ic_baseline_more_vert_24), stringResource(R.string.lite_options), tint = LiteColors.onSurface)
                    }
                    onDownload?.let { download ->
                        Box {
                            LiteDownloadIcon(episode.id, downloadStatus, onClick = {
                                if (downloadStatus == null || downloadStatus == VideoDownloadManager.DownloadType.IsStopped || downloadStatus == VideoDownloadManager.DownloadType.IsFailed) download()
                                else downloadMenu = true
                            })
                            DropdownMenu(expanded = downloadMenu, onDismissRequest = { downloadMenu = false }) {
                                downloadActions(downloadStatus).forEach { (action, label) ->
                                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                        downloadMenu = false
                                        onDownloadAction(action)
                                    })
                                }
                            }
                        }
                    }
                }
                if (description != null) {
                    Text(
                        text = description,
                        style = LiteType.description,
                        color = LiteColors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (date != null || downloadLabel != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                        date?.let {
                            Text(it, style = LiteType.metadata, color = LiteColors.onSurfaceVariant)
                        }
                        downloadLabel?.takeIf { downloadStatus != VideoDownloadManager.DownloadType.IsDownloading && downloadStatus != VideoDownloadManager.DownloadType.IsPending }?.let {
                            Text(it, style = LiteType.metadata, color = LiteColors.onSurfaceVariant)
                        }
                    }
                }
                if (progress > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(LiteSpacing.base)
                            .clip(LiteRadii.chipShape)
                            .background(LiteColors.surfaceHighest)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(LiteSpacing.base)
                                .background(LiteColors.progress)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PrimaryAction(
        label: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Button(
            onClick = onClick,
            shape = LiteRadii.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = LiteColors.accent,
                contentColor = LiteColors.onAccent,
            ),
            modifier = modifier.defaultMinSize(minHeight = 56.dp),
        ) {
            Icon(painterResource(R.drawable.ic_baseline_play_arrow_24), contentDescription = null)
            Spacer(Modifier.width(LiteSpacing.small))
            Text(text = label, style = LiteType.action, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun rememberImageRequest(url: String?, headers: Map<String, String>?): ImageRequest? {
        val context = LocalContext.current
        return remember(url, headers, context) {
            url?.takeIf { it.isNotBlank() }?.let {
                ImageRequest.Builder(context)
                    .data(it)
                    .httpHeaders(
                        NetworkHeaders.Builder().apply {
                            this["User-Agent"] = USER_AGENT
                            headers?.forEach { (key, value) -> this[key] = value }
                        }.build()
                    )
                    .build()
            }
        }
    }

    @Composable
    private fun ResultLoading(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(LiteSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap),
        ) {
            LiteSkeleton(
                modifier = Modifier
                    .width(140.dp)
                    .fillMaxWidth(0.2f)
            )
            LiteSkeleton(modifier = Modifier.fillMaxWidth(0.6f))
            LiteSkeleton(modifier = Modifier.fillMaxWidth(0.4f))
        }
    }
}

/**
 * Minimal LiveData observer. `runtime-livedata` is not on the classpath, so we
 * drive a [mutableStateOf] from [LiveData.observeForever] and detach on dispose.
 * (Mirrors the helper in the Lite Home screen.)
 */
@Composable
private fun <T> LiveData<T>.observeAsStateCompat(initial: T?): State<T?> {
    val state = remember { mutableStateOf(value ?: initial) }
    DisposableEffect(this) {
        val observer = Observer<T> { state.value = it }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}
