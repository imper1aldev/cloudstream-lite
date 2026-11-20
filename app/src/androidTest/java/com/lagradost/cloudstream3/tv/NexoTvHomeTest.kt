package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.AtomicList
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NexoTvHomeTest {
    private class Provider : MainAPI() {
        override var name = "NexoStream"
        override var mainUrl = "https://nexo-tv-home.invalid"
        override val hasMainPage = true
        override val mainPage = mainPageOf("first" to "TV first", "second" to "TV second", "third" to "TV third")
        @Volatile var empty = true
        val firstLoads = AtomicInteger()
        val releaseInitialCatalog = kotlinx.coroutines.CompletableDeferred<Unit>()
        val failSecond = AtomicInteger(1)
        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            if (page == 1) {
                firstLoads.incrementAndGet()
                if (!empty) releaseInitialCatalog.await()
            }
            kotlinx.coroutines.delay(80)
            if (page == 2 && request.data == "first" && failSecond.getAndDecrement() > 0) error("Temporary fixture page failure")
            val count = when (request.data) {
                "second" -> 16
                "third" -> 8
                else -> 12
            }
            val items = if (empty) emptyList() else (0 until count).map { offset ->
                val id = (page-1)*12+offset
                newMovieSearchResponse("${request.name} $id", "$mainUrl/${request.data}/$id", TvType.Movie)
            }
            return newHomePageResponse(request.name, items, !empty && request.data == "first" && page < 3)
        }
        override suspend fun load(url: String) = newAnimeLoadResponse("Home ${url.substringAfterLast('/')}", url, TvType.Anime, comingSoonIfNone = false) {
            year = 2024
            tags = listOf("Aventura")
            plot = "Local Home navigation fixture"
        }
    }
    @Test fun lateCatalogPaginationSectionAndReturnKeepFocus() {
        try { Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvHomeFragment") }
        catch (e: ClassNotFoundException) { throw AssertionError("Own TV Home missing", e) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val layoutKey = context.getString(R.string.app_layout_key)
        val previousLayout = prefs.all[layoutKey]
        val store=context.getSharedPrefs()
        val installed=store.getString("PLUGINS_KEY",null)
        val locals=store.getString("PLUGINS_KEY_LOCAL",null)
        val updateKey=context.getString(R.string.auto_update_plugins_key)
        val previousUpdate=prefs.all[updateKey]
        store.edit().remove("PLUGINS_KEY").remove("PLUGINS_KEY_LOCAL").commit()
        prefs.edit().putBoolean(updateKey,false).commit()
        val oldProviders = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        val oldApis = APIHolder.apis
        val favoriteId = -7753905
        val oldAccounts = DataStoreHelper.accounts
        val oldSelected = DataStoreHelper.selectedKeyIndex
        val testProfile = generateSequence(-7753906) { it-1 }.first { index -> oldAccounts.none { it.keyIndex==index } && CloudStreamApp.getKeys(index.toString()).isNullOrEmpty() }
        val emptyProfile = generateSequence(testProfile-1) { it-1 }.first { index -> oldAccounts.none { it.keyIndex==index } && CloudStreamApp.getKeys(index.toString()).isNullOrEmpty() }
        val profile = DataStoreHelper.Account(testProfile,"TV Home fixture",defaultImageIndex=0)
        val emptyAccount = DataStoreHelper.Account(emptyProfile,"TV empty fixture",defaultImageIndex=0)
        DataStoreHelper.accounts = oldAccounts + profile + emptyAccount
        DataStoreHelper.selectedKeyIndex = testProfile
        val provider = Provider()
        fun register() {
            APIHolder.allProviders.withLock { APIHolder.allProviders.clear(); APIHolder.allProviders.add(provider) }
            APIHolder.apis = AtomicList<MainAPI>(listOf(provider)); APIHolder.apiMap = null
        }
        fun press(key: Int) = instrumentation.sendKeyDownUpSync(key)
        fun capture(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
        }
        fun id(name: String) = context.resources.getIdentifier(name, "id", context.packageName)
        prefs.edit().putInt(layoutKey, 1).commit(); context.updateTv(); register()
        if (android.os.Build.VERSION.SDK_INT <= 32) listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE").forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun nav(a: MainActivity) = a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                fun vm(a: MainActivity) = ViewModelProvider(a)[HomeViewModel::class.java]
                fun await(message: String, condition: (MainActivity) -> Boolean) {
                    val end = SystemClock.elapsedRealtime()+15_000
                    while (SystemClock.elapsedRealtime()<end) {
                        var done = false; scenario.onActivity { done=condition(it) }; if (done) return
                        SystemClock.sleep(60)
                    }
                    scenario.onActivity { android.util.Log.e("NexoTvHomeTest", "$message focus=${it.currentFocus} tag=${it.currentFocus?.tag} dest=${nav(it).navController.currentDestination}") }
                    fail(message)
                }
                await("Empty catalog state missing") { it.findViewById<View>(id("nexo_tv_home_status"))?.visibility == View.VISIBLE && vm(it).page.value is Resource.Success }
                scenario.onActivity { register(); provider.empty=false; MainActivity.afterPluginsLoadedEvent.invoke(true) }
                try {
                    await("Skeleton missing during delayed initial catalog") {
                        it.findViewById<View>(id("nexo_tv_home_skeleton"))?.isShown == true &&
                            it.findViewById<View>(id("nexo_tv_home_list"))?.isShown == false
                    }
                    capture("nexo-tv-home-slow")
                } finally {
                    provider.releaseInitialCatalog.complete(Unit)
                }
                await("Late provider/catalog left No Data") { (vm(it).page.value as? Resource.Success)?.value?.get("TV first")?.list?.list?.size == 12 && it.findViewById<View>(id("nexo_tv_home_status"))?.visibility == View.GONE }
                await("Skeleton remains after initial catalog arrives") {
                    it.findViewById<View>(id("nexo_tv_home_skeleton"))?.isShown == false &&
                        it.findViewById<View>(id("nexo_tv_home_list"))?.isShown == true
                }
                await("Hero title was not laid out") { a -> a.findViewById<android.widget.TextView>(id("nexo_tv_hero_title"))?.let { it.isShown && it.width > 0 && it.height > 0 } == true }
                val titleBounds = android.graphics.Rect()
                scenario.onActivity { it.findViewById<View>(id("nexo_tv_hero_title")).getGlobalVisibleRect(titleBounds) }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    var glyphs=0
                    for (y in titleBounds.top until titleBounds.bottom) for (x in titleBounds.left until titleBounds.right) {
                        val pixel=bitmap.getPixel(x,y)
                        if (android.graphics.Color.red(pixel)>220 && android.graphics.Color.green(pixel)>220 && android.graphics.Color.blue(pixel)>220) glyphs++
                    }
                    bitmap.recycle()
                    assertTrue("Focused hero foreground covers its title",glyphs>30)
                }
                await("Anime metadata missing from the featured title") { activity ->
                    val metadata = activity.findViewById<android.widget.TextView>(id("nexo_tv_hero_metadata"))
                    metadata?.isShown == true &&
                        metadata.text.toString() == listOf(activity.getString(R.string.anime_singular), "2024", "Aventura").joinToString(" · ")
                }
                capture("nexo-tv-home-anime")
                capture("nexo-tv-home-initial")
                lateinit var master: RecyclerView
                scenario.onActivity { a -> master=a.findViewById(id("nexo_tv_home_list")) }
                for ((index, key) in listOf("TV first", "TV second", "TV third").withIndex()) {
                    scenario.onActivity { master.scrollToPosition(index+1) }
                    await("Plugin section order changed at $index") { master.findViewHolderForAdapterPosition(index+1)?.itemView?.tag == "section:$key" }
                    val expectedCards = listOf(13, 16, 8)[index]
                    await("Unexpected card count for $key") {
                        val section = master.findViewHolderForAdapterPosition(index + 1)?.itemView
                        section?.findViewById<RecyclerView>(id("nexo_tv_row_list"))?.adapter?.itemCount == expectedCards
                    }
                }
                scenario.onActivity { master.scrollToPosition(1) }
                await("First row unavailable") { master.findViewHolderForAdapterPosition(1) != null }
                lateinit var row: RecyclerView
                scenario.onActivity { row=master.findViewHolderForAdapterPosition(1)!!.itemView.findViewById(id("nexo_tv_row_list")); row.scrollToPosition(11) }
                await("Row end unavailable") { row.findViewHolderForAdapterPosition(11) != null }
                val key15 = "NexoStream\u0000${provider.mainUrl}/first/15"
                scenario.onActivity { row.findViewHolderForAdapterPosition(11)!!.itemView.requestFocus() }
                press(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("View all was not reached from the last title") { it.currentFocus?.tag == "section-more:TV first" }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                fun section(a: MainActivity) =
                    nav(a).childFragmentManager.primaryNavigationFragment?.childFragmentManager
                        ?.findFragmentByTag("nexo-tv-section")
                await("Section dialog missing") { a -> section(a)?.dialogViewWidth() == a.resources.displayMetrics.widthPixels }
                await("Failed pagination did not expose retry") { a ->
                    section(a)?.view?.findViewById<View>(id("nexo_tv_section_retry"))?.visibility == View.VISIBLE
                }
                capture("nexo-tv-section")
                scenario.onActivity { a ->
                    checkNotNull(section(a)?.view?.findViewById<View>(id("nexo_tv_section_retry"))).requestFocus()
                }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Retry did not recover page two") { a ->
                    ((vm(a).page.value as? Resource.Success)?.value?.get("TV first")?.currentPage ?: 0) >= 2 &&
                        (section(a)?.view?.findViewById<RecyclerView>(id("nexo_tv_section_list"))?.adapter?.itemCount ?: 0) >= 24
                }
                press(KeyEvent.KEYCODE_BACK)
                await("Section close lost View all focus") { it.currentFocus?.tag == "section-more:TV first" }
                press(KeyEvent.KEYCODE_DPAD_LEFT)
                await("Last title focus was lost") { it.currentFocus?.tag == key15 }
                val now=System.currentTimeMillis()
                scenario.onActivity {
                    DataStoreHelper.setFavoritesData(favoriteId, DataStoreHelper.FavoritesData(now, favoriteId, now, "TV favorite", "${provider.mainUrl}/favorite", "NexoStream", TvType.Movie, null, null))
                    MainActivity.bookmarksUpdatedEvent.invoke(true)
                }
                await("Favorites change not reflected") { a -> a.findViewById<RecyclerView>(id("nexo_tv_home_list"))?.adapter?.itemCount == 5 }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.getExternalFilesDir(null), "nexo-tv-home.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
            }
        } finally {
            DataStoreHelper.selectedKeyIndex = testProfile
            DataStoreHelper.removeFavoritesData(favoriteId)
            CloudStreamApp.removeKeys(testProfile.toString())
            CloudStreamApp.removeKeys(emptyProfile.toString())
            DataStoreHelper.accounts = oldAccounts
            DataStoreHelper.selectedKeyIndex = oldSelected
            val restorePlugins=store.edit()
            if(installed==null) restorePlugins.remove("PLUGINS_KEY") else restorePlugins.putString("PLUGINS_KEY",installed)
            if(locals==null) restorePlugins.remove("PLUGINS_KEY_LOCAL") else restorePlugins.putString("PLUGINS_KEY_LOCAL",locals)
            restorePlugins.commit()
            val restoreUpdate=prefs.edit(); if(previousUpdate is Boolean) restoreUpdate.putBoolean(updateKey,previousUpdate) else restoreUpdate.remove(updateKey);restoreUpdate.commit()
            context.getSharedPrefs().edit().commit()
            APIHolder.allProviders.withLock { APIHolder.allProviders.clear(); APIHolder.allProviders.addAll(oldProviders) }
            APIHolder.apis=oldApis; APIHolder.apiMap=null
            val edit=prefs.edit(); if(previousLayout is Int) edit.putInt(layoutKey,previousLayout) else edit.remove(layoutKey); edit.commit(); context.updateTv()
        }
    }
    private fun androidx.fragment.app.Fragment.dialogViewWidth(): Int? = (this as? androidx.fragment.app.DialogFragment)?.dialog?.window?.decorView?.width
}
