package com.lagradost.cloudstream3.tv

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.tv.NexoTvCardAdapter
import com.lagradost.cloudstream3.utils.DataStoreHelper.ResumeWatchingResult
import org.junit.Assert.*
import org.junit.Test

class NexoTvCardPayloadTest {
    @Test fun unchangedArtworkStillUsesLatestResumeActionPayload() {
        val provider = object : MainAPI() {
            override var name = "NexoStream"
            override var mainUrl = "https://nexo-tv-payload-${System.nanoTime()}.invalid"
            override val hasMainPage = true
            override val mainPage = mainPageOf("home" to "Payload")
            override suspend fun getMainPage(page: Int, request: MainPageRequest) = newHomePageResponse(request.name, listOf(newMovieSearchResponse("Fixture", "$mainUrl/movie", TvType.Movie)), false)
            override suspend fun load(url: String) = newMovieLoadResponse("Fixture",url,TvType.Movie,"data")
        }
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            val original = ResumeWatchingResult("Same artwork", "${provider.mainUrl}/resume", "NexoStream", TvType.TvSeries, null, null, 1, 100, 1, 1, false)
            val updated = original.copy(id=2, episode=2, isFromDownload=true)
            var clicked: SearchResponse? = null
            var held: SearchResponse? = null
            var focused: SearchResponse? = null
            val adapter = NexoTvCardAdapter(false, { clicked=it }, { held=it; true }, { item, _ -> focused=item })
            lateinit var list: RecyclerView
            scenario.onActivity { a ->
                val host=a.findViewById<ViewGroup>(R.id.home_lite_legacy_host)
                for(i in 0 until host.childCount) host.getChildAt(i).visibility=View.GONE
                list=RecyclerView(a).apply { layoutParams=ViewGroup.LayoutParams(-1,600); layoutManager=LinearLayoutManager(a,RecyclerView.HORIZONTAL,false); this.adapter=adapter }
                host.addView(list)
                adapter.submitList(listOf(original))
            }
            env.await(scenario,"Card not bound") { list.findViewHolderForAdapterPosition(0)!=null }
            var committed=false
            scenario.onActivity { adapter.submitList(listOf(updated)) { committed=true } }
            env.await(scenario,"New resume snapshot not committed") { committed }
            scenario.onActivity { a ->
                a.findViewById<View>(R.id.navigation_home).requestFocus()
                val card=list.findViewHolderForAdapterPosition(0)!!.itemView
                card.requestFocus(); card.performClick(); card.performLongClick()
                assertSame("Click retained previous episode/download payload", updated, clicked)
                assertSame("Long click retained previous payload", updated, held)
                assertSame("Focus retained previous payload", updated, focused)
            }
        } }
    }
}
