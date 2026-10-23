package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.home.HomeFragment2
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.AtomicList
import org.junit.Assert.*
import org.junit.Test

/** Debug-only integration probe: real MainActivity/remote/legacy Details, no media hosts. */
class NexoTvProbeTest {
    private class Provider : MainAPI() {
        override var name = "NexoStream"
        override var mainUrl = "https://nexo-tv-probe.invalid"
        override val hasMainPage = true
        override val hasChromecastSupport = false
        override val hasDownloadSupport = false
        override val mainPage = mainPageOf("0" to "TV fixture 1", "1" to "TV fixture 2")
        override suspend fun getMainPage(page: Int, request: MainPageRequest) = newHomePageResponse(request.name,
            (0..19).map { newMovieSearchResponse("TV probe ${request.data}/$it", "$mainUrl/${request.data}/$it", TvType.Movie) }, false)
        override suspend fun load(url: String) = newMovieLoadResponse("TV probe ${url.removePrefix("$mainUrl/")}", url, TvType.Movie, "unused") {
            plot = "Local TV navigation fixture. No playback or network extraction."
        }
    }

    @Test fun probeUsesRealTvMenuAndRestoresIntermediateCard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probeClass = try { Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvProbeFragment") }
            catch (_: ClassNotFoundException) { throw AssertionError("The isolated debug TV probe has not been implemented") }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val layoutKey = context.getString(R.string.app_layout_key)
        val oldLayout = prefs.all[layoutKey]
        val oldProviders = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        val oldApis = APIHolder.apis
        val provider = Provider()
        fun register() {
            APIHolder.allProviders.withLock { APIHolder.allProviders.clear(); APIHolder.allProviders.add(provider) }
            APIHolder.apis = AtomicList<MainAPI>(listOf(provider)); APIHolder.apiMap = null
        }
        fun key(code: Int) { instrumentation.sendKeyDownUpSync(code) }
        if (android.os.Build.VERSION.SDK_INT <= 32) {
            listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE").forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
        }
        prefs.edit().putInt(layoutKey, 1).commit(); context.updateTv(); register()
        val started = SystemClock.elapsedRealtime()
        val evidence = java.io.File(context.getExternalFilesDir(null), "nexo-tv-probe").apply { mkdirs() }
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun screenshot(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(evidence, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun nav(activity: MainActivity) = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
                fun probe(activity: MainActivity): Fragment? = (nav(activity).childFragmentManager.primaryNavigationFragment as? HomeFragment2)
                    ?.childFragmentManager?.findFragmentByTag("nexo-tv-probe")
                fun await(message: String, condition: (MainActivity) -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        var done = false
                        scenario.onActivity { done = condition(it) }
                        if (done) return
                        SystemClock.sleep(60)
                    }
                    scenario.onActivity { activity ->
                        android.util.Log.e("NexoTvProbe", "$message dest=${nav(activity).navController.currentDestination} focus=${activity.currentFocus} tag=${activity.currentFocus?.tag} fragments=${nav(activity).childFragmentManager.fragments}")
                    }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(context.getExternalFilesDir(null), "probe-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                    fail(message)
                }
                fun selected(row: Int, item: Int) = "${provider.mainUrl}/$row/$item"
                fun awaitCard(row: Int, item: Int) = await("Focus did not return to $row/$item") { it.currentFocus?.tag == selected(row, item) }
                fun settledCard(row: Int, item: Int): String {
                    awaitCard(row, item)
                    var previous = ""
                    var stableSince = SystemClock.elapsedRealtime()
                    var result = ""
                    await("Focused card $row/$item did not settle fully visible") { activity ->
                        val card = activity.currentFocus
                        val list = card?.parent as? androidx.recyclerview.widget.RecyclerView
                        val rect = android.graphics.Rect()
                        val visible = card?.getGlobalVisibleRect(rect) == true && rect.width() == card.width && rect.height() == card.height
                        val state = if (visible && list?.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE && !list.isComputingLayout)
                            "${rect.flattenToString()}:${list.computeHorizontalScrollOffset()}:${list.getChildAdapterPosition(card!!)}" else ""
                        if (state.isEmpty() || state != previous) stableSince = SystemClock.elapsedRealtime()
                        previous = state
                        result = state
                        state.isNotEmpty() && SystemClock.elapsedRealtime() - stableSince >= 500
                    }
                    return result
                }
                fun select(row: Int, item: Int) {
                    scenario.onActivity { activity ->
                        val p = checkNotNull(probe(activity))
                        probeClass.getMethod("focusCard", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(p, row, item)
                    }
                    awaitCard(row, item)
                }
                await("Home TV host missing") { nav(it).childFragmentManager.primaryNavigationFragment is HomeFragment2 }
                scenario.onActivity { activity ->
                    register()
                    ViewModelProvider(activity)[HomeViewModel::class.java].loadAndCancel(provider.name, forceReload = true)
                }
                await("Legacy fixture catalog did not load") { activity ->
                    val page = ViewModelProvider(activity)[HomeViewModel::class.java].page.value
                    page is Resource.Success && page.value.size == 2
                }
                fun measureMenu(name: String) {
                    scenario.onActivity { it.findViewById<View>(R.id.navigation_home).requestFocus() }
                    await("Menu benchmark entry missing") { it.currentFocus?.id == R.id.navigation_home }
                    shell("dumpsys gfxinfo ${context.packageName} reset")
                    repeat(10) {
                        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                        await("Menu benchmark bottom missing") { it.currentFocus?.id == R.id.navigation_settings }
                        repeat(3) { key(KeyEvent.KEYCODE_DPAD_UP) }
                        await("Menu benchmark top missing") { it.currentFocus?.id == R.id.navigation_home }
                    }
                    java.io.File(evidence, "$name-memory.txt").writeText(shell("dumpsys meminfo ${context.packageName}"))
                    java.io.File(evidence, "$name-frames.txt").writeText(shell("dumpsys gfxinfo ${context.packageName}"))
                    screenshot(name)
                }
                measureMenu("legacy-menu")
                scenario.onActivity { activity ->
                    register()
                    val host = nav(activity).childFragmentManager.primaryNavigationFragment as HomeFragment2
                    host.childFragmentManager.beginTransaction()
                        .replace(R.id.home_lite_legacy_host, probeClass.getDeclaredConstructor().newInstance() as Fragment, "nexo-tv-probe")
                        .commitNow()
                }
                awaitCard(0, 0)
                measureMenu("probe-menu")
                key(KeyEvent.KEYCODE_DPAD_RIGHT); awaitCard(0, 0)
                select(0, 19); key(KeyEvent.KEYCODE_DPAD_RIGHT); awaitCard(0, 19)
                repeat(50) {
                    select(0, 0)
                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                    await("LEFT did not reach selected Home menu item, cycle $it") { activity -> activity.currentFocus?.id == R.id.navigation_home }
                    key(KeyEvent.KEYCODE_DPAD_RIGHT); awaitCard(0, 0)
                }
                select(0, 7); key(KeyEvent.KEYCODE_DPAD_DOWN); awaitCard(1, 0)
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                awaitCard(1, 3); key(KeyEvent.KEYCODE_DPAD_UP); awaitCard(0, 7)
                key(KeyEvent.KEYCODE_DPAD_DOWN); awaitCard(1, 3)
                repeat(20) { cycle ->
                    select(1, 7)
                    val before = settledCard(1, 7)
                    key(if (cycle % 2 == 0) KeyEvent.KEYCODE_DPAD_CENTER else KeyEvent.KEYCODE_ENTER)
                    await("Legacy TV Details did not load the selected identity, cycle $cycle") { activity ->
                        nav(activity).navController.currentDestination?.id == R.id.navigation_results_tv &&
                            activity.findViewById<TextView>(R.id.result_title)?.text?.toString() == "TV probe 1/7"
                    }
                    key(KeyEvent.KEYCODE_BACK); awaitCard(1, 7)
                    assertEquals("Details return changed card geometry/scroll, cycle $cycle", before, settledCard(1, 7))
                }
                val beforeRecreate = settledCard(1, 7)
                scenario.recreate()
                awaitCard(1, 7)
                assertEquals("Recreation changed card geometry/scroll", beforeRecreate, settledCard(1, 7))
                scenario.onActivity { activity -> probeClass.getMethod("removeFocusedCard").invoke(checkNotNull(probe(activity))) }
                awaitCard(1, 8)
                screenshot("probe-final")
                java.io.File(evidence, "nexo-tv-probe.json").writeText(org.json.JSONObject()
                    .put("menu_round_trips", 50).put("details_round_trips", 20).put("recreated", true)
                    .put("geometry_and_scroll_restored", true).put("stable_visible_ms", 500)
                    .put("removed_card_fallback", "1/8").put("elapsed_ms", SystemClock.elapsedRealtime()-started).toString(2))
            }
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.clear(); APIHolder.allProviders.addAll(oldProviders) }
            APIHolder.apis = oldApis; APIHolder.apiMap = null
            val edit = prefs.edit(); if (oldLayout is Int) edit.putInt(layoutKey, oldLayout) else edit.remove(layoutKey)
            edit.commit(); context.updateTv()
        }
    }
}

