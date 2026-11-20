package com.lagradost.cloudstream3.tv

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NexoTvSearchTest {
    private class Provider : MainAPI() {
        override var name="NexoStream"
        override var mainUrl="https://nexo-tv-search.invalid"
        override val hasMainPage=true
        override val mainPage=mainPageOf("home" to "Fixture")
        override val hasDownloadSupport=false
        override val hasChromecastSupport=false
        val requests=CopyOnWriteArrayList<String>()
        val failures=AtomicInteger(1)
        override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,listOf(newMovieSearchResponse("Home","$mainUrl/home",TvType.Movie)),false)
        override suspend fun search(query:String,page:Int): SearchResponseList {
            requests.add("$query/$page")
            if(query=="slow") withContext(NonCancellable) { delay(1800) } else delay(60)
            if(query=="new" && page==2 && failures.getAndDecrement()>0) error("Expected pagination fixture error")
            return newSearchResponseList((0 until 28).map { newMovieSearchResponse("$query ${(page-1)*28+it}","$mainUrl/$query/${(page-1)*28+it}",TvType.Movie) },page<3)
        }
        override suspend fun load(url:String)=newMovieLoadResponse(url.substringAfterLast('/'),url,TvType.Movie,"data")
    }
    private fun ownSearchExists() {
        try { Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvSearchFragment") }
        catch(e:ClassNotFoundException) { throw AssertionError("Own TV Search missing",e) }
    }
    @Test fun queryGenerationPaginationAndDetailsKeepIdentity() {
        ownSearchExists()
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun nav(a:MainActivity)=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { nav(it).navigate(R.id.navigation_search) }
            env.await(scenario,"Search input missing") { it.findViewById<EditText>(env.id("nexo_tv_search_input"))!=null }
            fun query(text:String) { scenario.onActivity { it.findViewById<EditText>(env.id("nexo_tv_search_input")).setText(text) } }
            query("a"); SystemClock.sleep(650); assertTrue(provider.requests.isEmpty())
            query("slow")
            env.await(scenario,"Slow query never started") { provider.requests.contains("slow/1") }
            query("new")
            env.await(scenario,"New results missing") { a -> a.findViewById<RecyclerView>(env.id("nexo_tv_search_grid"))?.findViewHolderForAdapterPosition(0)?.itemView?.contentDescription=="new 0" }
            SystemClock.sleep(2000)
            scenario.onActivity { assertEquals("Stale response replaced new query","new 0",it.findViewById<RecyclerView>(env.id("nexo_tv_search_grid")).findViewHolderForAdapterPosition(0)?.itemView?.contentDescription) }
            scenario.onActivity { a -> val grid=a.findViewById<RecyclerView>(env.id("nexo_tv_search_grid"));grid.scrollToPosition(27);grid.post { grid.findViewHolderForAdapterPosition(27)?.itemView?.requestFocus() } }
            env.await(scenario,"Pagination failure not visible") { it.findViewById<View>(env.id("nexo_tv_search_retry"))?.visibility==View.VISIBLE }
            scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_search_retry")).performClick() }
            env.await(scenario,"Second page missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_search_grid"))?.adapter?.itemCount==56 }
            scenario.onActivity { a -> val grid=a.findViewById<RecyclerView>(env.id("nexo_tv_search_grid"));grid.scrollToPosition(31);grid.post { grid.findViewHolderForAdapterPosition(31)?.itemView?.requestFocus() } }
            env.await(scenario,"Result focus missing") { it.currentFocus?.contentDescription=="new 31" }
            env.key(KeyEvent.KEYCODE_DPAD_CENTER)
            env.await(scenario,"Details route missing") { nav(it).currentDestination?.id==R.id.navigation_results_tv }
            env.key(KeyEvent.KEYCODE_BACK)
            env.await(scenario,"Search result focus not restored") { it.currentFocus?.contentDescription=="new 31" }
            scenario.onActivity { assertFalse(ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true) }
            val calls=provider.requests.size
            scenario.recreate()
            env.await(scenario,"Search recreation lost query/selection") { it.currentFocus?.contentDescription=="new 31" && it.findViewById<EditText>(env.id("nexo_tv_search_input"))?.text?.toString()=="new" }
            assertEquals(calls,provider.requests.size)
            query("")
            env.await(scenario,"History missing") { it.findViewById<android.widget.LinearLayout>(env.id("nexo_tv_search_history"))?.childCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_search_back")).performClick() }
            env.await(scenario,"Search exit failed") { nav(it).currentDestination?.id==R.id.navigation_home }
        } }
    }
    private fun imeVisible(env:NexoTvTestEnvironment,a:MainActivity):Boolean {
        if(ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true) return true
        // TV Gboard is a floating IME and does not necessarily contribute a bottom inset.
        return env.instrumentation.uiAutomation.windows.any { it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser==true }
    }
    @Test fun backClosesKeyboardThenLeavesSearch() {
        ownSearchExists()
        NexoTvTestEnvironment(Provider()).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun nav(a:MainActivity)=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            env.instrumentation.uiAutomation.serviceInfo=env.instrumentation.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            scenario.onActivity { nav(it).navigate(R.id.navigation_search,Bundle().apply { putBoolean("lite_focus_search",true) }) }
            env.await(scenario,"Keyboard did not open") { imeVisible(env,it) }
            env.key(KeyEvent.KEYCODE_BACK)
            env.await(scenario,"First Back did not dismiss keyboard") { !imeVisible(env,it) }
            scenario.onActivity { assertEquals(R.id.navigation_search,nav(it).currentDestination?.id) }
            env.key(KeyEvent.KEYCODE_BACK)
            env.await(scenario,"Second Back did not leave Search") { nav(it).currentDestination?.id==R.id.navigation_home }
        } }
    }
}
