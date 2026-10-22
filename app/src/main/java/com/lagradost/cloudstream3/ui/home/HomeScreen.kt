package com.lagradost.cloudstream3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.lite.LiteColors
import com.lagradost.cloudstream3.ui.lite.LiteEmptyState
import com.lagradost.cloudstream3.ui.lite.LiteHeroCarousel
import com.lagradost.cloudstream3.ui.lite.LiteHeroItem
import com.lagradost.cloudstream3.ui.lite.LiteErrorState
import com.lagradost.cloudstream3.ui.lite.LitePosterCard
import com.lagradost.cloudstream3.ui.lite.LiteRadii
import com.lagradost.cloudstream3.ui.lite.LiteRow
import com.lagradost.cloudstream3.ui.lite.LiteSkeleton
import com.lagradost.cloudstream3.ui.lite.LiteSpacing
import com.lagradost.cloudstream3.ui.lite.LiteLandscapeCard
import com.lagradost.cloudstream3.ui.lite.LiteType
import com.lagradost.cloudstream3.ui.lite.LiteHeader
import com.lagradost.cloudstream3.ui.lite.openLiteSearch
import com.lagradost.cloudstream3.ui.lite.LiteGrid
import com.lagradost.cloudstream3.ui.lite.liteState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.plugins.ensureAllowedProvider
import com.lagradost.cloudstream3.plugins.ALLOWED_PLUGIN_API_NAME
import com.lagradost.cloudstream3.ui.lite.LiteWidthClass
import com.lagradost.cloudstream3.ui.lite.rememberLiteBreakpoint
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.utils.AppContextUtils.isNetworkAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.compose.Screen
import kotlin.math.min

/**
 * Lite Home screen (T3.1). Renders the EXISTING [HomeViewModel] homepage data
 * with the Fase-2 Lite components (poster cards + rows + loading/empty/error).
 *
 * Data-driven only: maps the `HomePageList` the provider already produces and
 * never invents model fields. Card click / detail navigation is intentionally
 * out of scope here (F3/F4 own it); this task is the host + navigation wiring.
 *
 * TV/emulator never reaches this screen — [HomeFragment2] proxies the legacy
 * [HomeFragment] on those layouts and only calls [Content] on phone/tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
object HomeScreen : Screen {

    @Composable
    override fun Content() {
        val viewModel = viewModel { HomeViewModel() }

        val apiName by viewModel.apiName.observeAsStateCompat(null)
        val page by viewModel.page.observeAsStateCompat(null)
        val resumeState by viewModel.resumeWatching.observeAsStateCompat(null)
        val bookmarksState by viewModel.bookmarks.observeAsStateCompat(null)
        val preview by viewModel.preview.observeHeroPreview()
        val composeView = LocalView.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var providerReady by remember { mutableStateOf<Boolean?>(null) }
        var section by remember { mutableStateOf<String?>(null) }
        var favorites by remember { mutableStateOf(DataStoreHelper.getAllFavorites()) }
        DisposableEffect(Unit) {
            val observer: (Boolean) -> Unit = { favorites = DataStoreHelper.getAllFavorites() }
            com.lagradost.cloudstream3.MainActivity.bookmarksUpdatedEvent += observer
            onDispose { com.lagradost.cloudstream3.MainActivity.bookmarksUpdatedEvent -= observer }
        }

        LaunchedEffect(Unit) {
            viewModel.reloadStored()
            providerReady = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.lagradost.cloudstream3.CommonActivity.activity?.let { ensureAllowedProvider(it) } ?: false
            }
            // Same initial load the legacy HomeFragment issues; the VM de-dupes
            // repeated plugin events (see HomeViewModel.loadAndCancel guards).
            if (providerReady == true) viewModel.loadAndCancel(ALLOWED_PLUGIN_API_NAME, forceReload = false)
        }

        val hero = if (page is Resource.Success && preview?.items.isNullOrEmpty()) {
            viewModel.heroCandidate()
        } else {
            null
        }
        var heroBackdrop by remember { mutableStateOf<LoadResponse?>(null) }
        LaunchedEffect(hero?.url) {
            heroBackdrop = hero?.url?.let { url ->
                when (val result = viewModel.loadHeroBackdrop(url)) {
                    is Resource.Success -> result.value
                    else -> null
                }
            }
        }
        val heroUi = hero?.let { candidate ->
            HeroUi(
                key = candidate.url,
                title = heroBackdrop?.name?.takeIf { it.isNotBlank() } ?: candidate.name,
                imageUrl = heroBackdrop?.backgroundPosterUrl
                    ?: heroBackdrop?.posterUrl
                    ?: candidate.posterUrl,
                metadata = heroBackdrop?.tags
                    ?.joinToString(" • ")
                    ?.takeIf { it.isNotBlank() },
                onDetail = { loadResult(candidate.url, candidate.apiName, candidate.name) },
            )
        }
        var extraPreviewAttempts by remember(apiName) { mutableStateOf(0) }
        LaunchedEffect(preview?.revision, apiName) {
            val current = preview
            if (current == null) extraPreviewAttempts = 0
            else if (current.revision > 0 && current.items.size < 5 && current.hasMore && extraPreviewAttempts < 5) {
                // Reuse the original loader, with bounded retries if a candidate fails.
                extraPreviewAttempts++
                viewModel.loadMoreHomeScrollResponses()
            }
        }
        val heroes = preview?.items.orEmpty().take(5).map { response ->
            HeroUi(
                key = response.url,
                title = response.name,
                imageUrl = response.backgroundPosterUrl ?: response.posterUrl,
                metadata = response.tags?.take(3)?.joinToString(" • "),
                onDetail = { loadResult(response.url, response.apiName, response.name) },
            )
        }.ifEmpty { listOfNotNull(heroUi) }

        HomeContent(
            page = when (providerReady) {
                null -> Resource.Loading()
                false -> Resource.Failure(false, "Provider unavailable")
                true -> page
            },
            heroes = heroes,
            resume = resumeState.orEmpty(),
            bookmarks = favorites,
            onSectionClick = { section = it },
            onResumeLongClick = { card ->
                AlertDialog.Builder(context).setTitle(card.name)
                    .setItems(arrayOf(context.getString(R.string.lite_hero_details), context.getString(R.string.delete))) { _, index ->
                        if (index == 0) loadResult(card.url, card.apiName, card.name)
                        else (card as? DataStoreHelper.ResumeWatchingResult)?.let {
                            DataStoreHelper.removeLastWatched(it.parentId)
                            viewModel.reloadStored()
                        }
                    }.show()
            },
            onResumeClick = { card ->
                viewModel.click(
                    SearchClickCallback(SEARCH_ACTION_PLAY_FILE, composeView, -1, card)
                )
            },
            onBookmarkClick = { card ->
                viewModel.click(
                    SearchClickCallback(SEARCH_ACTION_LOAD, composeView, -1, card)
                )
            },
            onProviderClick = { card ->
                viewModel.click(
                    SearchClickCallback(SEARCH_ACTION_LOAD, composeView, -1, card)
                )
            },
            onProviderLoadMore = { name -> viewModel.expand(name) },
            onRetry = {
                scope.launch {
                providerReady = null
                providerReady = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    (com.lagradost.cloudstream3.CommonActivity.activity ?: context as? android.app.Activity)?.let { ensureAllowedProvider(it) } ?: false
                }
                viewModel.loadAndCancel(
                    ALLOWED_PLUGIN_API_NAME,
                    forceReload = true,
                    fromUI = true,
                )
                }
            },
        )
        section?.let { name ->
            val row = (page as? Resource.Success)?.value?.get(name)
            ModalBottomSheet(onDismissRequest = { section = null }, containerColor = LiteColors.background,
                sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified) {
                Text(name, style = LiteType.rowTitle, modifier = Modifier.padding(LiteSpacing.screenPadding))
                LiteGrid(row?.list?.list.orEmpty(), onClick = { loadResult(it.url, it.apiName, it.name) },
                    modifier = Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.8f).dp),
                    hasNext = row?.hasNext == true, loading = row?.loading == true, error = row?.error == true,
                    pageKey = row?.currentPage ?: 0, onLoadMore = { viewModel.expand(name) })
            }
        }
    }

    @Composable
    private fun HomeContent(
        page: Resource<Map<String, HomeViewModel.ExpandableHomepageList>>?,
        heroes: List<HeroUi>,
        resume: List<SearchResponse>,
        bookmarks: List<SearchResponse>,
        onResumeClick: (SearchResponse) -> Unit,
        onBookmarkClick: (SearchResponse) -> Unit,
        onProviderClick: (SearchResponse) -> Unit,
        onProviderLoadMore: (String) -> Unit,
        onSectionClick: (String) -> Unit,
        onResumeLongClick: (SearchResponse) -> Unit,
        onRetry: () -> Unit,
    ) {
        val metrics = rememberHomeMetrics()
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LiteColors.background)
        ) {
            val view = LocalView.current
            LiteHeader(stringResource(R.string.app_name), { openLiteSearch(view) })

            val current = page
            when (current) {
                null, is Resource.Loading -> LiteHomeLoading(metrics)
                is Resource.Failure -> {
                    // Legacy parity (HomeFragment.kt:906-910): distinguish a real
                    // offline case from a provider failure and never surface the raw
                    // errorString / stack trace to the user.
                    val offline = !LocalContext.current.isNetworkAvailable()
                    LiteErrorState(
                        message = stringResource(
                            if (offline) R.string.no_internet_connection else R.string.error
                        ),
                        onRetry = onRetry,
                    )
                }

                is Resource.Success -> {
                    // Partial state: this per-row guard hides empty/failed rows while
                    // the rows that loaded render normally (one bad row never blocks
                    // the screen). Row-level retry lives in the T3.4 pagination path.
                    val rows = current.value.entries.filter { it.value.list.list.isNotEmpty() }
                    if (rows.isEmpty() && resume.isEmpty() && bookmarks.isEmpty()) {
                        LiteEmptyState(title = stringResource(R.string.no_data))
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = LiteSpacing.rowSpacing),
                            verticalArrangement = Arrangement.spacedBy(LiteSpacing.rowSpacing),
                        ) {
                            if (heroes.isNotEmpty()) {
                                item(key = "lite_hero") {
                                    LiteHeroCarousel(
                                        items = heroes.map { LiteHeroItem(it.key, it.title, it.imageUrl, it.metadata) },
                                        onDetail = { selected -> heroes.firstOrNull { it.key == selected.key }?.onDetail?.invoke() },
                                        fixedHeight = metrics.heroHeight,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            if (resume.isNotEmpty()) {
                                item(key = "lite_continue_watching") {
                                    LiteRow(
                                        title = stringResource(R.string.continue_watching),
                                        items = resume,
                                        key = { it.url },
                                    ) { card ->
                                        LiteLandscapeCard(
                                            imageUrl = card.posterUrl,
                                            title = card.name,
                                            subtitle = (card as? DataStoreHelper.ResumeWatchingResult)?.let {
                                                if ((it.episode ?: 0) > 0) context.getNameFull(null, it.episode, it.season) else null
                                            },
                                            progress = resumeProgress(card),
                                            modifier = Modifier.width(metrics.landscapeWidth),
                                            onClick = { onResumeClick(card) },
                                            onLongClick = { onResumeLongClick(card) },
                                        )
                                    }
                                }
                            }
                            if (bookmarks.isNotEmpty()) {
                                item(key = "lite_bookmarks") {
                                    LiteRow(
                                        title = stringResource(R.string.favorites_list_name),
                                        items = bookmarks,
                                        key = { it.url },
                                    ) { card ->
                                        LitePosterCard(
                                            imageUrl = card.posterUrl,
                                            contentDescription = card.name,
                                            modifier = Modifier.width(metrics.posterWidth),
                                            onClick = { onBookmarkClick(card) },
                                        )
                                    }
                                }
                            }
                            items(rows, key = { it.key }) { (name, expand) ->
                                LiteRow(
                                    title = name,
                                    items = expand.list.list,
                                    key = { it.url },
                                    onTitleClick = { onSectionClick(name) },
                                    loading = expand.loading,
                                    error = expand.error,
                                    pageKey = expand.currentPage,
                                    onLoadMore = if (expand.hasNext) {
                                        { onProviderLoadMore(name) }
                                    } else null,
                                ) { item ->
                                    LitePosterCard(
                                        imageUrl = item.posterUrl,
                                        contentDescription = item.name,
                                        modifier = Modifier.width(metrics.posterWidth),
                                        onClick = { onProviderClick(item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Three skeleton rows while the homepage loads (no layout jank on arrival). */
    @Composable
    private fun LiteHomeLoading(metrics: HomeMetrics) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = LiteSpacing.rowSpacing),
            verticalArrangement = Arrangement.spacedBy(LiteSpacing.rowSpacing),
        ) {
            LiteSkeleton(Modifier.fillMaxWidth().height(metrics.heroHeight), shape = androidx.compose.ui.graphics.RectangleShape)
            repeat(3) {
                Column(verticalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                    LiteSkeleton(
                        modifier = Modifier
                            .padding(horizontal = metrics.screenPadding)
                            .width(140.dp)
                            .height(LiteSpacing.rowTitleHeight),
                        shape = LiteRadii.buttonShape,
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = metrics.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap),
                    ) {
                        repeat(5) {
                            LiteSkeleton(
                                modifier = Modifier
                                    .width(metrics.posterWidth)
                                    .aspectRatio(2f / 3f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class HomeMetrics(
    val screenPadding: Dp,
    val posterWidth: Dp,
    val landscapeWidth: Dp,
    val heroHeight: Dp,
)

/**
 * Lite Home adaptation (spec §3.11/§7): card widths, screen padding and a hero
 * height cap derived from the window width class + orientation. Replaces the old
 * fixed 120/200dp cards so phones and tablets (portrait + landscape) both fit
 * without clipping; the hero cap keeps a 16:9 hero from swallowing the landscape.
 */
@Composable
private fun rememberHomeMetrics(): HomeMetrics {
    val bp = rememberLiteBreakpoint()
    val config = LocalConfiguration.current
    return remember(bp, config.screenWidthDp, config.screenHeightDp) {
        val padding = when (bp.widthClass) {
            LiteWidthClass.Compact -> 16
            LiteWidthClass.Medium -> 22
            LiteWidthClass.Expanded -> 28
        }
        val posterWidth = when (bp.widthClass) {
            LiteWidthClass.Compact -> 120
            LiteWidthClass.Medium -> 130
            LiteWidthClass.Expanded -> 140
        }
        val landscapeWidth = when (bp.widthClass) {
            LiteWidthClass.Compact -> 178
            LiteWidthClass.Medium -> 213
            LiteWidthClass.Expanded -> 213
        }
        val heroFraction = when {
            bp.widthClass == LiteWidthClass.Compact && bp.isLandscape -> 0.55f
            bp.widthClass == LiteWidthClass.Compact -> 0.45f
            bp.widthClass == LiteWidthClass.Medium -> 0.40f
            else -> 0.38f
        }
        val heroByWidth = (config.screenWidthDp - padding * 2) * 9f / 16f
        val heroByHeight = config.screenHeightDp * heroFraction
        HomeMetrics(
            screenPadding = padding.dp,
            posterWidth = posterWidth.dp,
            landscapeWidth = landscapeWidth.dp,
            heroHeight = min(heroByWidth, heroByHeight).coerceAtLeast(if (bp.isLandscape) 180f else 240f).dp,
        )
    }
}

private fun resumeProgress(card: SearchResponse): Float? {
    val pos = (card as? DataStoreHelper.ResumeWatchingResult)?.watchPos ?: return null
    if (pos.duration <= 0L) return null
    return (pos.position.toFloat() / pos.duration.toFloat()).coerceIn(0f, 1f)
}

private data class HeroUi(
    val key: String,
    val title: String,
    val imageUrl: String?,
    val metadata: String?,
    val onDetail: () -> Unit,
)

/** Copy the mutable legacy preview list at each emission so appends invalidate Compose. */
private data class HeroPreview(val hasMore: Boolean, val items: List<LoadResponse>, val revision: Int)

@Composable
private fun LiveData<Resource<Pair<Boolean, List<LoadResponse>>>>.observeHeroPreview(): State<HeroPreview?> {
    fun snapshot(resource: Resource<Pair<Boolean, List<LoadResponse>>>?, revision: Int) =
        (resource as? Resource.Success)?.value?.let { HeroPreview(it.first, it.second.toList(), revision) }
    val state = remember(this) { mutableStateOf(snapshot(value, 0)) }
    DisposableEffect(this) {
        var revision = 0
        val observer = Observer<Resource<Pair<Boolean, List<LoadResponse>>>> { state.value = snapshot(it, ++revision) }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}

/**
 * Minimal LiveData observer. `runtime-livedata` is not on the classpath, so we
 * drive a [mutableStateOf] from [LiveData.observeForever] and detach on dispose.
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
