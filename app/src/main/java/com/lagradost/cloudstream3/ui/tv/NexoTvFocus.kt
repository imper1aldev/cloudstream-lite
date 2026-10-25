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

/** Only the borders between zones override the existing grid's native arrow navigation. */
internal fun bindNexoTvGridEdges(card: View, grid: androidx.recyclerview.widget.RecyclerView, above: View, menu: View?, below: () -> View? = { null }) {
    card.setOnKeyListener { _, key, event ->
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
        val manager = grid.layoutManager as? androidx.recyclerview.widget.GridLayoutManager ?: return@setOnKeyListener false
        val position = grid.getChildAdapterPosition(card)
        if (position < 0) return@setOnKeyListener false
        val menuKey = if (grid.layoutDirection == View.LAYOUT_DIRECTION_RTL) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
        val target = when {
            key == menuKey && position % manager.spanCount == 0 -> menu
            key == android.view.KeyEvent.KEYCODE_DPAD_UP && position < manager.spanCount -> above
            key == android.view.KeyEvent.KEYCODE_DPAD_DOWN && position + manager.spanCount >= (grid.adapter?.itemCount ?: 0) -> below()
            else -> null
        }
        target?.takeIf { it.isShown && it.isFocusable && it.isEnabled }?.requestFocus() == true
    }
}

/** A delayed default/return focus must yield to subsequent remote navigation. */
internal fun androidx.fragment.app.Fragment.cancelNexoTvPendingFocus(root: View, vararg controls: View, cancel: () -> Unit) {
    fun View.inside(parent: View): Boolean = generateSequence(this) { it.parent as? View }.any { it === parent }
    val listener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { old, next ->
        val rail = activity?.findViewById<View>(R.id.nav_rail_view)
        if (isResumed && old != null && next != null && old !== next && (
                (rail != null && next.inside(rail) && (old.inside(root) || old.inside(rail))) ||
                (controls.any { it === next } && old.inside(root)))) cancel()
    }
    root.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
    controls.forEach { control -> control.setOnKeyListener { _, key, event ->
        if (event.action == android.view.KeyEvent.ACTION_DOWN && key in listOf(
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) cancel()
        false // Let native navigation carry out the move.
    } }
    viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
            controls.forEach { it.setOnKeyListener(null) }
        }
    })
}
