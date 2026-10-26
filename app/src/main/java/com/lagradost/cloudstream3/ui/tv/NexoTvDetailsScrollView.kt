package com.lagradost.cloudstream3.ui.tv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView
import com.lagradost.cloudstream3.R

/** Keep hero actions visible without MainActivity.centerView moving the title offscreen. */
class NexoTvDetailsScrollView(context: Context, attrs: AttributeSet?) : NestedScrollView(context, attrs) {
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        val heroAction = when (findFocus()?.id) {
            R.id.nexo_tv_result_play, R.id.nexo_tv_result_favorite, R.id.nexo_tv_result_more -> true
            else -> false
        }
        // centerView asks for a screen-height rectangle; native focus only asks for the control.
        if (heroAction && height > 0 && rectangle.height() >= height) return false
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }
}
