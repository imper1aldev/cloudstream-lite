package com.lagradost.cloudstream3.ui.tv

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.NexoTvSearchBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.ALLOWED_PLUGIN_API_NAME
import com.lagradost.cloudstream3.ui.GrdLayoutManager
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NexoTvSearchFragment : Fragment(R.layout.nexo_tv_search) {
    private val model: SearchViewModel by viewModels()
    private var binding: NexoTvSearchBinding? = null
    private var cards: NexoTvCardAdapter? = null
    private var query = ""
    private var anchor: TvFocusAnchor? = null
    private var scroll: Parcelable? = null
    private var returning = false
    private var focusResults = false
    private var debounce: Job? = null
    private var imeListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var requestKeyboard = false
    private var releaseRail: (() -> Unit)? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        query = state?.getString("query") ?: arguments?.getString("search_query") ?: model.lastQuery.orEmpty()
        state?.getString("item")?.let { anchor = TvFocusAnchor("search", it, state.getInt("index")) }
        @Suppress("DEPRECATION")
        scroll = state?.getParcelable("scroll")
        returning = anchor != null
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        val ui = NexoTvSearchBinding.bind(view)
        binding = ui
        cancelNexoTvPendingFocus(ui.root, ui.nexoTvSearchInput, ui.nexoTvSearchBack, ui.nexoTvSearchRetry) {
            returning = false; focusResults = false
        }
        val adapter = NexoTvCardAdapter(false, { item ->
            returning = true
            scroll = ui.nexoTvSearchGrid.layoutManager?.onSaveInstanceState()
            hideKeyboard()
            loadResult(item.url, item.apiName, item.name)
        }, { false }, { item, card ->
            if (!returning) anchor = TvFocusAnchor("search", item.tvItemKey, cards?.currentList?.indexOf(item) ?: 0)
            bindNexoTvGridEdges(card, ui.nexoTvSearchGrid, ui.nexoTvSearchInput, activity?.findViewById(R.id.navigation_search)) { ui.nexoTvSearchRetry.takeIf { it.isShown } }
            bindRail(card.id)
        })
        cards = adapter
        ui.nexoTvSearchGrid.apply {
            itemAnimator = null
            layoutManager = GrdLayoutManager(context, 6)
            this.adapter = adapter
            doOnLayout { (layoutManager as GridLayoutManager).spanCount = (width / resources.displayMetrics.density / 128).toInt().coerceIn(2,8) }
            layoutManager?.onRestoreInstanceState(scroll)
            addOnScrollListener(object : RecyclerView.OnScrollListener() { override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { loadMore() } })
        }
        ui.nexoTvSearchInput.apply {
            setText(query)
            showSoftInputOnFocus = false
            setOnClickListener { showKeyboard() }
            setOnFocusChangeListener { _, has -> if (has) bindRail(id) }
            doAfterTextChanged { text ->
                val changed = text?.toString().orEmpty()
                if (changed == query) return@doAfterTextChanged // Native view restoration can re-emit identical text.
                query = changed
                returning = false; focusResults = false; anchor = null; scroll = null
                debounce?.cancel()
                model.clearSearch() // Invalidate an old generation immediately, including during debounce.
                render()
                if (query.trim().length >= 2) debounce = viewLifecycleOwner.lifecycleScope.launch { delay(500); search() }
                model.updateHistory()
            }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event == null || event.action == KeyEvent.ACTION_UP) { hideKeyboard(); focusResults = true; debounce?.cancel(); search() }
                    true
                } else false
            }
        }
        ui.nexoTvSearchBack.setOnClickListener { exit() }
        ui.nexoTvSearchBack.setOnFocusChangeListener { _, has -> if (has) bindRail(ui.nexoTvSearchInput.id) }
        ui.nexoTvSearchRetry.setOnClickListener {
            if (model.moreError.value == true && model.lastQuery == query.trim()) {
                anchor?.let { saved -> val index = resolveTvFocusIndex(adapter.currentList.map { it.tvItemKey }, saved); index?.let { ui.nexoTvSearchGrid.findViewHolderForAdapterPosition(it)?.itemView?.requestFocus() } }
                model.loadMore()
            } else search()
        }
        model.searchResponse.observe(viewLifecycleOwner) { render() }
        model.moreLoading.observe(viewLifecycleOwner) { renderStatus() }
        model.moreError.observe(viewLifecycleOwner) { renderStatus() }
        model.currentHistory.observe(viewLifecycleOwner) { history ->
            ui.nexoTvSearchHistory.removeAllViews()
            history.forEach { item ->
                ui.nexoTvSearchHistory.addView(TextView(requireContext(), null, 0, R.style.NexoTvAction).apply {
                    id = View.generateViewId(); text = item.searchText
                    setOnClickListener { ui.nexoTvSearchInput.setText(item.searchText); debounce?.cancel(); focusResults = true; hideKeyboard(); search() }
                    setOnFocusChangeListener { _, has -> if (has) bindRail(ui.nexoTvSearchInput.id) }
                })
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ViewCompat.getRootWindowInsets(ui.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true) hideKeyboard() else exit()
            }
        })
        model.updateHistory()
        if (query.trim().length >= 2 && (query.trim() != model.lastQuery || model.searchResponse.value == null)) {
            debounce = viewLifecycleOwner.lifecycleScope.launch { delay(500); search() }
        }
        val requestIme = arguments?.getBoolean("lite_focus_search") == true
        arguments?.remove("lite_focus_search")
        ui.root.post { if (binding === ui) { if (returning) restoreFocus() else ui.nexoTvSearchInput.requestFocus(); if (requestIme) showKeyboard() } }
        render()
    }
    private fun search() {
        if (query.trim().length < 2) return
        model.reloadRepos()
        model.searchAndCancel(query.trim(), setOf(ALLOWED_PLUGIN_API_NAME), ignoreSettings = true)
    }
    private fun render() {
        val ui = binding ?: return
        val history = query.trim().length < 2
        ui.nexoTvSearchHistoryScroll.isVisible = history
        val results = if (model.lastQuery == query.trim()) (model.searchResponse.value as? Resource.Success)?.value else null
        ui.nexoTvSearchGrid.isVisible = !history && results?.list?.isNotEmpty() == true
        cards?.submitList(results?.list?.toList().orEmpty()) {
            if (binding === ui && (returning || focusResults)) ui.nexoTvSearchGrid.post { restoreFocus() }
        }
        renderStatus()
    }
    private fun renderStatus() {
        val ui = binding ?: return
        val history = query.trim().length < 2
        val resource = model.searchResponse.value
        val matching = model.lastQuery == query.trim()
        val data = if (matching) (resource as? Resource.Success)?.value else null
        ui.nexoTvSearchMessage.text = when {
            history -> ""
            !matching || resource is Resource.Loading || model.moreLoading.value == true -> getString(R.string.loading)
            resource is Resource.Failure -> resource.errorString
            data?.list?.isEmpty() == true -> getString(R.string.no_data)
            else -> ""
        }
        ui.nexoTvSearchMessage.isVisible = ui.nexoTvSearchMessage.text.isNotEmpty()
        ui.nexoTvSearchRetry.isVisible = !history && matching && (resource is Resource.Failure || model.moreError.value == true)
    }
    private fun loadMore() {
        val ui = binding ?: return
        val data = (model.searchResponse.value as? Resource.Success)?.value ?: return
        val manager = ui.nexoTvSearchGrid.layoutManager as? GridLayoutManager ?: return
        if (model.lastQuery == query.trim() && data.hasNext && model.moreLoading.value != true && model.moreError.value != true && manager.findLastVisibleItemPosition() >= data.list.size-manager.spanCount) model.loadMore()
    }
    private fun restoreFocus() {
        val ui = binding ?: return
        if (!returning && !focusResults || !isResumed) return
        val keys = cards?.currentList?.map { it.tvItemKey }.orEmpty()
        val index = resolveTvFocusIndex(keys, if (focusResults) null else anchor) ?: return
        val card = ui.nexoTvSearchGrid.findViewHolderForAdapterPosition(index)?.itemView
        if (card == null) {
            ui.nexoTvSearchGrid.scrollToPosition(index)
            ui.nexoTvSearchGrid.doOnNextLayout { if (binding === ui) restoreFocus() }
        } else { returning = false; focusResults = false; card.requestFocus() }
    }
    private fun bindRail(id: Int) { releaseRail?.invoke(); releaseRail = (activity as? MainActivity)?.let { bindNexoTvRail(it, id) } }
    private fun showKeyboard() {
        val ui = binding ?: return
        requestKeyboard = true
        val input = ui.nexoTvSearchInput
        input.requestFocus()
        if (input.hasWindowFocus() && isResumed) {
            imeListener?.let { ui.root.viewTreeObserver.removeOnWindowFocusChangeListener(it) }; imeListener = null
            input.showSoftInputOnFocus = true
            ViewCompat.getWindowInsetsController(input)?.show(WindowInsetsCompat.Type.ime())
            requestKeyboard = false
        } else if (imeListener == null) {
            imeListener = ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has && binding === ui && requestKeyboard) showKeyboard() }
            ui.root.viewTreeObserver.addOnWindowFocusChangeListener(imeListener)
        }
    }
    private fun hideKeyboard() {
        requestKeyboard = false
        binding?.let { ui ->
            imeListener?.let { ui.root.viewTreeObserver.removeOnWindowFocusChangeListener(it) }; imeListener = null
            ui.nexoTvSearchInput.showSoftInputOnFocus = false
            (ui.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(ui.root.windowToken, 0)
        }
    }
    private fun exit() { hideKeyboard(); val nav = findNavController(); if (!nav.popBackStack()) nav.navigate(R.id.navigation_home) }
    override fun onResume() { super.onResume(); binding?.root?.post { if (requestKeyboard) showKeyboard(); restoreFocus() } }
    override fun onStop() { hideKeyboard(); releaseRail?.invoke(); releaseRail = null; super.onStop() }
    override fun onSaveInstanceState(out: Bundle) {
        scroll = binding?.nexoTvSearchGrid?.layoutManager?.onSaveInstanceState() ?: scroll
        out.putString("query", query); out.putString("item", anchor?.itemKey); out.putInt("index", anchor?.fallbackIndex ?: 0); out.putParcelable("scroll", scroll)
        super.onSaveInstanceState(out)
    }
    override fun onDestroyView() {
        debounce?.cancel(); debounce = null
        hideKeyboard()
        scroll = binding?.nexoTvSearchGrid?.layoutManager?.onSaveInstanceState() ?: scroll
        releaseRail?.invoke(); releaseRail = null
        binding?.nexoTvSearchGrid?.adapter = null; cards = null; binding = null
        super.onDestroyView()
    }
}
