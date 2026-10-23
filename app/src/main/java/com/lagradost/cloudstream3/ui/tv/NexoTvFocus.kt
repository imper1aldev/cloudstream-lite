package com.lagradost.cloudstream3.ui.tv

import android.view.View
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse

/** Presentation state only; never persist an adapter position as the item's identity. */
data class TvFocusAnchor(val sectionKey: String, val itemKey: String, val fallbackIndex: Int)

fun resolveTvFocusIndex(itemKeys: List<String>, anchor: TvFocusAnchor?): Int? {
    if (itemKeys.isEmpty()) return null
    return anchor?.let { saved -> itemKeys.indexOf(saved.itemKey).takeIf { it >= 0 }
        ?: saved.fallbackIndex.coerceIn(itemKeys.indices) } ?: 0
}

internal val SearchResponse.tvItemKey: String get() = "$apiName\u0000$url"

private class RailLink(val target: Int, val baseline: Int, val previous: RailLink?) {
    var active = true
}

/** Release can run after a newer screen bound the rail: it must not undo that screen. */
fun bindNexoTvRail(activity: MainActivity, entryViewId: Int): () -> Unit {
    val rail = activity.findViewById<View>(R.id.nav_rail_view) ?: return {}
    val views = listOfNotNull(rail, rail.findViewById(R.id.navigation_home), rail.findViewById(R.id.navigation_search),
        rail.findViewById(R.id.navigation_library), rail.findViewById(R.id.navigation_settings))
    val links = views.map { view ->
        val previous = (view.getTag(R.id.nexo_tv_rail_link) as? RailLink)?.takeIf { it.target == view.nextFocusRightId }
        val link = RailLink(entryViewId, previous?.baseline ?: view.nextFocusRightId, previous)
        view.setTag(R.id.nexo_tv_rail_link, link)
        view.nextFocusRightId = entryViewId
        view to link
    }
    return {
        links.forEach { (view, link) ->
            link.active = false
            if (view.getTag(R.id.nexo_tv_rail_link) === link) {
                var previous = link.previous
                while (previous != null && !previous.active) previous = previous.previous
                if (view.nextFocusRightId == link.target) view.nextFocusRightId = previous?.target ?: link.baseline
                view.setTag(R.id.nexo_tv_rail_link, previous)
            }
        }
    }
}
