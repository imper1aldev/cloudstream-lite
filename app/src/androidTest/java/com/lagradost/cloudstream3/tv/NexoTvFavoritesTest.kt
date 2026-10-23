package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.FavoritesData
import com.lagradost.cloudstream3.ui.lite.LiteFavorites
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NexoTvFavoritesTest {
    private class Provider:MainAPI() {
        override var name="NexoStream"
        override var mainUrl="https://nexo-tv-favorites.invalid"
        override val hasMainPage=true
        override val mainPage=mainPageOf("home" to "Fixture")
        override val hasDownloadSupport=false
        override val hasChromecastSupport=false
        override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,listOf(newMovieSearchResponse("Home","$mainUrl/home",TvType.Movie)),false)
        override suspend fun load(url:String)=newMovieLoadResponse("Favorite fixture",url,TvType.Movie,"data")
    }
    @Test fun detailsLongPressUndoDownloadedFilterAndDelayedCleanup() {
        try { Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvFavoritesFragment") }
        catch(e:ClassNotFoundException) { throw AssertionError("Own TV Favorites missing",e) }
        NexoTvTestEnvironment(Provider()).use { env ->
            val beforeSort=DataStoreHelper.librarySortingMode
            val parent=generateSequence(-7869000) { it-2 }.first { CloudStreamApp.getKeys("$DOWNLOAD_EPISODE_CACHE/$it").isNullOrEmpty() }
            val episode=parent-1
            val dir=File(env.context.filesDir,"nexo-tv-favorites-${System.nanoTime()}").apply { mkdir() }
            val file=File(dir,"fixture.bin").apply { writeBytes(ByteArray(4096)) }
            val favorite=FavoritesData(10,parent,10,"Zulu","https://nexo-tv-favorites.invalid/zulu","NexoStream",TvType.Movie,null,2020)
            val alpha=favorite.copy(id=parent-2,name="Alpha",url="https://nexo-tv-favorites.invalid/alpha",latestUpdatedTime=20)
            CloudStreamApp.setKey("$DOWNLOAD_EPISODE_CACHE/$parent",episode.toString(),DownloadEpisodeCached("Local episode",null,1,1,parent,null,null,10,episode))
            CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO,episode.toString(),DownloadedFileInfo(4096,"",file.name,basePath=dir.absolutePath))
            DataStoreHelper.setFavoritesData(parent,favorite);DataStoreHelper.setFavoritesData(alpha.id,alpha)
            DataStoreHelper.librarySortingMode=com.lagradost.cloudstream3.ui.library.ListSorting.AlphabeticalA.ordinal
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun nav(a:MainActivity)=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
                env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
                scenario.onActivity { nav(it).navigate(R.id.navigation_library) }
                env.await(scenario,"Favorites grid missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_favorites_grid"))?.adapter?.itemCount==2 }
                scenario.onActivity { a -> a.findViewById<RecyclerView>(env.id("nexo_tv_favorites_grid")).findViewHolderForAdapterPosition(1)!!.itemView.apply { requestFocus();performClick() } }
                env.await(scenario,"Default favorite click did not open Details") { it.findViewById<TextView>(env.id("nexo_tv_result_title"))?.text=="Favorite fixture" }
                env.key(android.view.KeyEvent.KEYCODE_BACK)
                env.await(scenario,"Favorite identity not restored") { it.currentFocus?.contentDescription=="Zulu" }
                scenario.onActivity { a -> assertTrue(a.currentFocus!!.performLongClick()); assertEquals(R.id.navigation_library,nav(a).currentDestination?.id) }
                LiteUiAutomation.clickLabel(env.context.getString(R.string.action_remove_from_favorites))
                env.await(scenario,"Favorite not removed") { DataStoreHelper.getFavoritesData(parent)==null && it.findViewById<RecyclerView>(env.id("nexo_tv_favorites_grid"))?.adapter?.itemCount==1 }
                assertTrue("Files removed before grace deadline",file.exists())
                val undo=com.google.android.material.R.id.snackbar_action
                env.await(scenario,"Undo cannot receive remote focus") { it.findViewById<View>(undo)?.isFocusable==true }
                scenario.onActivity { it.findViewById<View>(undo).requestFocus() }
                env.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                env.await(scenario,"Undo did not restore favorite") { DataStoreHelper.getFavoritesData(parent)!=null }
                SystemClock.sleep(LiteFavorites.GRACE_MS+500)
                assertTrue("Undo did not protect local file",file.exists())
                scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_favorites_downloaded")).performClick() }
                env.await(scenario,"Downloaded filter incorrect") { it.findViewById<RecyclerView>(env.id("nexo_tv_favorites_grid"))?.adapter?.itemCount==1 }
                scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_favorites_options")).performClick() }
                LiteUiAutomation.clickLabel(env.context.getString(R.string.action_remove_from_favorites))
                env.await(scenario,"Empty downloaded favorites not rendered") { it.findViewById<RecyclerView>(env.id("nexo_tv_favorites_grid"))?.adapter?.itemCount==0 }
                scenario.onActivity { nav(it).navigate(R.id.navigation_settings) }
                val deadline=SystemClock.elapsedRealtime()+LiteFavorites.GRACE_MS+5000
                while(file.exists() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
                assertFalse("Navigating away canceled delayed local cleanup",file.exists())
            } } finally {
                LiteFavorites.added(parent);LiteFavorites.added(alpha.id!!)
                CloudStreamApp.removeKeys("$DOWNLOAD_EPISODE_CACHE/$parent")
                CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO,episode.toString())
                file.delete();dir.delete();DataStoreHelper.librarySortingMode=beforeSort
            }
        }
    }
}
