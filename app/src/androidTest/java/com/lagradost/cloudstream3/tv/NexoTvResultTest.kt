package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NexoTvResultTest {
    private class Provider : MainAPI() {
        override var name = "NexoStream"
        override var mainUrl = "https://nexo-tv-result-${System.nanoTime()}.invalid"
        override val hasMainPage = true
        override val hasDownloadSupport = false
        override val hasChromecastSupport = false
        override val mainPage = mainPageOf("movies" to "Fixture")
        val extracts = CopyOnWriteArrayList<String>()
        val loads = CopyOnWriteArrayList<String>()
        override suspend fun getMainPage(page: Int, request: MainPageRequest) = newHomePageResponse(request.name,
            listOf(newMovieSearchResponse("Movie", "$mainUrl/movie", TvType.Movie)),false)
        override suspend fun load(url: String): LoadResponse {
            loads.add(url)
            return when(url.substringAfterLast('/')) {
                "series" -> newTvSeriesLoadResponse("Series fixture",url,TvType.TvSeries,
                    listOf(newEpisode("season-1-episode-1") { season=1; episode=1; name="First" },
                        newEpisode("season-2-episode-7") { season=2; episode=7; name="Seventh" }))
                "range" -> newTvSeriesLoadResponse("Range fixture",url,TvType.TvSeries,(1..65).map { number -> newEpisode("range-$number") { season=1; episode=number; name="Range $number" } }) { plot=("A long synopsis for the TV layout. ").repeat(80) }
                "anime" -> newAnimeLoadResponse("Anime fixture",url,TvType.Anime) {
                    addEpisodes(DubStatus.Subbed,listOf(newEpisode("sub-1") { episode=1; name="Sub" }))
                    addEpisodes(DubStatus.Dubbed,listOf(newEpisode("dub-1") { episode=1; name="Dub" }))
                }
                "hero" -> newMovieLoadResponse("Vista de detalles",url,TvType.Movie,"movie-data") {
                    year=2020;duration=148;tags=listOf("Accion","Ciencia ficcion","Drama")
                    plot="Una historia de viajes, misterios y aventuras que pone a prueba a sus protagonistas. ".repeat(8)
                }
                "soon" -> newMovieLoadResponse("Soon fixture",url,TvType.Movie,"") { comingSoon=true }
                else -> newMovieLoadResponse("Movie fixture",url,TvType.Movie,"movie-data") { plot="A short plot" }
            }
        }
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            extracts.add(data)
            return false // No network or player: assert UI sends the original provider payload.
        }
    }
    private fun ownResultExists() {
        try { Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvResultFragment") }
        catch(e: ClassNotFoundException) { throw AssertionError("Own TV Details missing",e) }
    }
    @Test fun initialPlayFocusKeepsNormalHeroTitleVisible() {
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/hero",provider.name,"Vista de detalles") }
            env.await(scenario,"Initial Play focus missing") { it.currentFocus?.id==env.id("nexo_tv_result_play") }
            env.instrumentation.waitForIdleSync();SystemClock.sleep(600)
            scenario.onActivity { a ->
                val title=a.findViewById<View>(env.id("nexo_tv_result_title"));val rect=android.graphics.Rect()
                assertTrue("Title scrolled outside viewport",title.getGlobalVisibleRect(rect))
                assertEquals("Initial focus clipped the title",title.height,rect.height())
                val play=a.findViewById<View>(env.id("nexo_tv_result_play"))
                assertTrue(play.getGlobalVisibleRect(rect));assertEquals(play.height,rect.height())
            }
        } }
    }
    @Test fun favoriteIconAnnouncesAndPersistsToggle() {
        NexoTvTestEnvironment(Provider()).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val provider=APIHolder.getApiFromNameNull("NexoStream")!!
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/movie",provider.name,"Movie fixture") }
            env.await(scenario,"Details not ready") { it.findViewById<View>(env.id("nexo_tv_result_play"))?.isShown==true }
            fun favorite(a:MainActivity)=a.findViewById<View>(env.id("nexo_tv_result_favorite"))
            scenario.onActivity { a ->
                assertEquals("Icon must announce the add action",a.getString(R.string.favorite),favorite(a).contentDescription)
                assertFalse(favorite(a).isSelected)
                favorite(a).requestFocus(); favorite(a).performClick()
            }
            env.await(scenario,"Favorite icon state not updated") { favorite(it).isSelected && favorite(it).contentDescription==it.getString(R.string.unfavorite) }
            scenario.recreate()
            env.await(scenario,"Favorite state/focus not restored") { favorite(it).isSelected && it.currentFocus?.id==favorite(it).id }
            scenario.onActivity { favorite(it).performClick() }
            env.await(scenario,"Unfavorite did not update accessible state") { !favorite(it).isSelected && favorite(it).contentDescription==it.getString(R.string.favorite) }
        } }
    }
    @Test fun synopsisVisibilityTracksRelayoutWithoutReloadingMetadata() {
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/movie",provider.name,"Movie fixture") }
            env.await(scenario,"Details not ready") { it.findViewById<View>(env.id("nexo_tv_result_play"))?.isShown==true }
            scenario.onActivity { a ->
                a.findViewById<TextView>(env.id("nexo_tv_result_plot")).apply {
                    text="A traveller crosses the stars to find a new home, while the family left behind faces an uncertain future."
                    layoutParams=layoutParams.apply { width=220 }
                }
            }
            env.await(scenario,"Narrow synopsis cannot be expanded") { it.findViewById<View>(env.id("nexo_tv_result_more"))?.isShown==true }
            scenario.onActivity { a ->
                a.findViewById<View>(env.id("nexo_tv_result_more")).requestFocus()
                a.findViewById<TextView>(env.id("nexo_tv_result_plot")).apply { layoutParams=layoutParams.apply { width=1200 } }
            }
            env.await(scenario,"Expansion action remained after text fits") { it.findViewById<View>(env.id("nexo_tv_result_more"))?.visibility==View.GONE }
            env.await(scenario,"Hiding expansion lost valid focus") { it.currentFocus?.id==env.id("nexo_tv_result_play") }
            assertEquals(1,provider.loads.count { it.endsWith("/movie") })
        } }
    }
    @Test fun movieSeriesAnimeAndHiddenActionsUseExistingModel() {
        ownResultExists()
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun fragment(a: MainActivity) = (a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment!!
                fun open(kind: String, title: String) {
                    scenario.onActivity { env.register(); it.loadResult("${provider.mainUrl}/$kind",provider.name,title) }
                    env.await(scenario,"Details title missing: $title") { it.findViewById<TextView>(env.id("nexo_tv_result_title"))?.text?.toString()==title }
                }
                env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
                open("movie","Movie fixture")
                env.await(scenario,"Movie play missing") { it.findViewById<View>(env.id("nexo_tv_result_play"))?.visibility==View.VISIBLE }
                scenario.onActivity { a ->
                    assertTrue(a.findViewById<View>(env.id("nexo_tv_result_more")).visibility != View.VISIBLE)
                    assertTrue(a.findViewById<View>(env.id("nexo_tv_result_cast")).visibility != View.VISIBLE)
                    assertTrue(a.findViewById<View>(env.id("nexo_tv_result_download")).visibility != View.VISIBLE)
                    a.findViewById<View>(env.id("nexo_tv_result_play")).performClick()
                }
                env.await(scenario,"Movie payload changed") { provider.extracts.contains("movie-data") }
                scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_result_favorite")).performClick() }
                env.await(scenario,"Favorite action failed") { ViewModelProvider(fragment(it))[ResultViewModel2::class.java].favoriteStatus.value==true }
                val favoriteFocus=env.id("nexo_tv_result_favorite")
                scenario.onActivity { it.findViewById<View>(favoriteFocus).requestFocus() }
                scenario.recreate()
                env.await(scenario,"Details action focus not restored") { it.currentFocus?.id==favoriteFocus }
                assertEquals(1,provider.loads.count { it.endsWith("/movie") })
                open("series","Series fixture")
                env.await(scenario,"Season selector missing") { it.findViewById<View>(env.id("nexo_tv_result_season"))?.visibility==View.VISIBLE }
                scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_result_season")).performClick() }
                var seasonLabel=""
                scenario.onActivity { a -> seasonLabel=ViewModelProvider(fragment(a))[ResultViewModel2::class.java].seasonSelections.value!!.last().first!!.asString(a) }
                LiteUiAutomation.clickLabel(seasonLabel)
                env.await(scenario,"Selected season episode missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes"))?.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(env.id("nexo_tv_episode_title"))?.text?.contains("Seventh")==true }
                scenario.onActivity { a -> a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes")).findViewHolderForAdapterPosition(0)?.itemView?.performClick() }
                env.await(scenario,"Season/episode payload was reconstructed incorrectly") { provider.extracts.contains("${provider.mainUrl}/season-2-episode-7") }
                open("anime","Anime fixture")
                env.await(scenario,"Dub selector missing") { it.findViewById<View>(env.id("nexo_tv_result_dub"))?.visibility==View.VISIBLE }
                var dubLabel=""
                scenario.onActivity { a -> dubLabel=ViewModelProvider(fragment(a))[ResultViewModel2::class.java].dubSubSelections.value!!.first { it.second==DubStatus.Subbed }.first!!.asString(a); a.findViewById<View>(env.id("nexo_tv_result_dub")).performClick() }
                LiteUiAutomation.clickLabel(dubLabel)
                env.await(scenario,"Anime episode missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes"))?.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(env.id("nexo_tv_episode_title"))?.text?.contains("Sub")==true }
                scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_result_play")).performClick() }
                env.await(scenario,"Anime language payload changed") { provider.extracts.contains("${provider.mainUrl}/sub-1") }
                env.instrumentation.uiAutomation.takeScreenshot().let { bitmap -> java.io.File(env.context.getExternalFilesDir(null),"nexo-tv-result-anime.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
                open("soon","Soon fixture")
                env.await(scenario,"Coming-soon play action visible") { it.findViewById<View>(env.id("nexo_tv_result_play"))?.visibility != View.VISIBLE }
            }
        }
    }
    @Test fun rangeResumeAndProgressUpdatesPreserveEpisodeFocus() {
        ownResultExists()
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun fragment(a:MainActivity)=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment!!
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/range",provider.name,"Range fixture") }
            env.await(scenario,"Range selector missing") { it.findViewById<View>(env.id("nexo_tv_result_range"))?.visibility==View.VISIBLE }
            env.await(scenario,"Long synopsis cannot be expanded") { it.findViewById<View>(env.id("nexo_tv_result_more"))?.visibility==View.VISIBLE }
            var rangeLabel=""
            scenario.onActivity { a -> rangeLabel=ViewModelProvider(fragment(a))[ResultViewModel2::class.java].rangeSelections.value!!.last().first!!.asString(a); a.findViewById<View>(env.id("nexo_tv_result_range")).performClick() }
            LiteUiAutomation.clickLabel(rangeLabel)
            env.await(scenario,"Last range not bound") { it.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes"))?.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(env.id("nexo_tv_episode_title"))?.text?.contains("Range 51")==true }
            var ep:com.lagradost.cloudstream3.ui.result.ResultEpisode?=null
            scenario.onActivity { a ->
                val model=ViewModelProvider(fragment(a))[ResultViewModel2::class.java]
                ep=(model.episodes.value as com.lagradost.cloudstream3.mvvm.Resource.Success).value.first()
                a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes")).findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                com.lagradost.cloudstream3.utils.DataStoreHelper.setViewPos(ep!!.id,60_000,300_000)
                com.lagradost.cloudstream3.utils.DataStoreHelper.setLastWatched(ep!!.parentId,ep!!.id,ep!!.episode,ep!!.season)
                model.reloadEpisodes()
            }
            env.await(scenario,"Resume action not updated") { it.findViewById<TextView>(env.id("nexo_tv_result_play"))?.text==it.getString(R.string.resume) }
            scenario.onActivity { assertEquals("Progress update stole selected episode",ep!!.apiName+"\u0000"+ep!!.data+"\u0000"+ep!!.id,it.currentFocus?.tag) }
            scenario.recreate()
            env.await(scenario,"Recreation lost selected episode") { it.currentFocus?.tag==ep!!.apiName+"\u0000"+ep!!.data+"\u0000"+ep!!.id }
            scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_result_play")).performClick() }
            env.await(scenario,"Resume changed original payload") { provider.extracts.contains("${provider.mainUrl}/range-51") }
        } }
    }
    @Test fun consumedAutostartDoesNotExtractAgainAfterRecreation() {
        ownResultExists()
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
                scenario.onActivity { env.register(); it.loadResult("${provider.mainUrl}/movie",provider.name,"Movie fixture",START_ACTION_RESUME_LATEST,0) }
                env.await(scenario,"Initial autostart did not reach provider") { provider.extracts.size==1 }
                scenario.recreate()
                env.await(scenario,"Details did not survive autostart recreation") { it.findViewById<TextView>(env.id("nexo_tv_result_title"))?.text?.toString()=="Movie fixture" }
                SystemClock.sleep(500)
                assertEquals("Recreated Details replayed consumed start action",1,provider.extracts.size)
                assertEquals(1,provider.loads.count { it.endsWith("/movie") })
            }
        }
    }
}
