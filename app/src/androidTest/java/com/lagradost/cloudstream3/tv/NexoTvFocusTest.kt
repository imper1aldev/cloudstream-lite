package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.home.HomeFragment2
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import org.junit.Assert.*
import org.junit.Test

class NexoTvFocusTest {
    @Test fun cardsUseNativeRemoteAndRailOwnership() {
        val adapterClass = try { Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvCardAdapter") }
            catch (e: ClassNotFoundException) { throw AssertionError("TV card components missing", e) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.app_layout_key)
        val oldLayout = prefs.all[key]
        prefs.edit().putInt(key, 1).commit(); context.updateTv()
        if (android.os.Build.VERSION.SDK_INT <= 32) listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE").forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        var clicks = 0
        var longClicks = 0
        val items = (0..29).map { (object : MainAPI() {}).newMovieSearchResponse("Card $it", "https://tv-focus.invalid/$it", TvType.Movie).apply { if (it % 3 == 1) posterUrl = "file:///nexo-tv-fixture-missing-cover.jpg" } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var list: RecyclerView
                fun await(message: String, condition: (MainActivity) -> Boolean) {
                    val end = SystemClock.elapsedRealtime() + 10_000
                    while (SystemClock.elapsedRealtime() < end) {
                        var done = false; scenario.onActivity { done = condition(it) }
                        if (done) return
                        SystemClock.sleep(50)
                    }
                    fail(message)
                }
                fun press(code: Int) = instrumentation.sendKeyDownUpSync(code)
                fun position(): Int { val child = list.focusedChild ?: return -1; return list.getChildAdapterPosition(child) }
                await("Home missing") { activity ->
                    val nav = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                    nav?.childFragmentManager?.primaryNavigationFragment is HomeFragment2
                }
                scenario.onActivity { activity ->
                    val host = activity.findViewById<ViewGroup>(R.id.home_lite_legacy_host)
                    for (i in 0 until host.childCount) host.getChildAt(i).visibility = View.GONE
                    list = RecyclerView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(-1, (264*resources.displayMetrics.density).toInt())
                        clipToPadding = false
                        setPadding(24, 24, 24, 24)
                        setLinearListLayout(nextLeft = R.id.nav_rail_view, nextRight = FOCUS_SELF)
                        val click: (SearchResponse) -> Unit = { clicks++ }
                        val longClick: (SearchResponse) -> Boolean = { longClicks++; true }
                        val focused: (SearchResponse, View) -> Unit = { _, _ -> }
                        adapter = adapterClass.constructors.first { it.parameterCount == 4 }
                            .newInstance(false, click, longClick, focused) as RecyclerView.Adapter<*>
                        adapterClass.getMethod("submitList", List::class.java).invoke(adapter, items)
                    }
                    host.addView(list)
                }
                await("Cards not laid out") { list.findViewHolderForAdapterPosition(0) != null }
                scenario.onActivity { list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
                await("First card focus missing") { position() == 0 && it.currentFocus?.isFocused == true }
                scenario.onActivity { assertTrue(list.focusedChild!!.background.state.contains(android.R.attr.state_focused)); assertTrue(list.focusedChild!!.scaleX > 1f) }
                press(KeyEvent.KEYCODE_DPAD_CENTER); press(KeyEvent.KEYCODE_ENTER)
                await("OK and Enter must each click once") { clicks == 2 }
                val down = SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 250)
                instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                await("Long press not delivered") { longClicks == 1 }
                scenario.onActivity { assertEquals("Long press also clicked", 2, clicks) }
                repeat(9) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
                await("Repeated remote arrows failed") { position() == 9 }
                scenario.onActivity { list.layoutDirection = View.LAYOUT_DIRECTION_RTL; list.scrollToPosition(0) }
                await("RTL first card not laid out") { list.findViewHolderForAdapterPosition(0) != null }
                scenario.onActivity { list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
                repeat(5) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
                await("RTL direction failed") { position() == 5 }
                scenario.onActivity { activity ->
                    val rail = activity.findViewById<View>(R.id.nav_rail_view)
                    val original = rail.nextFocusRightId
                    val card0 = list.findViewHolderForAdapterPosition(5)!!.itemView
                    val card1 = list.findViewHolderForAdapterPosition(6)!!.itemView
                    val bind = Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvFocusKt")
                        .getMethod("bindNexoTvRail", MainActivity::class.java, Int::class.javaPrimitiveType)
                    @Suppress("UNCHECKED_CAST")
                    val oldRelease = bind.invoke(null, activity, card0.id) as () -> Unit
                    @Suppress("UNCHECKED_CAST")
                    val newRelease = bind.invoke(null, activity, card1.id) as () -> Unit
                    oldRelease()
                    assertEquals("Old screen overwrote new entry", card1.id, rail.nextFocusRightId)
                    newRelease()
                    assertEquals("Released entry restored destroyed screen", original, rail.nextFocusRightId)
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.getExternalFilesDir(null), "nexo-tv-components.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
        } finally {
            val edit = prefs.edit(); if (oldLayout is Int) edit.putInt(key, oldLayout) else edit.remove(key)
            edit.commit(); context.updateTv()
        }
    }
}
