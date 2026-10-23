package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.Assert.*
import org.junit.Test

class NexoTvNavigationTest {
    private class Provider:MainAPI() {
        override var name="NexoStream"
        override var mainUrl="https://nexo-tv-nav-${System.nanoTime()}.invalid"
        override val hasMainPage=true
        override val mainPage=mainPageOf("home" to "Navigation row")
        override val hasDownloadSupport=false
        override val hasChromecastSupport=false
        override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,(0..35).map { newMovieSearchResponse("Home $it","$mainUrl/$it",TvType.Movie) },false)
        override suspend fun search(query:String,page:Int)=newSearchResponseList((0..27).map { newMovieSearchResponse("Search $it","$mainUrl/$it",TvType.Movie) },false)
        override suspend fun load(url:String)=newMovieLoadResponse("Navigation details",url,TvType.Movie,"data") { plot="A local navigation fixture" }
    }
    private fun nav(a:MainActivity)=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
    private fun homeReady(env:NexoTvTestEnvironment,scenario:ActivityScenario<MainActivity>)=env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
    @Test fun searchGridEdgesReachMenuAndInputWithoutActivatingDestinations() {
        NexoTvTestEnvironment(Provider()).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            homeReady(env,scenario)
            scenario.onActivity { it.findViewById<NavigationRailView>(R.id.nav_rail_view).selectedItemId=R.id.navigation_search }
            env.await(scenario,"Search missing") { it.findViewById<EditText>(env.id("nexo_tv_search_input"))!=null }
            scenario.onActivity { it.findViewById<EditText>(env.id("nexo_tv_search_input")).setText("test") }
            env.await(scenario,"Search grid missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_search_grid"))?.findViewHolderForAdapterPosition(0)!=null }
            scenario.onActivity { it.findViewById<RecyclerView>(env.id("nexo_tv_search_grid")).findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
            env.key(KeyEvent.KEYCODE_DPAD_LEFT)
            env.await(scenario,"Search grid traps Left at the menu boundary") { it.findViewById<NavigationRailView>(R.id.nav_rail_view).hasFocus() }
            scenario.onActivity { assertEquals(R.id.navigation_search,nav(it).currentDestination?.id);it.findViewById<View>(R.id.navigation_settings).requestFocus();assertEquals(R.id.navigation_search,nav(it).currentDestination?.id) }
            env.key(KeyEvent.KEYCODE_DPAD_RIGHT)
            env.await(scenario,"Menu Right lost search selection") { it.currentFocus?.contentDescription=="Search 0" }
            env.key(KeyEvent.KEYCODE_DPAD_UP)
            env.await(scenario,"Grid Up did not reach query") { it.currentFocus?.id==env.id("nexo_tv_search_input") }
        } }
    }
    @Test fun favoritesStartOnFirstItemAndMenuReturnsToSameIdentity() {
        NexoTvTestEnvironment(Provider()).use { env ->
            DataStoreHelper.setFavoritesData(-7868801,DataStoreHelper.FavoritesData(1,-7868801,1,"Only favorite","https://nexo-tv-nav.invalid/fav","NexoStream",TvType.Movie,null,null))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                homeReady(env,scenario)
                scenario.onActivity { it.findViewById<NavigationRailView>(R.id.nav_rail_view).selectedItemId=R.id.navigation_library }
                env.await(scenario,"Favorites initial loading stranded focus on toolbar") { it.currentFocus?.contentDescription=="Only favorite" }
                env.key(KeyEvent.KEYCODE_DPAD_LEFT)
                env.await(scenario,"Favorites grid traps Left at the menu boundary") { it.findViewById<NavigationRailView>(R.id.nav_rail_view).hasFocus() }
                env.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                env.await(scenario,"Menu Right lost favorite identity") { it.currentFocus?.contentDescription=="Only favorite" }
                env.key(KeyEvent.KEYCODE_DPAD_UP)
                env.await(scenario,"Favorites grid Up cannot reach toolbar") { it.currentFocus?.id==env.id("nexo_tv_favorites_options") || it.currentFocus?.id==env.id("nexo_tv_favorites_downloaded") || it.currentFocus?.id==env.id("nexo_tv_favorites_sort") }
            }
        }
    }
    @Test fun expandedSectionDetailsBackAndSettingsKeepValidFocus() {
        NexoTvTestEnvironment(Provider()).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            homeReady(env,scenario)
            scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_home_list")).findViewWithTag<View>("section-title:Navigation row")!!.performClick() }
            fun section(a:MainActivity):DialogFragment? {
                val host=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment ?: return null
                return host.childFragmentManager.findFragmentByTag("nexo-tv-section") as? DialogFragment
            }
            env.await(scenario,"Section missing") { section(it)?.view?.findViewById<RecyclerView>(env.id("nexo_tv_section_list"))?.findViewHolderForAdapterPosition(7)!=null }
            scenario.onActivity { a -> section(a)!!.view!!.findViewById<RecyclerView>(env.id("nexo_tv_section_list")).findViewHolderForAdapterPosition(7)!!.itemView.requestFocus() }
            env.key(KeyEvent.KEYCODE_ENTER)
            env.await(scenario,"Section did not open Details") { it.findViewById<TextView>(env.id("nexo_tv_result_title"))?.text=="Navigation details" }
            env.key(KeyEvent.KEYCODE_BACK)
            env.await(scenario,"Details return lost section item") { section(it)?.view?.findFocus()?.contentDescription=="Home 7" }
            scenario.recreate()
            env.await(scenario,"Section recreation lost item") { section(it)?.view?.findFocus()?.contentDescription=="Home 7" }
            env.key(KeyEvent.KEYCODE_BACK)
            env.await(scenario,"Section close lost source title") { it.currentFocus?.tag=="section-title:Navigation row" }
            scenario.onActivity { it.findViewById<View>(R.id.navigation_settings).requestFocus() }
            scenario.onActivity { assertEquals(R.id.navigation_home,nav(it).currentDestination?.id) }
            env.key(KeyEvent.KEYCODE_DPAD_CENTER)
            env.await(scenario,"Settings menu did not activate") { nav(it).currentDestination?.id==R.id.navigation_settings }
            env.key(KeyEvent.KEYCODE_DPAD_RIGHT)
            env.await(scenario,"Settings content cannot receive remote focus") { !it.findViewById<View>(R.id.nav_rail_view).hasFocus() && it.findViewById<View>(R.id.nav_host_fragment).hasFocus() }
            val automation=env.instrumentation.uiAutomation
            automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            if(automation.windows.any { it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser==true }) env.key(KeyEvent.KEYCODE_BACK)
            var general:android.view.accessibility.AccessibilityNodeInfo?=null
            val deadline=SystemClock.elapsedRealtime()+15_000
            while(general==null && SystemClock.elapsedRealtime()<deadline) {
                general=(automation.windows.mapNotNull { it.root }+listOfNotNull(automation.rootInActiveWindow)).flatMap { LiteUiAutomation.nodes(it) }.firstOrNull { (it.text ?: it.contentDescription)?.toString()?.lineSequence()?.any { line -> line==env.context.getString(R.string.category_general) }==true }
                if(general==null) SystemClock.sleep(60)
            }
            if(general==null) automation.takeScreenshot().let { bitmap -> java.io.File(env.context.getExternalFilesDir(null),"nexo-tv-settings-missing.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            var target=checkNotNull(general) { "General setting missing; visible="+LiteUiAutomation.nodes(automation.rootInActiveWindow).mapNotNull { it.text ?: it.contentDescription } }
            while(!target.isFocusable && target.parent!=null) target=target.parent
            assertTrue("General setting cannot be focused",target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
            env.key(KeyEvent.KEYCODE_DPAD_CENTER)
            env.await(scenario,"General setting did not activate with remote") { nav(it).currentDestination?.id==R.id.navigation_settings_general }
            env.key(KeyEvent.KEYCODE_BACK)
            env.await(scenario,"Settings Back failed") { nav(it).currentDestination?.id==R.id.navigation_settings }
            scenario.onActivity { it.findViewById<NavigationRailView>(R.id.nav_rail_view).selectedItemId=R.id.navigation_home }
            homeReady(env,scenario)
            scenario.onActivity { it.findViewById<View>(R.id.navigation_home).requestFocus() }
            env.key(KeyEvent.KEYCODE_DPAD_RIGHT)
            env.await(scenario,"Old Settings/Details rail link points at destroyed view") { it.findViewById<View>(env.id("nexo_tv_home_list")).hasFocus() }
        } }
    }
}
