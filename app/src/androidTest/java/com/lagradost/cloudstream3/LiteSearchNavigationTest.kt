package com.lagradost.cloudstream3

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.LiteUiAutomation.clickLabel
import com.lagradost.cloudstream3.LiteUiAutomation.waitFor
import com.lagradost.cloudstream3.utils.AtomicList
import com.lagradost.cloudstream3.ui.search.SEARCH_HISTORY_KEY
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LiteSearchNavigationTest {
    private class Provider : MainAPI() {
        override var name="NexoStream"
        override var mainUrl="https://nexo-nav-fixture.invalid"
        override val hasDownloadSupport=false
        override val hasChromecastSupport=false
        val requests=AtomicInteger()
        override suspend fun search(query:String,page:Int):SearchResponseList {
            requests.incrementAndGet()
            return newSearchResponseList((1..100).map { newMovieSearchResponse("Fixture result $it","$mainUrl/$it",TvType.Movie) },false)
        }
        override suspend fun load(url:String)=newMovieLoadResponse("Fixture result ${url.substringAfterLast('/')}",url,TvType.Movie,"fixture") { plot="Navigation fixture." }
    }
    private fun tree(): List<AccessibilityNodeInfo> {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
        return LiteUiAutomation.nodes(automation.rootInActiveWindow).filter { it.isVisibleToUser }
    }
    private fun ime(activity:MainActivity)=ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true
    private fun firstResult()=tree().firstOrNull { node -> (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().contains("Fixture result ") }
    private fun click(node:AccessibilityNodeInfo) {
        var target:AccessibilityNodeInfo?=node
        while(target!=null && !target.isClickable)target=target.parent
        checkNotNull(target)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun headerSearchRetainsQueryScrollAndOneShotKeyboard() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        val key=context.getString(R.string.app_layout_key)
        val previous=prefs.all[key]
        val oldProviders=APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        val oldApis=APIHolder.apis
        val provider=Provider()
        val query="nexo-navigation-fixture"
        val deepQuery="nexo-deep-fixture"
        prefs.edit().putInt(key,0).commit();context.updateTv()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                APIHolder.allProviders.withLock { APIHolder.allProviders.clear();APIHolder.allProviders.add(provider) }
                APIHolder.apis=AtomicList<MainAPI>(listOf(provider));APIHolder.apiMap=null
                // Before the feature this action is absent; bottom-navigation Search cannot satisfy it.
                waitFor { tree().any { it.contentDescription?.toString()=="Search titles" } }
                clickLabel("Search titles")
                scenario.onActivity {
                    val nav=(it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
                    assertEquals(R.id.navigation_search,nav.currentDestination?.id)
                    assertEquals(3,it.findViewById<BottomNavigationView>(R.id.nav_view).menu.size())
                }
                var keyboard=false
                waitFor { scenario.onActivity { keyboard=ime(it) };keyboard }
                APIHolder.allProviders.withLock { APIHolder.allProviders.clear();APIHolder.allProviders.add(provider) }
                APIHolder.apis=AtomicList<MainAPI>(listOf(provider));APIHolder.apiMap=null
                val input=tree().first { it.isEditable }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,query) }))
                waitFor { tree().any { it.isEditable && it.text?.toString()==query } }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                Thread.sleep(300)
                try { waitFor { firstResult()!=null } } catch (error: AssertionError) {
                    throw AssertionError("requests=${provider.requests.get()}, apis=${APIHolder.apis.map { it.javaClass.name }}, tree="+tree().map { it.text ?: it.contentDescription },error)
                }
                repeat(2) {
                    assertTrue(tree().first { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    Thread.sleep(500)
                    instrumentation.waitForIdleSync()
                }
                val before=firstResult() ?: error("No scrolled result")
                val title=Regex("Fixture result \\d+").find(before.text?.toString() ?: before.contentDescription.toString())!!.value
                val calls=provider.requests.get()
                click(before)
                var detail=false
                waitFor { scenario.onActivity {
                    detail=(it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController.currentDestination?.id==R.id.navigation_results_phone
                };detail }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                waitFor { tree().any { it.isEditable && it.text?.toString()==query } }
                assertEquals(title,Regex("Fixture result \\d+").find(firstResult()?.text?.toString() ?: firstResult()?.contentDescription.toString())?.value)
                Thread.sleep(700)
                assertEquals("Returning repeated search and reset its results",calls,provider.requests.get())
                scenario.onActivity { assertFalse("Back reopened keyboard",ime(it)) }
                scenario.recreate()
                waitFor { tree().any { it.isEditable && it.text?.toString()==query } }
                scenario.onActivity { assertFalse("Recreation reopened keyboard",ime(it)) }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                waitFor { tree().any { it.contentDescription?.toString()=="Search titles" } }
                clickLabel("Search titles")
                waitFor { scenario.onActivity { keyboard=ime(it) };keyboard }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                // Exercise the real external search handler, independent of removed menu items.
                scenario.onActivity { assertTrue(MainActivity.handleAppIntentUrl(it,"cloudstreamsearch://$deepQuery",false)) }
                waitFor { tree().any { it.isEditable && it.text?.toString()==deepQuery } }
            }
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.clear();APIHolder.allProviders.addAll(oldProviders) }
            APIHolder.apis=oldApis;APIHolder.apiMap=null
            listOf(query,deepQuery).forEach { removeKey("${DataStoreHelper.currentAccount}/$SEARCH_HISTORY_KEY",it.hashCode().toString()) }
            val edit=prefs.edit();if(previous is Int)edit.putInt(key,previous)else edit.remove(key)
            edit.commit();context.updateTv()
        }
    }
    @Test fun headersExposeSearchOnEachMobileDestination() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        val key=context.getString(R.string.app_layout_key);val previous=prefs.all[key]
        prefs.edit().putInt(key,0).commit();context.updateTv()
        val prefix=InstrumentationRegistry.getArguments().getString("prefix") ?: "search-headers"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                listOf(R.id.navigation_home to "home",R.id.navigation_library to "favorites",R.id.navigation_settings to "settings").forEach { (destination,name) ->
                    scenario.onActivity {
                        val nav=(it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
                        if(nav.currentDestination?.id!=destination)nav.navigate(destination)
                    }
                    waitFor { tree().any { it.contentDescription?.toString()=="Search titles" } }
                    val action=tree().first { it.contentDescription?.toString()=="Search titles" }
                    var target:AccessibilityNodeInfo?=action
                    while(target!=null && !target.isClickable)target=target.parent
                    val bounds=android.graphics.Rect().also { checkNotNull(target).getBoundsInScreen(it) }
                    assertTrue("Search target too small: $bounds",bounds.width()/context.resources.displayMetrics.density>=47.9f && bounds.height()/context.resources.displayMetrics.density>=47.9f)
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        java.io.File(context.cacheDir,"$prefix-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        bitmap.recycle()
                    }
                    clickLabel("Search titles")
                    scenario.onActivity {
                        val nav=(it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
                        assertEquals(R.id.navigation_search,nav.currentDestination?.id)
                        nav.popBackStack()
                    }
                }
            }
        } finally { val edit=prefs.edit();if(previous is Int)edit.putInt(key,previous)else edit.remove(key);edit.commit();context.updateTv() }
    }
    @Test fun tvStillHasSearchNavigation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        val key=context.getString(R.string.app_layout_key);val previous=prefs.all[key]
        prefs.edit().putInt(key,1).commit();context.updateTv()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity {
                val rail=it.findViewById<NavigationRailView>(R.id.nav_rail_view)
                assertNotNull(rail.menu.findItem(R.id.navigation_search))
                rail.selectedItemId=R.id.navigation_search
                val nav=(it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
                assertEquals(R.id.navigation_search,nav.currentDestination?.id)
            } }
        } finally { val edit=prefs.edit();if(previous is Int)edit.putInt(key,previous)else edit.remove(key);edit.commit();context.updateTv() }
    }
}
