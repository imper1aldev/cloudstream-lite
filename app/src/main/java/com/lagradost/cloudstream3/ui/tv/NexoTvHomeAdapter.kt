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
import androidx.recyclerview.widget.ConcatAdapter
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

internal data class TvHero(val item: SearchResponse, val image: String?, val plot: String?, val portrait: Boolean, val metadata: String = "")
internal data class TvHomeRow(val key: String, val title: String, val items: List<SearchResponse>,
    val expandable: Boolean = false, val hasNext: Boolean = false, val loading: Boolean = false,
    val error: Boolean = false, val hero: TvHero? = null, val heroIndex: Int = 0, val heroCount: Int = 0)

/** Only presentation state is stored here; pages and actions belong to HomeViewModel. */
internal class NexoTvHomeAdapter(
    private val memory: MutableMap<String, TvFocusAnchor>,
    private val scroll: MutableMap<String, Parcelable>,
    private val clicked: (String, SearchResponse, View) -> Unit,
    private val longClicked: (String, SearchResponse) -> Boolean,
    private val section: (String) -> Unit,
    private val focused: (TvFocusAnchor, View) -> Unit,
    private val advanceHero: (Int) -> Boolean,
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
        if (type == 1 && parent.height > 0) {
            view.layoutParams.height = (parent.height * 0.56f).toInt()
        }
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

    fun restore(anchor: TvFocusAnchor?, allowed: () -> Boolean = { true }, completed: () -> Unit) {
        val list = master ?: return
        if (!allowed() || currentList.isEmpty()) return
        val position = currentList.indexOfFirst { it.key == anchor?.sectionKey }.takeIf { it >= 0 }
            ?: if (anchor != null && currentList.size > 1 && currentList.first().hero != null) 1 else 0
        val finish = {
            list.stopScroll()
            (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
            completed()
        }
        val holder = list.findViewHolderForAdapterPosition(position)
        if (holder == null) {
            list.scrollToPosition(position)
            list.doOnNextLayout { if (master === list) restore(anchor, allowed, completed) }
        } else when (holder) {
            is Hero -> { if (holder.itemView.requestFocus()) finish() }
            is Row -> {
                val target = getItem(position).key
                val rowAnchor = anchor?.takeIf { it.sectionKey == target } ?: memory[target]
                holder.focusItem(rowAnchor, allowed, finish)
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
        init { view.id = View.generateViewId() }
        fun bind(data: TvHomeRow) {
            val hero = checkNotNull(data.hero)
            itemView.tag = "hero"
            itemView.nextFocusLeftId = if (data.heroCount > 1) View.NO_ID else R.id.nav_rail_view
            itemView.nextFocusRightId = if (data.heroCount > 1) View.NO_ID else itemView.id
            itemView.contentDescription = hero.item.name
            itemView.findViewById<TextView>(R.id.nexo_tv_hero_title).text = hero.item.name
            itemView.findViewById<TextView>(R.id.nexo_tv_hero_plot).apply { text = hero.plot; isVisible = !hero.plot.isNullOrBlank() }
            itemView.findViewById<TextView>(R.id.nexo_tv_hero_metadata).apply {
                text = hero.metadata
                isVisible = hero.metadata.isNotEmpty()
            }
            image.scaleType = if (hero.portrait) ImageView.ScaleType.FIT_END else ImageView.ScaleType.CENTER_CROP
            itemView.findViewById<TextView>(R.id.nexo_tv_hero_dots).apply {
                isVisible = data.heroCount > 1
                text = (0 until data.heroCount).joinToString("  ") { if (it == data.heroIndex) "●" else "○" }
            }
            if (hero.image.isNullOrBlank()) { image.dispose(); image.setImageDrawable(null) }
            else image.loadImage(hero.image, hero.item.posterHeaders) { error { getImageFromDrawable(itemView.context, R.drawable.default_cover) } }
            itemView.setOnClickListener { clicked(data.key, hero.item, itemView) }
            val action = itemView.findViewById<TextView>(R.id.nexo_tv_hero_action)
            action.isActivated = itemView.hasFocus()
            itemView.setOnFocusChangeListener { view, hasFocus ->
                action.isActivated = hasFocus
                if (hasFocus) focused(TvFocusAnchor(data.key, hero.item.tvItemKey, 0), view)
            }
            itemView.setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { vertical(data.key, 1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> advanceHero(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> advanceHero(1)
                    else -> false
                }
            }
        }
    }
    private inner class Row(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.nexo_tv_row_title)
        val list: RecyclerView = view.findViewById(R.id.nexo_tv_row_list)
        private var row: TvHomeRow? = null
        private var pendingScroll: String? = null
        private val cards: NexoTvCardAdapter = NexoTvCardAdapter(false, { item -> row?.let { clicked(it.key, item, list) } },
            { item -> row?.let { longClicked(it.key, item) } ?: false }, { item, card ->
                row?.let { data ->
                    val index = cardsIndex(item)
                    focused(TvFocusAnchor(data.key, item.tvItemKey, index), card)
                    bindVerticalKeys(card, data.key)
                }
            }, immersive = true)
        private fun cardsIndex(item: SearchResponse) = cards.currentList.indexOfFirst { it.tvItemKey == item.tvItemKey }.coerceAtLeast(0)
        private val footer = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var visible = false
                set(value) {
                    if (field == value) return
                    field = value
                    if (value) notifyItemInserted(0) else notifyItemRemoved(0)
                }

            override fun getItemCount() = if (visible) 1 else 0

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.nexo_tv_more_card, parent, false)
                view.id = View.generateViewId()
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val data = checkNotNull(row)
                holder.itemView.apply {
                    tag = "section-more:${data.key}"
                    setOnClickListener { section(data.key) }
                    setOnFocusChangeListener { view, hasFocus ->
                        if (hasFocus) focused(TvFocusAnchor(data.key, "@more", cards.itemCount), view)
                    }
                    bindVerticalKeys(this, data.key)
                }
            }
        }
        private val rowAdapter = ConcatAdapter(cards, footer)

        init {
            list.itemAnimator = null
            list.setLinearListLayout(nextLeft = R.id.nav_rail_view, nextRight = FOCUS_SELF)
        }

        private fun bindVerticalKeys(view: View, key: String) {
            view.setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { vertical(key, -1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { vertical(key, 1); true }
                    else -> false
                }
            }
        }

        fun bind(data: TvHomeRow) {
            val newRow = row?.key != data.key
            if (newRow) {
                save()
                list.adapter = null
                pendingScroll = data.key
            }
            row = data
            itemView.tag = "section:${data.key}"
            title.text = data.title
            title.isFocusable = false
            if (list.adapter !== rowAdapter) list.adapter = rowAdapter
            footer.visible = data.expandable && (data.hasNext || data.items.size > 16)
            if (newRow && footer.visible) footer.notifyItemChanged(0)
            cards.submitList(if (data.expandable) data.items.take(16) else data.items) {
                if (pendingScroll == data.key) {
                    scroll[data.key]?.let { list.layoutManager?.onRestoreInstanceState(it) }
                    pendingScroll = null
                }
            }
        }
        fun release() { save(); list.adapter = null; row = null }
        fun save() { row?.key?.let { key -> list.layoutManager?.onSaveInstanceState()?.let { scroll[key] = it } } }
        fun focusItem(anchor: TvFocusAnchor?, allowed: () -> Boolean = { true }, completed: () -> Unit) {
            val data = row ?: return
            if (!allowed()) return
            if (cards.currentList.isEmpty() && data.items.isNotEmpty()) {
                list.doOnNextLayout { if (row?.key == data.key) focusItem(anchor, allowed, completed) }; return
            }
            val keys = cards.currentList.map { it.tvItemKey }
            val index = resolveTvFocusIndex(if (footer.visible) keys + "@more" else keys, anchor) ?: return
            val target = list.findViewHolderForAdapterPosition(index)?.itemView
            if (target != null) { if(target.requestFocus()) completed() }
            else { list.scrollToPosition(index); list.doOnNextLayout { if (row?.key == data.key) focusItem(anchor, allowed, completed) } }
        }
    }
}
