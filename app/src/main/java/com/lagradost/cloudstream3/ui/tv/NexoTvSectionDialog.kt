package com.lagradost.cloudstream3.ui.tv

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.NexoTvSectionBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.GrdLayoutManager
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult

class NexoTvSectionDialog : DialogFragment(R.layout.nexo_tv_section) {
    private val model: HomeViewModel by activityViewModels()
    private var binding: NexoTvSectionBinding? = null
    private var cards: NexoTvCardAdapter? = null
    private var itemKey: String? = null
    private var fallback = 0
    private var scroll: Parcelable? = null
    private var restoring = true
    private val key: String get() = requireArguments().getString("nexo_tv_section_key").orEmpty()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Material_NoActionBar)
        itemKey = state?.getString("item"); fallback = state?.getInt("index") ?: 0
        @Suppress("DEPRECATION")
        scroll = state?.getParcelable("scroll")
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = NexoTvSectionBinding.bind(view)
        binding = ui
        ui.nexoTvSectionTitle.text = key
        ui.nexoTvSectionClose.setOnClickListener { dismiss() }
        ui.nexoTvSectionRetry.setOnClickListener { model.expand(key) }
        val adapter = NexoTvCardAdapter(false, { restoring = true; loadResult(it.url, it.apiName, it.name) }, { false }, { item, card ->
            if (!restoring) { itemKey = item.tvItemKey; fallback = (ui.nexoTvSectionList.layoutManager as? GridLayoutManager)?.getPosition(card) ?: 0 }
        })
        cards = adapter
        ui.nexoTvSectionList.apply {
            itemAnimator = null
            val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            layoutManager = GrdLayoutManager(context, ((width-48)/128).toInt().coerceIn(2,8))
            this.adapter = adapter
            layoutManager?.onRestoreInstanceState(scroll)
            addOnScrollListener(object : RecyclerView.OnScrollListener() { override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { maybeExpand() } })
        }
        model.page.observe(viewLifecycleOwner) { state ->
            val data = (state as? Resource.Success)?.value?.get(key)
            ui.nexoTvSectionProgress.isVisible = data?.loading == true
            ui.nexoTvSectionRetry.isVisible = data?.error == true
            adapter.submitList(data?.list?.list?.toList().orEmpty()) {
                if (binding === ui && restoring) ui.nexoTvSectionList.post { restoreFocus() }
            }
        }
    }
    private fun maybeExpand() {
        val data = (model.page.value as? Resource.Success)?.value?.get(key) ?: return
        val list = binding?.nexoTvSectionList ?: return
        val manager = list.layoutManager as? GridLayoutManager ?: return
        if (data.hasNext && !data.loading && !data.error && manager.findLastVisibleItemPosition() >= (cards?.itemCount ?: 0)-manager.spanCount*2) model.expand(key)
    }
    private fun restoreFocus() {
        val ui = binding ?: return
        val adapter = cards ?: return
        val index = resolveTvFocusIndex(adapter.currentList.map { it.tvItemKey }, TvFocusAnchor(key,itemKey.orEmpty(),fallback))
            ?: run { ui.nexoTvSectionClose.requestFocus(); return }
        val target = ui.nexoTvSectionList.findViewHolderForAdapterPosition(index)?.itemView
        if (target == null) {
            ui.nexoTvSectionList.scrollToPosition(index)
            ui.nexoTvSectionList.doOnNextLayout { if (binding === ui) restoreFocus() }
        } else {
            if (target.requestFocus()) { restoring = false; itemKey = adapter.currentList[index].tvItemKey; fallback = index }
        }
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply { setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT); setBackgroundDrawable(ColorDrawable(Color.rgb(16,17,20))) }
        binding?.root?.post { if(restoring) restoreFocus() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        scroll = binding?.nexoTvSectionList?.layoutManager?.onSaveInstanceState() ?: scroll
        outState.putParcelable("scroll",scroll); outState.putString("item",itemKey); outState.putInt("index",fallback)
        super.onSaveInstanceState(outState)
    }
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        parentFragmentManager.fragments.filterIsInstance<NexoTvHomeFragment>().firstOrNull()?.focusSectionTitle(key)
    }
    override fun onDestroyView() {
        scroll = binding?.nexoTvSectionList?.layoutManager?.onSaveInstanceState() ?: scroll
        binding?.nexoTvSectionList?.adapter = null
        binding = null; cards = null
        super.onDestroyView()
    }
}
