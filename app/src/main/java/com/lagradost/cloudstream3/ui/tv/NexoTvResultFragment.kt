package com.lagradost.cloudstream3.ui.tv

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.NexoTvResultBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.result.*
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/** Presentation only: original episode payloads, autostart and player actions remain in ResultViewModel2. */
class NexoTvResultFragment : Fragment(R.layout.nexo_tv_result) {
    private lateinit var model: ResultViewModel2
    private var binding: NexoTvResultBinding? = null
    private var episodes: NexoTvEpisodeAdapter? = null
    private var recommendations: NexoTvCardAdapter? = null
    private var releaseRail: (() -> Unit)? = null
    private var popup: AlertDialog? = null
    private var loading: AlertDialog? = null
    private var selection: AlertDialog? = null
    private var focusId = View.NO_ID
    private var focusKey: String? = null
    private var focusArea = "action"
    private var focusIndex = 0
    private var pendingFocus = true
    private var comingSoon = false
    private var stored: ResultFragment.StoredData? = null
    private val update: (Int?) -> Unit = { model.reloadEpisodes() }
    private val library: (Boolean) -> Unit = { model.refreshFavorite() }
    private val plugins: (Boolean) -> Unit = { if (!model.hasLoaded()) load(false) }
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, next ->
        val root = binding?.root
        if (!pendingFocus && next != null && root?.findViewById<View>(next.id) === next) {
            if (next.tag is String) {
                focusKey = next.tag as String
                focusArea = if (episodes?.currentList?.any { it.tvEpisodeKey == focusKey } == true) "episode" else "recommendation"
            } else { focusArea = "action"; focusId = next.id; focusKey = null }
            releaseRail?.invoke()
            releaseRail = (activity as? MainActivity)?.let { bindNexoTvRail(it, next.id) }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        model = ViewModelProvider(this)[ResultViewModel2::class.java]
        model.EPISODE_RANGE_SIZE = 50
        state?.let { focusId = it.getInt("focus", View.NO_ID); focusKey = it.getString("key"); focusArea = it.getString("area") ?: "action"; focusIndex = it.getInt("index") }
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        val ui = NexoTvResultBinding.bind(view)
        binding = ui; pendingFocus = true
        stored = getStoredData() ?: return
        ui.root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        ui.nexoTvResultRetry.setOnClickListener { load(false) }
        ui.nexoTvResultFavorite.setOnClickListener { model.toggleFavoriteStatus(context) }
        episodes = NexoTvEpisodeAdapter(
            { model.handleAction(EpisodeClickEvent(ACTION_CLICK_DEFAULT, it)) },
            { model.handleAction(EpisodeClickEvent(ACTION_SHOW_OPTIONS, it)) },
            { ep, _ -> focusIndex = episodes?.currentList?.indexOf(ep) ?: 0 })
        ui.nexoTvResultEpisodes.setLinearListLayout(nextLeft = R.id.nav_rail_view, nextRight = FOCUS_SELF)
        ui.nexoTvResultEpisodes.adapter = episodes
        recommendations = NexoTvCardAdapter(false,
            { loadResult(it.url, it.apiName, it.name) }, { false },
            { item, _ -> focusIndex = recommendations?.currentList?.indexOf(item) ?: 0 })
        ui.nexoTvResultRecommendations.setLinearListLayout(nextLeft = R.id.nav_rail_view, nextRight = FOCUS_SELF)
        ui.nexoTvResultRecommendations.adapter = recommendations
        model.page.observe(viewLifecycleOwner) { page ->
            when (page) {
                is Resource.Success -> {
                    val data = page.value
                    comingSoon = data.comingSoon
                    ui.nexoTvResultStatus.isVisible = false
                    ui.nexoTvResultTitle.text = data.title
                    ui.nexoTvResultMeta.text = listOfNotNull(data.yearText, data.typeText, data.durationText, data.ratingText, data.contentRatingText, data.onGoingText, data.nextAiringEpisode).joinToString("   ") { it.asString(requireContext()) }
                    ui.nexoTvResultPlot.text = data.plotText.asString(requireContext())
                    ui.nexoTvResultPlot.post {
                        if (binding === ui) {
                            val layout = ui.nexoTvResultPlot.layout
                            ui.nexoTvResultMore.isVisible = layout != null && (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
                        }
                    }
                    ui.nexoTvResultMore.setOnClickListener {
                        selection?.dismiss()
                        selection = AlertDialog.Builder(requireContext()).setTitle(data.title).setMessage(data.plotText.asString(requireContext())).setPositiveButton(android.R.string.ok, null).show()
                    }
                    ui.nexoTvResultBackdrop.loadImage(data.backgroundPosterUrl ?: data.posterBackgroundImage ?: data.posterImage, data.posterHeaders)
                    refreshPlay(); restoreFocus()
                }
                is Resource.Failure -> {
                    ui.nexoTvResultStatus.isVisible = true
                    ui.nexoTvResultMessage.text = page.errorString
                    ui.nexoTvResultRetry.isVisible = true
                    if (pendingFocus) ui.nexoTvResultRetry.requestFocus()
                }
                else -> { ui.nexoTvResultStatus.isVisible = true; ui.nexoTvResultRetry.isVisible = false }
            }
        }
        model.movie.observe(viewLifecycleOwner) { refreshPlay() }
        model.resumeWatching.observe(viewLifecycleOwner) { refreshPlay() }
        model.episodes.observe(viewLifecycleOwner) { resource ->
            if (resource is Resource.Success) {
                episodes?.submitList(resource.value) { restoreFocus() }
                ui.nexoTvResultEpisodes.isVisible = resource.value.isNotEmpty() && !comingSoon
                ui.nexoTvResultEpisodeStatus.text = if (resource.value.isEmpty()) model.page.value.let { (it as? Resource.Success)?.value?.noEpisodesFoundText?.asString(requireContext()) } else getString(R.string.episodes)
            }
            refreshPlay()
        }
        model.favoriteStatus.observe(viewLifecycleOwner) {
            ui.nexoTvResultFavorite.text = getString(R.string.favorite) + if (it == true) " \u2665" else " \u2661"
            ui.nexoTvResultFavorite.isEnabled = it != null
        }
        model.seasonSelections.observe(viewLifecycleOwner) { values ->
            selector(ui.nexoTvResultSeason, values.map { it.first?.asString(requireContext()).orEmpty() }, model.selectedSeasonIndex.value ?: 0) { model.changeSeason(values[it].second) }
        }
        model.selectedSeasonIndex.observe(viewLifecycleOwner) { refreshSelectionLabels() }
        model.rangeSelections.observe(viewLifecycleOwner) { values ->
            selector(ui.nexoTvResultRange, values.map { it.first?.asString(requireContext()).orEmpty() }, model.selectedRangeIndex.value ?: 0) { model.changeRange(values[it].second) }
        }
        model.selectedRangeIndex.observe(viewLifecycleOwner) { refreshSelectionLabels() }
        model.dubSubSelections.observe(viewLifecycleOwner) { values ->
            selector(ui.nexoTvResultDub, values.map { it.first?.asString(requireContext()).orEmpty() }, model.selectedDubStatusIndex.value ?: 0) { model.changeDubStatus(values[it].second) }
        }
        model.selectedDubStatusIndex.observe(viewLifecycleOwner) { refreshSelectionLabels() }
        model.recommendations.observe(viewLifecycleOwner) { items ->
            recommendations?.submitList(items) { restoreFocus() }
            ui.nexoTvResultRecommendations.isVisible = items.isNotEmpty()
            ui.nexoTvResultRecommendationTitle.isVisible = items.isNotEmpty()
        }
        model.selectPopup.observe(viewLifecycleOwner) { value ->
            popup?.dismiss(); popup = null
            if (value != null) popup = AlertDialog.Builder(requireContext()).setTitle(value.getTitle(requireContext()))
                .setItems(value.getOptions(requireContext()).toTypedArray()) { _, which -> value.callback(which) }
                .setOnCancelListener { value.callback(null) }.show()
        }
        model.loadedLinks.observe(viewLifecycleOwner) { value ->
            if (value == null) { loading?.dismiss(); loading = null }
            else {
                if (loading == null) loading = AlertDialog.Builder(requireContext()).setTitle(R.string.loading)
                    .setNegativeButton(R.string.cancel) { _, _ -> model.cancelLinks() }
                    .setPositiveButton(R.string.skip_loading, null).setOnCancelListener { model.cancelLinks() }.show().also { dialog ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { model.skipLoading() }
                    }
                loading?.getButton(AlertDialog.BUTTON_POSITIVE)?.isVisible = value.linksLoaded > 0
            }
        }
        model.episodeSynopsis.observe(viewLifecycleOwner) { text ->
            if (text != null) {
                selection?.dismiss()
                selection = AlertDialog.Builder(requireContext()).setMessage(text).setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener { model.releaseEpisodeSynopsis() }.show()
            }
        }
        ResultFragment.updateUIEvent += update
        MainActivity.reloadLibraryEvent += library
        if (stored?.restart == true || !model.hasLoaded()) load(true)
    }
    private fun load(autostart: Boolean) {
        val data = stored ?: return
        model.load(activity, data.url, data.apiName, data.showFillers, data.dubStatus, if (autostart) data.start else null)
        // Arguments were consumed by getStoredData. Never keep a second autostart for retry/plugin events.
        stored = data.copy(start = null, restart = false)
    }
    private fun refreshPlay() {
        val ui = binding ?: return
        val movie = (model.movie.value as? Resource.Success)?.value?.second
        val eps = (model.episodes.value as? Resource.Success)?.value.orEmpty()
        val last = eps.indexOfLast { it.getWatchProgress() >= NEXT_WATCH_EPISODE_PERCENTAGE / 100.0f || it.videoWatchState == VideoWatchState.Watched }
        val resume = model.resumeWatching.value
        val target = resume?.result ?: movie ?: eps.getOrElse(last + 1) { eps.firstOrNull() }
        ui.nexoTvResultPlay.isVisible = !comingSoon && target != null
        ui.nexoTvResultPlay.text = getString(if (resume != null) R.string.resume else R.string.lite_play)
        ui.nexoTvResultPlay.setOnClickListener { target?.let { model.handleAction(EpisodeClickEvent(if (resume != null) stored?.playerAction ?: ACTION_CLICK_DEFAULT else ACTION_CLICK_DEFAULT, it)) } }
        ui.nexoTvResultPlay.setOnLongClickListener { target?.let { model.handleAction(EpisodeClickEvent(ACTION_SHOW_OPTIONS, it)) }; true }
        restoreFocus()
    }
    private fun selector(view: TextView, labels: List<String>, selected: Int, changed: (Int) -> Unit) {
        view.isVisible = labels.size > 1
        view.text = labels.getOrNull(selected)
        view.setOnClickListener {
            selection?.dismiss()
            selection = AlertDialog.Builder(requireContext()).setTitle(view.text).setSingleChoiceItems(labels.toTypedArray(), labels.indexOf(view.text.toString()).coerceAtLeast(0)) { dialog, index -> changed(index); dialog.dismiss() }.show()
        }
    }
    private fun refreshSelectionLabels() {
        val ui = binding ?: return
        ui.nexoTvResultSeason.text = model.seasonSelections.value?.getOrNull(model.selectedSeasonIndex.value ?: 0)?.first?.asString(requireContext())
        ui.nexoTvResultRange.text = model.rangeSelections.value?.getOrNull(model.selectedRangeIndex.value ?: 0)?.first?.asString(requireContext())
        ui.nexoTvResultDub.text = model.dubSubSelections.value?.getOrNull(model.selectedDubStatusIndex.value ?: 0)?.first?.asString(requireContext())
    }
    private fun restoreFocus() {
        val ui = binding ?: return
        if (!pendingFocus || model.page.value !is Resource.Success || !isResumed) return
        if (focusId == View.NO_ID && !comingSoon && model.movie.value !is Resource.Success && model.episodes.value !is Resource.Success) return
        ui.root.post {
            if (binding !== ui || !pendingFocus || !isResumed) return@post
            if (focusArea != "action") {
                val list = if (focusArea == "episode") ui.nexoTvResultEpisodes else ui.nexoTvResultRecommendations
                val keys = if (focusArea == "episode") episodes?.currentList?.map { it.tvEpisodeKey }.orEmpty() else recommendations?.currentList?.map { it.tvItemKey }.orEmpty()
                val index = resolveTvFocusIndex(keys, focusKey?.let { TvFocusAnchor(focusArea, it, focusIndex) })
                if (index != null) {
                    val target = list.findViewHolderForAdapterPosition(index)?.itemView
                    if (target == null) { list.scrollToPosition(index); list.doOnNextLayout { if (binding === ui) restoreFocus() } }
                    else { pendingFocus = false; target.requestFocus(); releaseRail?.invoke(); releaseRail = (activity as? MainActivity)?.let { bindNexoTvRail(it, target.id) } }
                    return@post
                }
            }
            val target = ui.root.findViewById<View>(focusId)?.takeIf { it.isShown && it.isFocusable }
                ?: ui.nexoTvResultPlay.takeIf { it.isShown } ?: ui.nexoTvResultFavorite
            pendingFocus = false; target.requestFocus()
            releaseRail?.invoke(); releaseRail = (activity as? MainActivity)?.let { bindNexoTvRail(it, target.id) }
        }
    }
    override fun onResume() { super.onResume(); MainActivity.afterPluginsLoadedEvent += plugins; restoreFocus() }
    override fun onStop() { MainActivity.afterPluginsLoadedEvent -= plugins; releaseRail?.invoke(); releaseRail = null; super.onStop() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("focus", focusId); out.putString("key", focusKey); out.putString("area", focusArea); out.putInt("index", focusIndex)
        super.onSaveInstanceState(out)
    }
    override fun onDestroyView() {
        binding?.root?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusListener)
        ResultFragment.updateUIEvent -= update; MainActivity.reloadLibraryEvent -= library
        releaseRail?.invoke(); releaseRail = null
        popup?.dismiss(); popup = null; loading?.dismiss(); loading = null; selection?.dismiss(); selection = null
        binding?.nexoTvResultBackdrop?.dispose()
        binding?.nexoTvResultEpisodes?.adapter = null; binding?.nexoTvResultRecommendations?.adapter = null
        episodes = null; recommendations = null; binding = null; pendingFocus = true
        super.onDestroyView()
    }
}
