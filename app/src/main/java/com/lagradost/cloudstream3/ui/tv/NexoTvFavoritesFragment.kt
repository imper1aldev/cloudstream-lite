package com.lagradost.cloudstream3.ui.tv

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.NexoTvFavoritesBinding
import com.lagradost.cloudstream3.ui.GrdLayoutManager
import com.lagradost.cloudstream3.ui.download.*
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.ui.lite.LiteFavorites
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.FavoritesData
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType

class NexoTvFavoritesFragment : Fragment(R.layout.nexo_tv_favorites) {
    private val model: NexoTvFavoritesViewModel by viewModels()
    private var binding: NexoTvFavoritesBinding? = null
    private var cards: NexoTvCardAdapter? = null
    private var anchor: TvFocusAnchor? = null
    private var scroll: Parcelable? = null
    private var restoring = true
    private var releaseRail: (() -> Unit)? = null
    private var dialog: AlertDialog? = null
    private var snackbar: Snackbar? = null
    private var query = ""
    private var onlyDownloaded = false
    private val accountChanged: (Boolean) -> Unit = { snackbar?.dismiss(); snackbar = null; anchor = null; restoring = true }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        query = state?.getString("query").orEmpty()
        onlyDownloaded = state?.getBoolean("downloaded") ?: (arguments?.getBoolean("downloaded") == true)
        state?.getString("item")?.let { anchor = TvFocusAnchor("favorites", it, state.getInt("index")) }
        @Suppress("DEPRECATION")
        scroll = state?.getParcelable("scroll")
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        val ui = NexoTvFavoritesBinding.bind(view)
        binding = ui; restoring = true
        cancelNexoTvPendingFocus(ui.root, ui.nexoTvFavoritesQuery, ui.nexoTvFavoritesDownloaded, ui.nexoTvFavoritesSort, ui.nexoTvFavoritesOptions) { restoring = false }
        val adapter = NexoTvCardAdapter(false, { item ->
            restoring = true; hideKeyboard()
            scroll = ui.nexoTvFavoritesGrid.layoutManager?.onSaveInstanceState()
            loadResult(item.url, item.apiName, item.name)
        }, { item -> (item as? FavoritesData)?.let { showOptions(it) }; true }, { item, card ->
            if (!restoring) anchor = TvFocusAnchor("favorites", item.tvItemKey, cards?.currentList?.indexOf(item) ?: 0)
            bindNexoTvGridEdges(card, ui.nexoTvFavoritesGrid, ui.nexoTvFavoritesOptions, activity?.findViewById(R.id.navigation_library)) {
                snackbar?.takeIf { it.isShown }?.view?.findViewById(com.google.android.material.R.id.snackbar_action)
            }
            bindRail(card.id)
        })
        cards = adapter
        ui.nexoTvFavoritesGrid.apply {
            itemAnimator = null
            layoutManager = GrdLayoutManager(context,6)
            this.adapter = adapter
            doOnLayout { (layoutManager as GridLayoutManager).spanCount = (width / resources.displayMetrics.density / 128).toInt().coerceIn(2,8) }
            layoutManager?.onRestoreInstanceState(scroll)
        }
        ui.nexoTvFavoritesQuery.apply {
            setText(model.query.ifEmpty { query }); showSoftInputOnFocus = false
            setOnClickListener { showSoftInputOnFocus = true; ViewCompatShowIme() }
            doAfterTextChanged { model.setQuery(it?.toString().orEmpty()) }
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); ui.nexoTvFavoritesDownloaded.requestFocus(); true }
        }
        listOf(ui.nexoTvFavoritesQuery, ui.nexoTvFavoritesDownloaded, ui.nexoTvFavoritesSort, ui.nexoTvFavoritesOptions).forEach { control ->
            control.setOnFocusChangeListener { _, has -> if (has) bindRail(control.id) }
        }
        ui.nexoTvFavoritesDownloaded.setOnClickListener { model.toggleDownloaded() }
        ui.nexoTvFavoritesSort.setOnClickListener {
            val values = ListSorting.entries.filter { it != ListSorting.Query }
            dialog?.dismiss()
            dialog = AlertDialog.Builder(requireContext()).setTitle(R.string.lite_sort)
                .setSingleChoiceItems(values.map { getString(it.stringRes) }.toTypedArray(), values.indexOf(model.sorting)) { choice, index -> model.setSorting(values[index]); choice.dismiss() }.show()
        }
        ui.nexoTvFavoritesOptions.setOnClickListener {
            val items = model.items.value.orEmpty()
            val index = resolveTvFocusIndex(items.map { it.tvItemKey }, anchor)
            index?.let { showOptions(items[it]) }
        }
        model.items.observe(viewLifecycleOwner) { items ->
            val selectedGone = anchor?.itemKey?.let { selected -> items.none { it.tvItemKey == selected } } == true
            if (selectedGone && ui.nexoTvFavoritesGrid.hasFocus()) restoring = true
            ui.nexoTvFavoritesMessage.text = if (!model.hasLoaded) getString(R.string.loading) else if (items.isEmpty()) getString(R.string.no_data) else ""
            ui.nexoTvFavoritesMessage.isVisible = items.isEmpty()
            ui.nexoTvFavoritesOptions.isEnabled = items.isNotEmpty()
            ui.nexoTvFavoritesDownloaded.text = getString(R.string.lite_downloaded_filter) + if (model.onlyDownloaded) " \u2713" else ""
            adapter.submitList(items) {
                if (binding === ui) {
                    if (restoring && model.hasLoaded) ui.root.post { restoreFocus() }
                    val parentId = arguments?.getInt("parentId")?.takeIf { arguments?.containsKey("parentId") == true }
                    if (parentId != null && items.isNotEmpty()) {
                        arguments?.remove("parentId")
                        items.firstOrNull { it.id == parentId }?.let { showOptions(it) }
                    }
                }
            }
        }
        MainActivity.reloadAccountEvent += accountChanged
        model.start(requireContext(), query, onlyDownloaded)
    }
    private fun android.widget.EditText.ViewCompatShowIme() {
        requestFocus(); androidx.core.view.ViewCompat.getWindowInsetsController(this)?.show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }
    private fun showOptions(favorite: FavoritesData) {
        hideKeyboard()
        val episodes = model.localEpisodes(favorite.id)
        val labels = listOf(getString(R.string.lite_hero_details), getString(R.string.action_remove_from_favorites)) + episodes.map { getString(R.string.downloaded_file) + " \u00B7 " + requireContext().getNameFull(it.name, it.episode, it.season) }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(requireContext()).setTitle(favorite.name).setItems(labels.toTypedArray()) { _, index ->
            when (index) {
                0 -> { restoring = true; loadResult(favorite.url, favorite.apiName, favorite.name) }
                1 -> remove(favorite)
                else -> episodes.getOrNull(index-2)?.let { localOptions(it) }
            }
        }.show()
    }
    private fun localOptions(episode: DownloadEpisodeCached) {
        val status = model.statuses[episode.id]
        val actions = mutableListOf<Pair<Int, Int>>()
        if (status == DownloadType.IsDone) actions += R.string.lite_play to DOWNLOAD_ACTION_PLAY_FILE
        else {
            actions += if (status == DownloadType.IsDownloading) R.string.pause to DOWNLOAD_ACTION_PAUSE_DOWNLOAD else R.string.resume to DOWNLOAD_ACTION_RESUME_DOWNLOAD
            actions += R.string.cancel to DOWNLOAD_ACTION_CANCEL_PENDING
        }
        actions += R.string.delete to DOWNLOAD_ACTION_DELETE_FILE
        dialog?.dismiss()
        dialog = AlertDialog.Builder(requireContext()).setTitle(requireContext().getNameFull(episode.name, episode.episode, episode.season))
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index -> DownloadButtonSetup.handleDownloadClick(DownloadClickEvent(actions[index].second, episode)) }.show()
    }
    private fun remove(favorite: FavoritesData) {
        val ui = binding ?: return
        val account = DataStoreHelper.currentAccount
        restoring = true
        DataStoreHelper.removeFavoritesData(favorite.id)
        snackbar?.dismiss()
        snackbar = Snackbar.make(ui.root, getString(R.string.favorite_removed).format(favorite.name), LiteFavorites.GRACE_MS.toInt())
            .setAction(R.string.lite_undo) {
                if (account == DataStoreHelper.currentAccount) { restoring = true; DataStoreHelper.setFavoritesData(favorite.id, favorite) }
            }.also { snack ->
                snack.show()
                snack.view.findViewById<View>(com.google.android.material.R.id.snackbar_action)?.apply {
                    isFocusable = true; background = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.nexo_tv_text_focus)
                    nextFocusUpId = ui.nexoTvFavoritesDownloaded.id
                    ui.nexoTvFavoritesOptions.nextFocusDownId = id
                    ui.nexoTvFavoritesDownloaded.nextFocusDownId = id
                }
            }
    }
    private fun restoreFocus() {
        val ui = binding ?: return
        if (!restoring || !isResumed || !model.hasLoaded) return
        val items = cards?.currentList.orEmpty()
        // IO can finish before RecyclerView commits its first asynchronous diff.
        if (items.map { it.tvItemKey } != model.items.value.orEmpty().map { it.tvItemKey }) return
        val index = resolveTvFocusIndex(items.map { it.tvItemKey }, anchor)
        if (index == null) { restoring = false; ui.nexoTvFavoritesDownloaded.requestFocus(); return }
        val card = ui.nexoTvFavoritesGrid.findViewHolderForAdapterPosition(index)?.itemView
        if (card == null) { ui.nexoTvFavoritesGrid.scrollToPosition(index); ui.nexoTvFavoritesGrid.doOnNextLayout { if (binding === ui) restoreFocus() } }
        else { restoring = false; card.requestFocus() }
    }
    private fun bindRail(id: Int) { releaseRail?.invoke(); releaseRail = (activity as? MainActivity)?.let { bindNexoTvRail(it,id) } }
    private fun hideKeyboard() { binding?.let { ui -> ui.nexoTvFavoritesQuery.showSoftInputOnFocus = false; (ui.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(ui.root.windowToken,0) } }
    override fun onResume() { super.onResume(); binding?.root?.post { restoreFocus() } }
    override fun onStop() { hideKeyboard(); releaseRail?.invoke(); releaseRail = null; super.onStop() }
    override fun onSaveInstanceState(out: Bundle) {
        scroll = binding?.nexoTvFavoritesGrid?.layoutManager?.onSaveInstanceState() ?: scroll
        out.putString("query",model.query); out.putBoolean("downloaded",model.onlyDownloaded); out.putString("item",anchor?.itemKey); out.putInt("index",anchor?.fallbackIndex ?: 0); out.putParcelable("scroll",scroll)
        super.onSaveInstanceState(out)
    }
    override fun onDestroyView() {
        MainActivity.reloadAccountEvent -= accountChanged
        hideKeyboard(); releaseRail?.invoke(); releaseRail = null
        scroll = binding?.nexoTvFavoritesGrid?.layoutManager?.onSaveInstanceState() ?: scroll
        dialog?.dismiss(); dialog = null; snackbar?.dismiss(); snackbar = null
        binding?.nexoTvFavoritesGrid?.adapter = null; binding = null; cards = null
        super.onDestroyView()
    }
}
