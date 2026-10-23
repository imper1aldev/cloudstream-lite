package com.lagradost.cloudstream3.ui.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult

/** Throwaway debug fixture, mounted by instrumentation only. No production route. */
class NexoTvProbeFragment : Fragment() {
    private val rows = arrayOf((0..19).toMutableList(), (0..19).toMutableList())
    private val selected = intArrayOf(0, 0)
    private val scroll = arrayOfNulls<Parcelable>(2)
    private var lastRow = 0
    private var restoring = true
    private val lists = arrayOfNulls<RecyclerView>(2)
    private val railPrevious = mutableMapOf<View, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { saved ->
            lastRow = saved.getInt("row")
            saved.getIntArray("selected")?.copyInto(selected)
            for (row in 0..1) {
                saved.getIntArray("items$row")?.let { rows[row].clear(); rows[row].addAll(it.toList()) }
                @Suppress("DEPRECATION")
                scroll[row] = saved.getParcelable("scroll$row")
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.nexo_tv_probe, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoring = true
        for ((row, id) in listOf(R.id.nexo_tv_probe_first, R.id.nexo_tv_probe_second).withIndex()) {
            lists[row] = view.findViewById<RecyclerView>(id).apply {
                itemAnimator = null
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                setLinearListLayout(nextLeft = R.id.nav_rail_view, nextRight = FOCUS_SELF)
                adapter = Cards(row)
                layoutManager?.onRestoreInstanceState(scroll[row])
            }
        }
        val restoreRow = lastRow
        val restoreIndex = selected[restoreRow]
        view.post { focusCard(restoreRow, restoreIndex) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveScroll()
        outState.putInt("row", lastRow)
        outState.putIntArray("selected", selected)
        for (row in 0..1) {
            outState.putIntArray("items$row", rows[row].toIntArray())
            outState.putParcelable("scroll$row", scroll[row])
        }
        super.onSaveInstanceState(outState)
    }

    private fun saveScroll() { for (row in 0..1) scroll[row] = lists[row]?.layoutManager?.onSaveInstanceState() ?: scroll[row] }

    private fun bindRail(target: Int) {
        val rail = activity?.findViewById<View>(R.id.nav_rail_view) ?: return
        val views = listOfNotNull(rail, rail.findViewById(R.id.navigation_home), rail.findViewById(R.id.navigation_search),
            rail.findViewById(R.id.navigation_library), rail.findViewById(R.id.navigation_settings))
        for (item in views) {
            railPrevious.putIfAbsent(item, item.nextFocusRightId)
            item.nextFocusRightId = target
        }
    }

    fun focusCard(row: Int, index: Int) {
        val list = lists[row] ?: return
        val position = index.coerceIn(0, rows[row].lastIndex)
        val child = list.layoutManager?.findViewByPosition(position)
        if (child != null) { restoring = false; child.requestFocus() }
        else {
            list.scrollToPosition(position)
            list.post { list.layoutManager?.findViewByPosition(position)?.let { restoring = false; it.requestFocus() } }
        }
    }

    fun removeFocusedCard() {
        val row = lastRow
        val position = selected[row].coerceIn(0, rows[row].lastIndex)
        rows[row].removeAt(position)
        lists[row]?.adapter?.notifyItemRemoved(position)
        lists[row]?.post { focusCard(row, position) }
    }

    override fun onDestroyView() {
        restoring = true
        saveScroll()
        railPrevious.forEach { (item, previous) -> item.nextFocusRightId = previous }
        railPrevious.clear()
        for (row in 0..1) { lists[row]?.adapter = null; lists[row] = null }
        super.onDestroyView()
    }

    private inner class Cards(private val row: Int) : RecyclerView.Adapter<Card>() {
        override fun getItemCount() = rows[row].size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card {
            val density = parent.resources.displayMetrics.density
            fun dp(value: Int) = (value*density).toInt()
            val label = TextView(parent.context).apply {
                id = View.generateViewId()
                layoutParams = RecyclerView.LayoutParams(dp(136), dp(116)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                isFocusable = true
                isFocusableInTouchMode = true
                background = StateListDrawable().apply {
                    fun shape(focused: Boolean) = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (focused) Color.rgb(42,57,92) else Color.rgb(30,32,39))
                        setStroke(dp(if (focused) 3 else 1), if (focused) Color.WHITE else Color.DKGRAY)
                    }
                    addState(intArrayOf(android.R.attr.state_focused), shape(true))
                    addState(intArrayOf(), shape(false))
                }
            }
            return Card(label)
        }
        override fun onBindViewHolder(holder: Card, position: Int) {
            val label = holder.itemView as TextView
            val value = rows[row][position]
            val url = "https://nexo-tv-probe.invalid/$row/$value"
            label.text = "Fila ${row+1}\nTarjeta $value"
            label.tag = url
            label.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !restoring) {
                    lastRow = row
                    selected[row] = holder.bindingAdapterPosition.coerceAtLeast(0)
                    bindRail(label.id)
                }
            }
            label.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (row == 1) focusCard(0, selected[0]); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (row == 0) focusCard(1, selected[1]); true }
                    else -> false
                }
            }
            label.setOnClickListener {
                lastRow = row
                selected[row] = holder.bindingAdapterPosition
                restoring = true
                loadResult(url, "NexoStream", "TV probe $row/$value")
            }
        }
    }
    private class Card(view: View) : RecyclerView.ViewHolder(view)
}


