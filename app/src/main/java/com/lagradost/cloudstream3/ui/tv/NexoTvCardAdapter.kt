package com.lagradost.cloudstream3.ui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

class NexoTvCardAdapter(
    private val landscape: Boolean,
    private val clicked: (SearchResponse) -> Unit,
    private val longClicked: (SearchResponse) -> Boolean,
    private val focused: (SearchResponse, View) -> Unit,
) : ListAdapter<SearchResponse, NexoTvCardAdapter.Card>(object : DiffUtil.ItemCallback<SearchResponse>() {
    override fun areItemsTheSame(old: SearchResponse, new: SearchResponse) = old.apiName == new.apiName && old.url == new.url
    override fun areContentsTheSame(old: SearchResponse, new: SearchResponse) =
        old.name == new.name && old.posterUrl == new.posterUrl && old.posterHeaders == new.posterHeaders
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card = Card(LayoutInflater.from(parent.context)
        .inflate(if (landscape) R.layout.nexo_tv_landscape_card else R.layout.nexo_tv_poster_card, parent, false))

    override fun onBindViewHolder(holder: Card, position: Int) {
        val item = getItem(position)
        holder.itemView.apply {
            tag = item.tvItemKey
            contentDescription = item.name
            scaleX = if (hasFocus()) 1.04f else 1f
            scaleY = scaleX
            setOnClickListener { clicked(item) }
            setOnLongClickListener { longClicked(item) }
            setOnFocusChangeListener { view, hasFocus ->
                view.scaleX = if (hasFocus) 1.04f else 1f
                view.scaleY = view.scaleX
                if (hasFocus) focused(item, view)
            }
        }
        holder.title.text = item.name
        if (item.posterUrl.isNullOrBlank()) holder.image.loadImage(R.drawable.default_cover)
        else holder.image.loadImage(item.posterUrl, item.posterHeaders) { error { getImageFromDrawable(holder.itemView.context, R.drawable.default_cover) } }
    }
    override fun onViewRecycled(holder: Card) {
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        holder.itemView.setOnFocusChangeListener(null)
        super.onViewRecycled(holder)
    }
    class Card(view: View) : RecyclerView.ViewHolder(view) {
        init { view.id = View.generateViewId() }
        val image: ImageView = view.findViewById(R.id.nexo_tv_card_image)
        val title: TextView = view.findViewById(R.id.nexo_tv_card_title)
    }
}

