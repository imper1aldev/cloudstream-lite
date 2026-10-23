package com.lagradost.cloudstream3.ui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.getDisplayPosition
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

internal val ResultEpisode.tvEpisodeKey: String get() = "$apiName\u0000$data\u0000$id"

class NexoTvEpisodeAdapter(
    private val clicked: (ResultEpisode) -> Unit,
    private val longClicked: (ResultEpisode) -> Unit,
    private val focused: (ResultEpisode, View) -> Unit,
) : ListAdapter<ResultEpisode, NexoTvEpisodeAdapter.Episode>(object : DiffUtil.ItemCallback<ResultEpisode>() {
    override fun areItemsTheSame(a: ResultEpisode, b: ResultEpisode) = a.tvEpisodeKey == b.tvEpisodeKey
    override fun areContentsTheSame(a: ResultEpisode, b: ResultEpisode) = a == b
}) {
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Episode(LayoutInflater.from(parent.context).inflate(R.layout.nexo_tv_episode, parent, false))
    override fun onBindViewHolder(holder: Episode, position: Int) {
        val ep = getItem(position)
        holder.itemView.apply {
            tag = ep.tvEpisodeKey
            contentDescription = context.getNameFull(ep.name, ep.episode, ep.season)
            setOnClickListener { clicked(ep) }
            setOnLongClickListener { longClicked(ep); true }
            setOnFocusChangeListener { v, has -> if (has) focused(ep, v) }
        }
        holder.title.text = holder.itemView.context.getNameFull(ep.name, ep.episode, ep.season)
        holder.description.text = ep.description
        holder.progress.progress = if (ep.duration > 0) (ep.getDisplayPosition() * 100 / ep.duration).toInt() else 0
        if (ep.poster.isNullOrBlank()) holder.image.loadImage(R.drawable.default_cover)
        else holder.image.loadImage(ep.poster) { error { getImageFromDrawable(holder.itemView.context, R.drawable.default_cover) } }
    }
    override fun onViewRecycled(holder: Episode) { holder.image.dispose(); holder.itemView.onFocusChangeListener = null; super.onViewRecycled(holder) }
    class Episode(view: View) : RecyclerView.ViewHolder(view) {
        init { view.id = View.generateViewId() }
        val image: ImageView = view.findViewById(R.id.nexo_tv_episode_image)
        val title: TextView = view.findViewById(R.id.nexo_tv_episode_title)
        val description: TextView = view.findViewById(R.id.nexo_tv_episode_description)
        val progress: ProgressBar = view.findViewById(R.id.nexo_tv_episode_progress)
    }
}
