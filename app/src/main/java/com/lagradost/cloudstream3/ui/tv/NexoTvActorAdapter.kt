package com.lagradost.cloudstream3.ui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

/** Informational portraits; remote focus belongs to the scrollable row, not fake actor actions. */
class NexoTvActorAdapter : ListAdapter<ActorData, NexoTvActorAdapter.Portrait>(object : DiffUtil.ItemCallback<ActorData>() {
    override fun areItemsTheSame(old: ActorData, new: ActorData) = old.actor.name == new.actor.name && old.roleString == new.roleString
    override fun areContentsTheSame(old: ActorData, new: ActorData) = old == new
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Portrait(LayoutInflater.from(parent.context).inflate(R.layout.nexo_tv_actor, parent, false))
    override fun onBindViewHolder(holder: Portrait, position: Int) {
        val item = getItem(position)
        holder.name.text = item.actor.name
        val role = item.roleString?.takeIf { it.isNotBlank() } ?: item.role?.let {
            holder.itemView.context.getString(when (it) {
                ActorRole.Main -> R.string.actor_main
                ActorRole.Supporting -> R.string.actor_supporting
                ActorRole.Background -> R.string.actor_background
            })
        }
        holder.role.text = role
        holder.role.isVisible = role != null
        if (item.actor.image.isNullOrBlank()) holder.image.loadImage(R.drawable.ic_outline_account_circle_24)
        else holder.image.loadImage(item.actor.image) {
            placeholder { getImageFromDrawable(holder.itemView.context, R.drawable.ic_outline_account_circle_24) }
            error { getImageFromDrawable(holder.itemView.context, R.drawable.ic_outline_account_circle_24) }
        }
    }
    override fun onViewRecycled(holder: Portrait) {
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        super.onViewRecycled(holder)
    }
    class Portrait(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.nexo_tv_actor_image)
        val name: TextView = view.findViewById(R.id.nexo_tv_actor_name)
        val role: TextView = view.findViewById(R.id.nexo_tv_actor_role)
    }
}
