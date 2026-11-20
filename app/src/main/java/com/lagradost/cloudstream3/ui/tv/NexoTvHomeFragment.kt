package com.lagradost.cloudstream3.ui.tv

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.databinding.NexoTvHomeBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.ALLOWED_PLUGIN_API_NAME
import com.lagradost.cloudstream3.plugins.ensureAllowedProvider
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A TV presentation layer over the existing catalog, library and playback contracts. */
class NexoTvHomeFragment : Fragment(R.layout.nexo_tv_home) {
    private val model: HomeViewModel by activityViewModels()
    private var binding: NexoTvHomeBinding? = null
    private var rows: NexoTvHomeAdapter? = null
    private val memory = mutableMapOf<String, TvFocusAnchor>()
    private val rowScroll = mutableMapOf<String, Parcelable>()
    private var masterScroll: Parcelable? = null
    private var anchor: TvFocusAnchor? = null
    private var candidate: TvFocusAnchor? = null
    private var restoring = true
    private var pendingRestore = true
    private var releaseRail: (() -> Unit)? = null
    private var favorites: List<SearchResponse> = emptyList()
    private var favoritesJob: Job? = null
    private var providerReady: Boolean? = null
    private var preparing: Job? = null
    private var heroes: List<TvHero> = emptyList()
    private var heroIndex = 0
    private var extraPreviewAttempts = 0
    private val libraryChanged: (Boolean) -> Unit = { refreshFavorites() }
    private val accountChanged: (Boolean) -> Unit = { model.reloadStored(); refreshFavorites() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            anchor = state.getBundle("anchor")?.decodeAnchor()
            heroIndex = state.getInt("hero")
            state.getBundle("memory")?.let { saved -> saved.keySet().forEach { key -> saved.getBundle(key)?.decodeAnchor()?.let { memory[key] = it } } }
            @Suppress("DEPRECATION")
            masterScroll = state.getParcelable("master")
            state.getBundle("scroll")?.let { saved -> saved.keySet().forEach { key ->
                @Suppress("DEPRECATION")
                saved.getParcelable<Parcelable>(key)?.let { rowScroll[key] = it }
            } }
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = NexoTvHomeBinding.bind(view)
        binding = ui
        restoring = true; pendingRestore = true
        cancelNexoTvPendingFocus(ui.root, ui.nexoTvHomeRetry) { restoring = false; pendingRestore = false }
        val adapter = NexoTvHomeAdapter(memory, rowScroll,
            { key, item, clicked ->
                if (key != "@resume") restoring = true
                if (key == "@resume") model.click(SearchClickCallback(SEARCH_ACTION_PLAY_FILE, clicked, -1, item))
                else loadResult(item.url, item.apiName, item.name)
            }, { key, item ->
                if (key != "@resume") false else {
                    AlertDialog.Builder(requireContext()).setTitle(item.name)
                        .setItems(arrayOf(getString(R.string.lite_hero_details), getString(R.string.delete))) { _, index ->
                            if (index == 0) { restoring = true; loadResult(item.url, item.apiName, item.name) }
                            else (item as? DataStoreHelper.ResumeWatchingResult)?.let { DataStoreHelper.removeLastWatched(it.parentId); model.reloadStored() }
                        }.show()
                    true
                }
            }, { name ->
                if (parentFragmentManager.findFragmentByTag("nexo-tv-section") == null)
                    NexoTvSectionDialog().apply { arguments = Bundle().apply { putString("nexo_tv_section_key", name) } }.show(parentFragmentManager, "nexo-tv-section")
            }, ::focused, ::advanceHero)
        rows = adapter
        ui.nexoTvHomeList.apply {
            itemAnimator = null
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            layoutManager?.onRestoreInstanceState(masterScroll)
        }
        ui.nexoTvHomeRetry.setOnClickListener { prepareProvider(true) }
        model.page.observe(viewLifecycleOwner) { render() }
        model.resumeWatching.observe(viewLifecycleOwner) { render() }
        model.preview.observe(viewLifecycleOwner) { value ->
            if (value is Resource.Success && value.value.second.size < 5 && value.value.first && extraPreviewAttempts < 5) {
                extraPreviewAttempts++; model.loadMoreHomeScrollResponses()
            }
            render()
        }
        MainActivity.bookmarksUpdatedEvent += libraryChanged
        MainActivity.reloadAccountEvent += accountChanged
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding?.root?.hasFocus() == true) requireActivity().findViewById<View>(R.id.navigation_home)?.requestFocus()
                else { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        model.reloadStored()
        refreshFavorites()
        prepareProvider(false)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(6_000)
                    val list = binding?.nexoTvHomeList ?: continue
                    val hero = list.findViewHolderForAdapterPosition(0)?.itemView ?: continue
                    if (rows?.currentList?.firstOrNull()?.hero != null &&
                        !list.hasFocus() && !hero.hasFocus() && hero.isShown &&
                        hero.getLocalVisibleRect(android.graphics.Rect()) &&
                        android.os.Build.VERSION.SDK_INT >= 26 && ValueAnimator.areAnimatorsEnabled() &&
                        parentFragmentManager.findFragmentByTag("nexo-tv-section") == null
                    ) advanceHero(1)
                }
            }
        }
    }
    private fun advanceHero(direction: Int): Boolean {
        if (heroes.size < 2) return false
        heroIndex = (heroIndex + direction + heroes.size) % heroes.size
        val item = heroes[heroIndex].item
        if (anchor?.sectionKey == "@hero") {
            anchor = TvFocusAnchor("@hero", item.tvItemKey, 0)
            memory["@hero"] = checkNotNull(anchor)
        }
        render()
        return true
    }

    private fun prepareProvider(force: Boolean) {
        if (preparing?.isActive == true) return
        providerReady = null; render()
        preparing = viewLifecycleOwner.lifecycleScope.launch {
            val host = requireActivity()
            val ready = withContext(Dispatchers.IO) { ensureAllowedProvider(host) }
            providerReady = ready || APIHolder.getApiFromNameNull(ALLOWED_PLUGIN_API_NAME) != null
            if (providerReady == true) model.loadAndCancel(ALLOWED_PLUGIN_API_NAME, forceReload = force)
            render()
        }
    }
    private fun refreshFavorites() {
        if (view == null) return
        favoritesJob?.cancel()
        favoritesJob = viewLifecycleOwner.lifecycleScope.launch {
            favorites = withContext(Dispatchers.IO) { DataStoreHelper.getAllFavorites().sortedByDescending { it.favoritesTime } }
            render()
        }
    }
    private fun render() {
        val ui = binding ?: return
        val page = model.page.value
        if (page is Resource.Success && APIHolder.getApiFromNameNull(ALLOWED_PLUGIN_API_NAME) != null) providerReady = true
        val catalog = (page as? Resource.Success)?.value.orEmpty()
        val itemsByKey = catalog.values.flatMap { it.list.list }.associateBy { it.tvItemKey }
        val preview = (model.preview.value as? Resource.Success)?.value?.second.orEmpty()
        val selectedHeroKey = heroes.getOrNull(heroIndex)?.item?.tvItemKey ?: anchor?.takeIf { it.sectionKey == "@hero" }?.itemKey
        heroes = preview.mapNotNull { response ->
            itemsByKey["${response.apiName}\u0000${response.url}"]?.let { TvHero(it, response.backgroundPosterUrl ?: response.posterUrl ?: it.posterUrl, response.plot, response.backgroundPosterUrl == null,
                listOfNotNull(
                    when (response.type) {
                        TvType.Movie -> getString(R.string.movies_singular)
                        TvType.TvSeries, TvType.AsianDrama -> getString(R.string.tv_series_singular)
                        TvType.Anime, TvType.AnimeMovie, TvType.OVA -> getString(R.string.anime_singular)
                        else -> null
                    },
                    response.year?.takeIf { it > 0 }?.toString(),
                    response.tags?.firstOrNull { it.isNotBlank() }).joinToString(" · ")) }
        }.distinctBy { it.item.tvItemKey }.take(5).ifEmpty { listOfNotNull(model.heroCandidate()?.let { TvHero(it, it.posterUrl, null, true) }) }
        heroIndex = heroes.indexOfFirst { it.item.tvItemKey == selectedHeroKey }.takeIf { it >= 0 }
            ?: heroIndex.coerceIn(0, heroes.lastIndex.coerceAtLeast(0))
        val content = buildList {
            heroes.getOrNull(heroIndex)?.let { add(TvHomeRow("@hero", "", emptyList(), hero = it, heroIndex = heroIndex, heroCount = heroes.size)) }
            model.resumeWatching.value?.takeIf { it.isNotEmpty() }?.let { add(TvHomeRow("@resume", getString(R.string.continue_watching), it)) }
            favorites.takeIf { it.isNotEmpty() }?.let { add(TvHomeRow("@favorites", getString(R.string.favorites_list_name), it)) }
            catalog.forEach { (name, data) -> if (data.list.list.isNotEmpty()) add(TvHomeRow(name, name, data.list.list.toList(), true, data.hasNext, data.loading, data.error)) }
        }
        val loading = providerReady == null || (providerReady == true && (page == null || page is Resource.Loading))
        val empty = content.isEmpty() || providerReady != true || page is Resource.Failure
        ui.nexoTvHomeStatus.isVisible = empty
        ui.nexoTvHomeList.isVisible = !empty
        ui.nexoTvHomeSkeleton.isVisible = loading && empty
        ui.nexoTvHomeMessage.setText(when {
            providerReady == null -> R.string.nexo_tv_home_preparing
            loading -> R.string.loading
            providerReady == false || page is Resource.Failure -> R.string.nexo_tv_home_error
            else -> R.string.no_data
        })
        ui.nexoTvHomeRetry.isVisible = !loading
        rows?.submitList(content) {
            if (binding !== ui) return@submitList
            if (!empty) ui.nexoTvHomeList.post {
                if (binding === ui && pendingRestore && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) restoreFocus()
            } else if (!loading && pendingRestore) ui.nexoTvHomeRetry.post {
                if (binding !== ui) return@post
                if (pendingRestore && ui.root.findFocus() == null) ui.nexoTvHomeRetry.requestFocus()
                bindEntry(ui.nexoTvHomeRetry)
            }
        }
    }
    private fun focused(saved: TvFocusAnchor, view: View) {
        if (restoring) { candidate = saved; return }
        anchor = saved
        if (saved.itemKey !in listOf("@title", "@retry")) memory[saved.sectionKey] = saved
        bindEntry(view)
    }
    private fun bindEntry(view: View) {
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        releaseRail?.invoke()
        releaseRail = (activity as? MainActivity)?.let { bindNexoTvRail(it, view.id) }
    }
    private fun restoreFocus() {
        val ui = binding ?: return
        if (ui.nexoTvHomeStatus.isVisible || !pendingRestore) return
        val focus = requireActivity().currentFocus
        if (anchor == null && focus?.id in listOf(R.id.navigation_search, R.id.navigation_library, R.id.navigation_settings)) {
            pendingRestore = false; restoring = false; bindEntry(ui.nexoTvHomeList); return
        }
        rows?.restore(anchor, { pendingRestore }) {
            pendingRestore = false; restoring = false
            candidate?.let { anchor = it; if (it.itemKey !in listOf("@title", "@retry")) memory[it.sectionKey] = it }
            ui.root.findFocus()?.let(::bindEntry)
        }
    }
    internal fun focusSectionMore(key: String) {
        anchor = TvFocusAnchor(key, "@more", 16)
        restoring = true; pendingRestore = true
        binding?.root?.post { restoreFocus() }
    }
    override fun onStart() { super.onStart(); binding?.nexoTvHomeList?.let(::bindEntry); binding?.root?.post { if(pendingRestore) restoreFocus() } }
    override fun onStop() { restoring = true; pendingRestore = true; releaseRail?.invoke(); releaseRail = null; super.onStop() }
    private fun saveScroll() { rows?.saveScroll(); binding?.nexoTvHomeList?.layoutManager?.onSaveInstanceState()?.let { masterScroll = it } }
    override fun onSaveInstanceState(outState: Bundle) {
        saveScroll()
        outState.putParcelable("master", masterScroll)
        outState.putInt("hero", heroIndex)
        anchor?.let { outState.putBundle("anchor", it.encode()) }
        outState.putBundle("memory", Bundle().apply { memory.forEach { (key,value) -> putBundle(key,value.encode()) } })
        outState.putBundle("scroll", Bundle().apply { rowScroll.forEach { (key,value) -> putParcelable(key,value) } })
        super.onSaveInstanceState(outState)
    }
    override fun onDestroyView() {
        restoring = true
        saveScroll()
        MainActivity.bookmarksUpdatedEvent -= libraryChanged
        MainActivity.reloadAccountEvent -= accountChanged
        releaseRail?.invoke(); releaseRail = null
        binding?.nexoTvHomeList?.adapter = null
        rows = null; binding = null
        super.onDestroyView()
    }
    private fun TvFocusAnchor.encode() = Bundle().apply { putString("section",sectionKey); putString("item",itemKey); putInt("index",fallbackIndex) }
    private fun Bundle.decodeAnchor() = TvFocusAnchor(getString("section").orEmpty(),getString("item").orEmpty(),getInt("index"))
}
