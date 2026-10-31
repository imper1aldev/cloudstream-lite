package com.lagradost.cloudstream3.ui.tv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import androidx.core.widget.NestedScrollView
import com.lagradost.cloudstream3.R

/** Frame the focused Details section, independently of the global per-control centering. */
class NexoTvDetailsScrollView(context: Context, attrs: AttributeSet?) : NestedScrollView(context, attrs) {
    private fun sectionAnchor(focused: View?): View? {
        var view = focused
        while (view != null && view !== this) {
            when (view.id) {
                R.id.nexo_tv_result_hero -> return view
                R.id.nexo_tv_result_episode_controls, R.id.nexo_tv_result_episodes ->
                    return findViewById<View>(R.id.nexo_tv_result_episode_controls).takeIf { it.visibility == View.VISIBLE }
                        ?: findViewById(R.id.nexo_tv_result_episode_status)
                R.id.nexo_tv_result_actors -> return findViewById(R.id.nexo_tv_result_actor_header)
                R.id.nexo_tv_result_recommendations -> return findViewById(R.id.nexo_tv_result_recommendation_title)
            }
            view = view.parent as? View
        }
        return null
    }
    override fun arrowScroll(direction: Int): Boolean {
        val current = findFocus()
        if ((direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) && sectionAnchor(current) != null) {
            val next = FocusFinder.getInstance().findNextFocus(this, current, direction)
            // NestedScrollView otherwise scrolls half a viewport before allowing a distant focus target.
            if (next != null && sectionAnchor(next) != null) return next.requestFocus(direction)
        }
        return super.arrowScroll(direction)
    }
    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        post {
            if (findFocus() !== focused || height <= 0) return@post
            val anchor = sectionAnchor(focused) ?: return@post
            val section = Rect().also { anchor.getDrawingRect(it); offsetDescendantRectToMyCoords(anchor, it) }
            val control = Rect().also { focused.getDrawingRect(it); offsetDescendantRectToMyCoords(focused, it) }
            val inset = (24 * resources.displayMetrics.density).toInt()
            // Tall text still needs to reveal the actual control, even if the whole hero cannot fit.
            val desired = maxOf(section.top - inset, control.bottom - height + inset)
                .coerceAtMost(control.top - inset).coerceAtLeast(0)
            smoothScrollTo(0, desired)
        }
    }
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        // MainActivity.centerView requests a screen-height rectangle around each individual control.
        if (height > 0 && rectangle.height() >= height && sectionAnchor(findFocus()) != null) return false
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }
}
