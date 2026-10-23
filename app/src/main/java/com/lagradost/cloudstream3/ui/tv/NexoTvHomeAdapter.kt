package com.lagradost.cloudstream3.ui.tv

import android.os.Parcelable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

internal data class TvHero(val item: SearchResponse, val image: String?, val plot: String?)
internal data class TvHomeRow(val key: String, val title: String, val items: List<SearchResponse>,
    val expandable: Boolean = false, val hasNext: Boolean = false, val loading: Boolean = false,
    val error: Boolean = false, val hero: TvHero? = null)

/** Only presentation state is stored here; pages and actions belong to HomeViewModel. */
internal class NexoTvHomeAdapter(
    private val memory: MutableMap<String, TvFocusAnchor>,
    private val scroll: MutableMap<String, Parcelable>,
    private val clicked: (String, SearchResponse, View) -> Unit,
    private val longClicked: (String, SearchResponse) -> Boolean,
    private val section: (String) -> Unit,
    private val focused: (TvFocusAnchor, View) -> Unit,
    private val loadMore: (String) -> Unit,
) : ListAdapter<TvHomeRow, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<TvHomeRow>() {
    override fun areItemsTheSame(old: TvHomeRow, new: TvHomeRow) = old.key == new.key
    override fun areContentsTheSame(old: TvHomeRow, new: TvHomeRow) = old == new
}) {
    private var master: RecyclerView? = null
    override fun getItemViewType(position: Int) = if (getItem(position).hero != null) 1 else 0
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { master = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { master = null }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(if (type == 1) R.layout.nexo_tv_hero else R.layout.nexo_tv_row, parent, false)
        return if (type == 1) Hero(view) else Row(view)
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val data = getItem(position)
        if (holder is Hero) holder.bind(data) else (holder as Row).bind(data)
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is Row) holder.release()
        else (holder as Hero).image.dispose()
        super.onViewRecycled(holder)
    }
    fun saveScroll() { master?.let { list -> for (i in 0 until list.childCount) (list.getChildViewHolder(list.getChildAt(i)) as? Row)?.save() } }

    fun restore(anchor: TvFocusAnchor?, completed: () -> Unit) {
        val list = master ?: return
        if (currentList.isEmpty()) return
        val position = currentList.indexOfFirst { it.key == anchor?.sectionKey }.coerceAtLeast(0)
        val holder = list.findViewHolderForAdapterPosition(position)
        if (holder == null) {
            list.scrollToPosition(position)
            list.doOnNextLayout { if (master === list) restore(anchor, completed) }
        } else when (holder) {
            is Hero -> { if (holder.itemView.requestFocus()) completed() }
            is Row -> {
                if (anchor?.itemKey == "@title") { if (holder.title.requestFocus()) completed() }
                else holder.focusItem(anchor ?: memory[getItem(position).key], completed)
            }
        }
    }
    private fun vertical(key: String, delta: Int) {
        val index = currentList.indexOfFirst { it.key == key }
        val next = currentList.getOrNull(index+delta) ?: return
        restore(memory[next.key] ?: TvFocusAnchor(next.key, "", 0)) {}
    }
    private inner class Hero(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.nexo_tv_hero_image)
        init { view.id = View.generateViewId(); view.nextFocusLeftId = R.id.nav_rail_view; view.nextFocusRightId = view.id }
        fun bind(data: TvHomeRow) {
            val hero = checkNotNull(data.hero)
            itemView.tag = "hero"
            itemView.contentDescription = hero.item.name
            itemView.findViewById<TextView>(R.id.nexo_tv_hero_title).text = hero.item.name
            itemView.findViewById<TextView>(R.id.nexo_tv_hero_plot).apply { text = hero.plot; isVisible = !hero.plot.isNullOrBlank() }
            if (hero.image.isNullOrBlank()) image.loadImage(R.drawable.default_cover)
            else image.loadImage(hero.image, hero.item.posterHeaders) { error { getImageFromDrawable(itemView.context, R.drawable.default_cover) } }
            itemView.setOnClickListener { clicked(data.key, hero.item, itemView) }
            itemView.setOnFocusChangeListener { view, hasFocus -> if (hasFocus) focused(TvFocusAnchor(data.key, hero.item.tvItemKey, 0), view) }
            itemView.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) { vertical(data.key, 1); true } else false
            }
        }
    }
    private inner class Row(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.nexo_tv_row_title)
        val list: RecyclerView = view.findViewById(R.id.nexo_tv_row_list)
        private val retry: View = view.findViewById(R.id.nexo_tv_row_retry)
        private var row: TvHomeRow? = null
        private var pendingScroll: String? = null
        private val cards: NexoTvCardAdapter = NexoTvCardAdapter(false, { item -> row?.let { clicked(it.key, item, list) } },
            { item -> row?.let { longClicked(it.key, item) } ?: false }, { item, card ->
                row?.let { data ->
                    val index = cardsIndex(item)
                    focused(TvFocusAnchor(data.key, item.tvItemKey, index), card)
                    card.setOnKeyListener { _, code, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) false
                        else when (code) {
                            KeyEvent.KEYCODE_DPAD_UP -> { if (title.isFocusable) title.requestFocus() else vertical(data.key, -1); true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { vertical(data.key, 1); true }
                            else -> false
                        }
                    }
                    maybeExpand()
                }
            })
        private fun cardsIndex(item: SearchResponse) = cards.currentList.indexOfFirst { it.tvItemKey == item.tvItemKey }.coerceAtLeast(0)
        init {
            title.id = View.generateViewId()
            list.itemAnimator = null
            list.setLinearListLayout(nextLeft = R.id.nav_rail_view, nextRight = FOCUS_SELF)
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() { override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { maybeExpand() } })
        }
        fun bind(data: TvHomeRow) {
            if (row?.key != data.key) { save(); list.adapter = null }
            val newRow = row?.key != data.key
            row = data
            if (newRow) pendingScroll = data.key
            itemView.tag = "section:${data.key}"
            title.tag = "section-title:${data.key}"
            title.text = data.title + if (data.expandable) "  \u203A" else ""
            title.isFocusable = data.expandable
            title.isClickable = data.expandable
            title.setOnClickListener { section(data.key) }
            title.setOnFocusChangeListener { v, has -> if (has) focused(TvFocusAnchor(data.key, "@title", 0), v) }
            title.setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when(code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { focusItem(memory[data.key]) {}; true }
                    KeyEvent.KEYCODE_DPAD_UP -> { vertical(data.key,-1); true }
                    else -> false
                }
            }
            retry.isVisible = data.error
            retry.setOnClickListener { focusItem(memory[data.key]) {}; loadMore(data.key) }
            if (list.adapter !== cards) list.adapter = cards
            cards.submitList(data.items) {
                if (pendingScroll == data.key) { scroll[data.key]?.let { list.layoutManager?.onRestoreInstanceState(it) }; pendingScroll = null }
            }
        }
        private fun maybeExpand() {
            val data = row ?: return
            if (data.expandable && data.hasNext && !data.loading && !data.error &&
                (list.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition()?.let { it >= cards.itemCount-4 } == true) loadMore(data.key)
        }
        fun release() { save(); list.adapter = null; row = null }
        fun save() { row?.key?.let { key -> list.layoutManager?.onSaveInstanceState()?.let { scroll[key] = it } } }
        fun focusItem(anchor: TvFocusAnchor?, completed: () -> Unit) {
            val data = row ?: return
            if (cards.currentList.isEmpty() && data.items.isNotEmpty()) {
                list.doOnNextLayout { if (row?.key == data.key) focusItem(anchor, completed) }; return
            }
            val index = resolveTvFocusIndex(cards.currentList.map { it.tvItemKey }, anchor) ?: run { if(title.requestFocus()) completed(); return }
            val target = list.findViewHolderForAdapterPosition(index)?.itemView
            if (target != null) { if(target.requestFocus()) completed() }
            else { list.scrollToPosition(index); list.doOnNextLayout { if (row?.key == data.key) focusItem(anchor, completed) } }
        }
    }
}


