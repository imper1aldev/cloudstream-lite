package com.lagradost.cloudstream3.ui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

class NexoTvCardAdapter @JvmOverloads constructor(
    private val landscape: Boolean,
    private val clicked: (SearchResponse) -> Unit,
    private val longClicked: (SearchResponse) -> Boolean,
    private val focused: (SearchResponse, View) -> Unit,
    private val immersive: Boolean = false,
) : ListAdapter<SearchResponse, NexoTvCardAdapter.Card>(object : DiffUtil.ItemCallback<SearchResponse>() {
    override fun areItemsTheSame(old: SearchResponse, new: SearchResponse) = old.apiName == new.apiName && old.url == new.url
    override fun areContentsTheSame(old: SearchResponse, new: SearchResponse) =
        old.name == new.name && old.posterUrl == new.posterUrl && old.posterHeaders == new.posterHeaders && watchProgress(old) == watchProgress(new)
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card = Card(LayoutInflater.from(parent.context)
        .inflate(if (landscape) R.layout.nexo_tv_landscape_card else if (immersive) R.layout.nexo_tv_overlay_card else R.layout.nexo_tv_poster_card, parent, false))

    override fun onBindViewHolder(holder: Card, position: Int) {
        val item = getItem(position)
        holder.itemView.apply {
            tag = item.tvItemKey
            contentDescription = item.name
            scaleX = if (hasFocus()) 1.04f else 1f
            scaleY = scaleX
            setOnClickListener { currentItem(holder)?.let(clicked) }
            setOnLongClickListener { currentItem(holder)?.let(longClicked) ?: false }
            setOnFocusChangeListener { view, hasFocus ->
                view.scaleX = if (hasFocus) 1.04f else 1f
                view.scaleY = view.scaleX
                if (hasFocus) currentItem(holder)?.let { focused(it, view) }
            }
        }
        holder.title.text = item.name
        holder.progress?.apply {
            val value = watchProgress(item)
            progress = value ?: 0
            isVisible = value != null
        }
        if (item.posterUrl.isNullOrBlank()) holder.image.loadImage(R.drawable.default_cover)
        else holder.image.loadImage(item.posterUrl, item.posterHeaders) { error { getImageFromDrawable(holder.itemView.context, R.drawable.default_cover) } }
    }
    private fun currentItem(holder: Card): SearchResponse? =
        holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { currentList.getOrNull(it) }

    override fun onViewRecycled(holder: Card) {
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        holder.itemView.setOnFocusChangeListener(null)
        holder.itemView.setOnKeyListener(null)
        super.onViewRecycled(holder)
    }
    class Card(view: View) : RecyclerView.ViewHolder(view) {
        init { view.id = View.generateViewId() }
        val image: ImageView = view.findViewById(R.id.nexo_tv_card_image)
        val progress: ProgressBar? = view.findViewById(R.id.nexo_tv_card_progress)
        val title: TextView = view.findViewById(R.id.nexo_tv_card_title)
    }
}

private fun watchProgress(item: SearchResponse): Int? {
    val position = (item as? DataStoreHelper.ResumeWatchingResult)?.watchPos ?: return null
    if (position.duration <= 0L) return null
    return (position.position.toDouble() / position.duration * 100).toInt().coerceIn(0, 100)
}