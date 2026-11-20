package com.lagradost.cloudstream3.ui.tv

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.LinearLayout
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.lagradost.cloudstream3.utils.AppContextUtils.isCastApiAvailable
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.lagradost.cloudstream3.utils.UiText
import androidx.core.view.isVisible
import androidx.core.view.children
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.NexoTvResultBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.result.*
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import java.text.NumberFormat
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.populateChips

/** Presentation only: original episode payloads, autostart and player actions remain in ResultViewModel2. */
class NexoTvResultFragment : Fragment(R.layout.nexo_tv_result) {
    private lateinit var model: ResultViewModel2
    private var binding: NexoTvResultBinding? = null
    private var episodes: NexoTvEpisodeAdapter? = null
    private var recommendations: NexoTvCardAdapter? = null
    private var actors: NexoTvActorAdapter? = null
    private var releaseRail: (() -> Unit)? = null
    private var popup: AlertDialog? = null
    private var loading: AlertDialog? = null
    private var selection: AlertDialog? = null
    private var focusId = View.NO_ID
    private var focusKey: String? = null
    private var focusArea = "action"
    private var focusIndex = 0
    private var playbackOrigin: TvFocusAnchor? = null
    private var pendingFocus = true
    private var comingSoon = false
    private var secondaryTarget: ResultEpisode? = null
    private var castRequested = false
    private var castSetupFailed = false
    private var stored: ResultFragment.StoredData? = null
    private val update: (Int?) -> Unit = { model.reloadEpisodes() }
    private val library: (Boolean) -> Unit = { model.refreshFavorite() }
    private val plugins: (Boolean) -> Unit = { if (!model.hasLoaded()) load(false) }
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, next ->
        val root = binding?.root
        if (!pendingFocus && isResumed && parentFragmentManager.primaryNavigationFragment === this &&
            next != null && root?.findViewById<View>(next.id) === next) {
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
        val buttonSemantics = object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        listOf(ui.nexoTvResultPlay, ui.nexoTvResultMore, ui.nexoTvResultSeason,
            ui.nexoTvResultDub, ui.nexoTvResultRange, ui.nexoTvResultRetry).forEach {
            ViewCompat.setAccessibilityDelegate(it, buttonSemantics)
        }
        listOf(ui.nexoTvResultTitle, ui.nexoTvResultEpisodeStatus,
            ui.nexoTvResultActorTitle, ui.nexoTvResultRecommendationTitle).forEach {
            ViewCompat.setAccessibilityHeading(it, true)
        }
        // Hiding Play during metadata loading can move focus to Favorite automatically.
        // Only leaving for the menu cancels this initial/return restoration.
        cancelNexoTvPendingFocus(ui.root) { pendingFocus = false }
        // Truncation can change after text, width or font-scale updates without a new page.
        ui.nexoTvResultPlot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Changing a sibling's visibility during layout can leave its parent with stale bounds.
            ui.nexoTvResultPlot.post {
                if (binding === ui) {
                    val layout = ui.nexoTvResultPlot.layout
                    val truncated = layout != null && (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
                    if (!truncated && ui.nexoTvResultMore.hasFocus()) {
                        (ui.nexoTvResultPlay.takeIf { it.isShown } ?: ui.nexoTvResultFavorite).requestFocus()
                    }
                    ui.nexoTvResultMore.isVisible = truncated
                }
            }
        }
        stored = getStoredData() ?: return
        ui.root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        ui.nexoTvResultRetry.setOnClickListener { load(false) }
        ui.nexoTvResultTrailer.setOnClickListener {
            if (model.trailers.value.orEmpty().tvTrailerLinks().isNotEmpty() && childFragmentManager.findFragmentByTag("nexo_tv_trailer") == null) {
                NexoTvTrailerDialog().show(childFragmentManager, "nexo_tv_trailer")
            }
        }
        ui.nexoTvResultMoreActions.setOnClickListener { showSecondaryActions() }
        ui.nexoTvResultFavorite.setOnClickListener { model.toggleFavoriteStatus(context) }
        episodes = NexoTvEpisodeAdapter(
            { rememberPlaybackOrigin(it); model.handleAction(EpisodeClickEvent(ACTION_CLICK_DEFAULT, it)) },
            { rememberPlaybackOrigin(it); model.handleAction(EpisodeClickEvent(ACTION_SHOW_OPTIONS, it)) },
            { ep, _ -> focusIndex = episodes?.currentList?.indexOf(ep) ?: 0 })
        ui.nexoTvResultEpisodes.setLinearListLayout(nextLeft = R.id.nav_rail_view, nextRight = FOCUS_SELF)
        ui.nexoTvResultEpisodes.adapter = episodes
        actors = NexoTvActorAdapter()
        ui.nexoTvResultActors.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        ui.nexoTvResultActors.adapter = actors
        ui.nexoTvResultActors.setOnFocusChangeListener { _, focused ->
            ui.nexoTvResultActorHint.isVisible = focused
        }
        ui.nexoTvResultActors.setOnKeyListener { _, key, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val direction = when (key) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@setOnKeyListener false
            }
            val row = ui.nexoTvResultActors
            val towardMenu = direction == if (row.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 else -1
            if (row.canScrollHorizontally(direction)) row.smoothScrollBy(direction * row.width / 2, 0)
            else if (towardMenu) activity?.findViewById<View>(R.id.nav_rail_view)?.requestFocus()
            true
        }
        recommendations = NexoTvCardAdapter(false,
            { item ->
                // Preserve the activated card while navigation animations move focus out of this view.
                focusArea = "recommendation"; focusKey = item.tvItemKey
                focusIndex = recommendations?.currentList?.indexOf(item)?.coerceAtLeast(0) ?: 0
                pendingFocus = true
                loadResult(item.url, item.apiName, item.name)
            }, { false },
            { item, _ -> focusIndex = recommendations?.currentList?.indexOf(item) ?: 0 })
        ui.nexoTvResultRecommendations.setLinearListLayout(nextLeft = R.id.nav_rail_view, nextRight = FOCUS_SELF)
        ui.nexoTvResultRecommendations.adapter = recommendations
        model.page.observe(viewLifecycleOwner) { page ->
            when (page) {
                is Resource.Success -> {
                    val data = page.value
                    comingSoon = data.comingSoon
                    val cast = data.actors.orEmpty()
                    actors?.submitList(cast)
                    ui.nexoTvResultActors.isVisible = cast.isNotEmpty()
                    ui.nexoTvResultActorText.text = data.actorsText?.asString(requireContext())
                    ui.nexoTvResultActorText.isVisible = cast.isEmpty() && !ui.nexoTvResultActorText.text.isNullOrBlank()
                    ui.nexoTvResultActorTitle.isVisible = cast.isNotEmpty() || ui.nexoTvResultActorText.isVisible
                    ui.nexoTvResultActorHeader.isVisible = ui.nexoTvResultActorTitle.isVisible
                    ui.nexoTvResultStatus.isVisible = false
                    ui.nexoTvResultTitle.text = data.title
                    val emission = data.onGoingText?.let { status ->
                        when ((status as? UiText.StringResource)?.resId) {
                            R.string.status_completed -> getString(R.string.nexo_tv_emission_completed)
                            R.string.status_ongoing -> getString(R.string.nexo_tv_emission_ongoing)
                            else -> getString(R.string.nexo_tv_emission_status, status.asString(requireContext()))
                        }
                    }
                    val metadata = (listOfNotNull(data.yearText, data.durationText, data.ratingText, data.contentRatingText)
                        .map { it.asString(requireContext()) } + listOfNotNull(emission, data.nextAiringEpisode?.asString(requireContext())))
                        .filter { it.isNotBlank() }
                    ui.nexoTvResultMeta.text = metadata.joinToString("  |  ")
                    ui.nexoTvResultMeta.isVisible = metadata.isNotEmpty()
                    populateChips(ui.nexoTvResultGenres, data.tags, R.style.NexoTvDetailsGenre)
                    // Informational pills need no inherited button/touch-target insets.
                    ui.nexoTvResultGenres.children.filterIsInstance<Chip>().forEach { chip ->
                        chip.setEnsureMinTouchTargetSize(false)
                        chip.minHeight = 0
                        chip.minimumHeight = 0
                        chip.setTextAppearance(R.style.NexoTvDetailsGenreText)
                        chip.setPaddingRelative(chip.paddingStart, 0, chip.paddingEnd, 0)
                    }
                    ui.nexoTvResultGenres.isVisible = data.tags.isNotEmpty()
                    ui.nexoTvResultPlot.text = data.plotText.asString(requireContext())
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
        model.trailers.observe(viewLifecycleOwner) { refreshSecondaryActions() }
        model.movie.observe(viewLifecycleOwner) { refreshPlay() }
        model.resumeWatching.observe(viewLifecycleOwner) { refreshPlay() }
        model.episodes.observe(viewLifecycleOwner) { resource ->
            if (resource is Resource.Success) {
                episodes?.submitList(resource.value) { restoreFocus() }
            }
            refreshPlay()
        }
        model.favoriteStatus.observe(viewLifecycleOwner) {
            ui.nexoTvResultFavorite.isSelected = it == true
            ui.nexoTvResultFavorite.setImageResource(if (it == true) R.drawable.ic_baseline_favorite_24 else R.drawable.ic_baseline_favorite_border_24)
            ui.nexoTvResultFavorite.contentDescription = getString(if (it == true) R.string.unfavorite else R.string.favorite)
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
        secondaryTarget = target?.takeUnless { comingSoon }
        ui.nexoTvResultResume.isVisible = resume != null && !comingSoon
        if (resume != null) {
            ui.nexoTvResultResumeTitle.text = if (resume.isMovie) (model.page.value as? Resource.Success)?.value?.title
                else requireContext().getNameFull(resume.result.name, resume.result.episode, resume.result.season)
            val progress = resume.progress
            val fraction = progress?.takeIf { it.maxProgress > 0 }?.let { (it.progress.toDouble() / it.maxProgress).coerceIn(0.0, 1.0) }
            ui.nexoTvResultResumeProgressRow.isVisible = fraction != null
            ui.nexoTvResultResumeRemaining.isVisible = fraction != null
            ui.nexoTvResultResumeProgress.progress = ((fraction ?: 0.0) * 100).toInt()
            ui.nexoTvResultResumePercent.text = fraction?.let { NumberFormat.getPercentInstance().format(it) }
            ui.nexoTvResultResumeRemaining.text = progress?.progressLeft?.asString(requireContext())?.replace('\n', ' ')
        }
        val hasEpisodes = eps.isNotEmpty() && !comingSoon
        ui.nexoTvResultEpisodes.isVisible = hasEpisodes
        val episodeStatus = if (hasEpisodes) getString(R.string.episodes) else if (!comingSoon)
            (model.page.value as? Resource.Success)?.value?.noEpisodesFoundText?.asString(requireContext()) else null
        ui.nexoTvResultEpisodeStatus.text = episodeStatus
        ui.nexoTvResultEpisodeStatus.isVisible = !episodeStatus.isNullOrBlank()
        refreshEpisodeControls()
        ui.nexoTvResultPlay.isVisible = !comingSoon && target != null
        ui.nexoTvResultPlay.text = getString(if (resume != null) R.string.resume else R.string.lite_play)
        ui.nexoTvResultPlay.setOnClickListener { playbackOrigin = null; target?.let { model.handleAction(EpisodeClickEvent(if (resume != null) stored?.playerAction ?: ACTION_CLICK_DEFAULT else ACTION_CLICK_DEFAULT, it)) } }
        ui.nexoTvResultPlay.setOnLongClickListener { playbackOrigin = null; target?.let { model.handleAction(EpisodeClickEvent(ACTION_SHOW_OPTIONS, it)) }; true }
        refreshSecondaryActions()
        restoreFocus()
    }
    private fun canChooseCast(): Boolean = !castSetupFailed && model.currentRepo?.api?.hasChromecastSupport == true && context?.isCastApiAvailable() == true
    private fun refreshSecondaryActions() {
        val ui = binding ?: return
        val trailer = model.trailers.value.orEmpty().tvTrailerLinks().isNotEmpty()
        val more = secondaryTarget != null || canChooseCast()
        if ((!trailer && ui.nexoTvResultTrailer.hasFocus()) || (!more && ui.nexoTvResultMoreActions.hasFocus())) {
            (ui.nexoTvResultPlay.takeIf { it.isShown } ?: ui.nexoTvResultFavorite).requestFocus()
        }
        ui.nexoTvResultTrailer.isVisible = trailer
        ui.nexoTvResultMoreActions.isVisible = more
        if (!castRequested && model.currentRepo?.api?.hasChromecastSupport == true) {
            castRequested = true
            runCatching {
                CastContext.getSharedInstance(requireContext()) { it.run() }.addOnCompleteListener {
                    if (binding === ui) refreshSecondaryActions()
                }
            }
        }
    }
    private fun showSecondaryActions() {
        val ui = binding ?: return
        val target = secondaryTarget
        val castButton = if (canChooseCast()) runCatching {
            MediaRouteButton(requireContext()).apply {
                CastButtonFactory.setUpMediaRouteButton(requireContext(), this)
                setAlwaysVisible(true)
                contentDescription = getString(R.string.lite_cast_action)
                isFocusable = true
            }
        }.getOrElse { castSetupFailed = true; null } else null
        if (target == null && castButton == null) { refreshSecondaryActions(); return }
        selection?.dismiss()
        if (target != null && castButton == null) {
            playbackOrigin = null
            model.handleAction(EpisodeClickEvent(ACTION_SHOW_OPTIONS, target))
            return
        }
        val builder = AlertDialog.Builder(requireContext()).setTitle(R.string.lite_more_actions)
        if (target != null) builder.setItems(arrayOf(getString(R.string.lite_options))) { _, _ ->
            playbackOrigin = null
            model.handleAction(EpisodeClickEvent(ACTION_SHOW_OPTIONS, target))
        }
        if (castButton != null) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(24.toPx(), 8.toPx(), 24.toPx(), 16.toPx())
                addView(TextView(context).apply { text = getString(R.string.lite_cast_action); textSize = 17f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(castButton, LinearLayout.LayoutParams(52.toPx(), 52.toPx()))
            }
            builder.setView(row)
        }
        selection = builder.create().apply {
            setOnDismissListener { if (binding === ui && isResumed && ui.nexoTvResultMoreActions.isShown) ui.nexoTvResultMoreActions.requestFocus() }
            show()
        }
    }
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun rememberPlaybackOrigin(episode: ResultEpisode) {
        playbackOrigin = TvFocusAnchor("episode", episode.tvEpisodeKey, episodes?.currentList?.indexOf(episode)?.coerceAtLeast(0) ?: 0)
    }
    private fun selector(view: TextView, labels: List<String>, selected: Int, changed: (Int) -> Unit) {
        view.isVisible = labels.size > 1
        view.text = labels.getOrNull(selected)
        refreshEpisodeControls()
        view.setOnClickListener {
            selection?.dismiss()
            selection = AlertDialog.Builder(requireContext()).setTitle(view.text).setSingleChoiceItems(labels.toTypedArray(), labels.indexOf(view.text.toString()).coerceAtLeast(0)) { dialog, index -> changed(index); dialog.dismiss() }.show()
        }
    }
    private fun refreshEpisodeControls() {
        val ui = binding ?: return
        ui.nexoTvResultEpisodeControls.isVisible = !comingSoon && listOf(ui.nexoTvResultSeason, ui.nexoTvResultDub, ui.nexoTvResultRange).any { it.isVisible }
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
                // LiveData can arrive before ListAdapter commits its list after returning from the player.
                val awaitingItems = if (focusArea == "episode") {
                    val source = model.episodes.value
                    source !is Resource.Success || source.value.isNotEmpty()
                } else !model.recommendations.value.isNullOrEmpty()
                if (keys.isEmpty() && awaitingItems && !comingSoon) return@post
                val index = resolveTvFocusIndex(keys, focusKey?.let { TvFocusAnchor(focusArea, it, focusIndex) })
                if (index != null) {
                    val target = list.findViewHolderForAdapterPosition(index)?.itemView
                    if (target == null) { list.scrollToPosition(index); list.doOnNextLayout { if (binding === ui) restoreFocus() } }
                    else if (target.requestFocus()) {
                        pendingFocus = false; releaseRail?.invoke(); releaseRail = (activity as? MainActivity)?.let { bindNexoTvRail(it, target.id) }
                    } else list.doOnNextLayout { if (binding === ui) restoreFocus() }
                    return@post
                }
            }
            val target = ui.root.findViewById<View>(focusId)?.takeIf { it.isShown && it.isFocusable }
                ?: ui.nexoTvResultPlay.takeIf { it.isShown } ?: ui.nexoTvResultFavorite
            if (target.requestFocus()) {
                pendingFocus = false
                releaseRail?.invoke(); releaseRail = (activity as? MainActivity)?.let { bindNexoTvRail(it, target.id) }
            } else ui.root.doOnNextLayout { if (binding === ui) restoreFocus() }
        }
    }
    override fun onResume() { super.onResume(); MainActivity.afterPluginsLoadedEvent += plugins; refreshSecondaryActions(); restoreFocus() }
    override fun onStop() {
        // A transition/season animation can move focus before onPause. Restore the action's origin,
        // rather than that transient focus, only when handing off to the native player.
        if (parentFragmentManager.primaryNavigationFragment is com.lagradost.cloudstream3.ui.player.GeneratorPlayer) {
            playbackOrigin?.let { focusArea = it.sectionKey; focusKey = it.itemKey; focusIndex = it.fallbackIndex }
        }
        playbackOrigin = null
        MainActivity.afterPluginsLoadedEvent -= plugins
        releaseRail?.invoke(); releaseRail = null; super.onStop()
    }
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
        binding?.nexoTvResultActors?.adapter = null; actors = null
        binding?.nexoTvResultEpisodes?.adapter = null; binding?.nexoTvResultRecommendations?.adapter = null
        episodes = null; recommendations = null; binding = null; pendingFocus = true
        super.onDestroyView()
    }
}
