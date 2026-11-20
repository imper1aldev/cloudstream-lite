package com.lagradost.cloudstream3.tv

import android.view.View
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.Assert.*
import org.junit.Test

class NexoTvHomeLateCallbackTest {
    @Test fun queuedHomeRestoreCannotAccessDestroyedViewAfterDetachment() {
        val api=object: MainAPI() {
            override var name="NexoStream"
            override var mainUrl="https://nexo-tv-late-${System.nanoTime()}.invalid"
            override val hasMainPage=true
            override val mainPage=mainPageOf("home" to "Late callback")
            override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,(0..7).map { newMovieSearchResponse("Item $it","$mainUrl/$it",TvType.Movie) },false)
            override suspend fun load(url:String)=newMovieLoadResponse("Late callback details",url,TvType.Movie,"data")
        }
        NexoTvTestEnvironment(api).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            var queued=false
            lateinit var home:com.lagradost.cloudstream3.ui.tv.NexoTvHomeFragment
            scenario.onActivity { a ->
                val host=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment!!
                home=host.childFragmentManager.fragments.filterIsInstance<com.lagradost.cloudstream3.ui.tv.NexoTvHomeFragment>().single()
                val list=a.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))
                list.adapter!!.registerAdapterDataObserver(object:RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart:Int,itemCount:Int) {
                        if(queued)return
                        queued=true
                        list.post {
                            home.parentFragmentManager.beginTransaction().detach(home).commitNow()
                        }
                    }
                })
                DataStoreHelper.setFavoritesData(-7214101,DataStoreHelper.FavoritesData(1,-7214101,1,"Added favorite","${api.mainUrl}/fav",api.name,TvType.Movie,null,null))
                MainActivity.bookmarksUpdatedEvent.invoke(true)
            }
            env.await(scenario,"View detachment was not queued from list commit") { queued && home.isDetached && home.view==null }
            env.instrumentation.waitForIdleSync()
            scenario.onActivity { assertFalse(it.isFinishing) }
        } }
    }
}
