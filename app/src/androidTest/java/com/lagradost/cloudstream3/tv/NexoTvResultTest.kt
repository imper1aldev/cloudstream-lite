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
    private class Provider(private val mediaUrl:String?=null) : MainAPI() {
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
                    listOf(newEpisode("season-1-episode-1") { season=1; episode=1; name="First";runTime=2700;description="The first episode synopsis." },
                        newEpisode("season-2-episode-7") { season=2; episode=7; name="Seventh" }))
                "empty" -> newTvSeriesLoadResponse("Empty fixture",url,TvType.TvSeries,emptyList())
                "many" -> newTvSeriesLoadResponse("Many seasons",url,TvType.TvSeries,(1..31).map { number -> newEpisode("many-$number") { season=number;episode=1;name="Season $number" } })
                "range" -> newTvSeriesLoadResponse("Range fixture",url,TvType.TvSeries,(1..65).map { number -> newEpisode("range-$number") { season=1; episode=number; name="Range $number" } }) { plot=("A long synopsis for the TV layout. ").repeat(80) }
                "anime" -> newAnimeLoadResponse("Anime fixture",url,TvType.Anime) {
                    addEpisodes(DubStatus.Subbed,listOf(newEpisode("sub-1") { episode=1; name="Sub" }))
                    addEpisodes(DubStatus.Dubbed,listOf(newEpisode("dub-1") { episode=1; name="Dub" }))
                }
                "hero" -> newMovieLoadResponse("Vista de detalles",url,TvType.Movie,"movie-data") {
                    year=2020;duration=148;tags=listOf("Accion","Ciencia ficcion","Drama")
                    plot="Una historia de viajes, misterios y aventuras que pone a prueba a sus protagonistas. ".repeat(8)
                }
                "supplemental" -> newMovieLoadResponse("Supplemental fixture",url,TvType.Movie,"movie-data") {
                    actors=(1..36).map { n -> ActorData(Actor("Person $n",if(n==2) null else "file:///missing-actor-$n.png"),roleString=if(n%2==1) "Character $n" else null) }
                    recommendations=listOf(newMovieSearchResponse("Original related title","$mainUrl/related?original=1",TvType.Movie))
                }
                "names-only" -> newMovieLoadResponse("Names only",url,TvType.Movie,"movie-data") { actors=listOf(ActorData(Actor("No photo person"))) }
                "soon" -> newMovieLoadResponse("Soon fixture",url,TvType.Movie,"") { comingSoon=true }
                else -> newMovieLoadResponse("Movie fixture",url,TvType.Movie,"movie-data") { plot="A short plot" }
            }
        }
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            extracts.add(data)
            mediaUrl?.let { url ->
                callback(com.lagradost.cloudstream3.utils.newExtractorLink("Local","[Fixture][LAT] Local",url,com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO) { quality=1080 })
                return true
            }
            return false // No network or player unless a test explicitly supplies local PCM media.
        }
    }
    @Test fun emptySupplementalRowsAreSkipped() {
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/movie",provider.name,"Movie fixture") }
            env.await(scenario,"Play missing") { it.currentFocus?.id==env.id("nexo_tv_result_play") }
            scenario.onActivity { a ->
                for(name in listOf("nexo_tv_result_actors","nexo_tv_result_actor_title","nexo_tv_result_actor_text","nexo_tv_result_recommendations","nexo_tv_result_recommendation_title")) {
                    val row=a.findViewById<View>(env.id(name));assertNotNull("Supplemental view missing: $name",row)
                    assertEquals("Empty row consumes space: $name",View.GONE,row.visibility)
                }
            }
            env.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { a -> assertFalse("Focus entered an empty row",a.currentFocus?.id==env.id("nexo_tv_result_actors")) }
        } }
    }
    @Test fun castScrollsWithoutFakeActionsAndNamesSurviveMissingImages() {
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/supplemental",provider.name,"Supplemental fixture") }
            env.await(scenario,"Cast not rendered") { it.findViewById<RecyclerView>(env.id("nexo_tv_result_actors"))?.adapter?.itemCount==36 }
            scenario.onActivity { a ->
                val row=a.findViewById<RecyclerView>(env.id("nexo_tv_result_actors"));row.requestFocus()
                assertFalse("Informational cast offers a fake click",row.hasOnClickListeners())
                val card=row.findViewHolderForAdapterPosition(0)!!.itemView
                assertFalse(card.isFocusable);assertFalse(card.hasOnClickListeners())
                assertTrue(card.findViewById<TextView>(env.id("nexo_tv_actor_name")).text=="Person 1")
                assertTrue(card.findViewById<TextView>(env.id("nexo_tv_actor_role")).text=="Character 1")
            }
            env.await(scenario,"Cast scroll container not focused") { it.currentFocus?.id==env.id("nexo_tv_result_actors") }
            repeat(8) { env.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
            env.await(scenario,"Remote did not scroll cast") { (it.findViewById<RecyclerView>(env.id("nexo_tv_result_actors"))?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.findFirstVisibleItemPosition()?.let { n -> n>0 }==true }
            env.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            env.await(scenario,"Cannot leave cast for related items") { it.currentFocus?.tag=="${provider.name}\u0000${provider.mainUrl}/related?original=1" }
            env.key(android.view.KeyEvent.KEYCODE_DPAD_UP)
            env.await(scenario,"Cannot return up to cast") { it.currentFocus?.id==env.id("nexo_tv_result_actors") }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/names-only",provider.name,"Names only") }
            env.await(scenario,"Names-only fallback missing") { it.findViewById<TextView>(env.id("nexo_tv_result_actor_text"))?.text?.contains("No photo person")==true }
            scenario.onActivity { a -> assertEquals(View.GONE,a.findViewById<View>(env.id("nexo_tv_result_actors")).visibility) }
        } }
    }
    @Test fun recommendationsKeepOriginalPayloadAndRestoreFocus() {
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/supplemental",provider.name,"Supplemental fixture") }
            env.await(scenario,"Related card missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_result_recommendations"))?.findViewHolderForAdapterPosition(0)!=null }
            val key="${provider.name}\u0000${provider.mainUrl}/related?original=1"
            scenario.onActivity { it.findViewById<RecyclerView>(env.id("nexo_tv_result_recommendations")).findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
            env.await(scenario,"Related card not focused") { it.currentFocus?.tag==key }
            env.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            env.await(scenario,"Original related URL was not loaded") { provider.loads.contains("${provider.mainUrl}/related?original=1") && it.findViewById<TextView>(env.id("nexo_tv_result_title"))?.text=="Movie fixture" }
            env.key(android.view.KeyEvent.KEYCODE_BACK)
            env.await(scenario,"Related origin focus not restored") { it.findViewById<TextView>(env.id("nexo_tv_result_title"))?.text=="Supplemental fixture" && it.currentFocus?.tag==key }
        } }
    }
    private fun ownResultExists() {
        try { Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvResultFragment") }
        catch(e: ClassNotFoundException) { throw AssertionError("Own TV Details missing",e) }
    }
    @Test fun singleEpisodeResumeRefreshAlsoWorksWithPhoneLayout() {
        val provider=Provider()
        NexoTvTestEnvironment(provider,layout=0).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var model:ResultViewModel2?=null
            scenario.onActivity { a ->
                env.register()
                model=ViewModelProvider(a)[ResultViewModel2::class.java]
                model!!.load(a,"${provider.mainUrl}/series",provider.name,false,DubStatus.None,null)
            }
            env.await(scenario,"Shared phone model episodes missing") { (model!!.episodes.value as? com.lagradost.cloudstream3.mvvm.Resource.Success)?.value?.size==1 }
            var id=0
            scenario.onActivity {
                val ep=(model!!.episodes.value as com.lagradost.cloudstream3.mvvm.Resource.Success).value.single();id=ep.id
                com.lagradost.cloudstream3.utils.DataStoreHelper.setViewPos(ep.id,50_000,200_000)
                com.lagradost.cloudstream3.utils.DataStoreHelper.setLastWatched(ep.parentId,ep.id,ep.episode,ep.season)
                model!!.reloadEpisodes()
            }
            env.await(scenario,"Single-episode phone resume was not refreshed") { model!!.resumeWatching.value?.result?.id==id }
            scenario.onActivity {
                assertEquals(50,model!!.resumeWatching.value!!.progress!!.progress)
                assertEquals(200,model!!.resumeWatching.value!!.progress!!.maxProgress)
                assertEquals(50_000L,(model!!.episodes.value as com.lagradost.cloudstream3.mvvm.Resource.Success).value.single().position)
            }
        } }
    }
    @Test fun resumeBannerUsesStoredEpisodeAcrossSeasonSelection() {
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun model(a:MainActivity)=ViewModelProvider((a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment!!)[ResultViewModel2::class.java]
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/series",provider.name,"Series fixture") }
            env.await(scenario,"Episodes missing") { (model(it).episodes.value as? com.lagradost.cloudstream3.mvvm.Resource.Success)?.value?.isNotEmpty()==true }
            scenario.onActivity { a ->
                assertTrue(a.findViewById<View>(env.id("nexo_tv_result_resume"))?.visibility!=View.VISIBLE)
                assertEquals("Episode runtime must use seconds, not minutes", "45m",a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes")).findViewHolderForAdapterPosition(0)!!.itemView.findViewById<TextView>(env.id("nexo_tv_episode_runtime")).text.toString())
                val ep=(model(a).episodes.value as com.lagradost.cloudstream3.mvvm.Resource.Success).value.first()
                com.lagradost.cloudstream3.utils.DataStoreHelper.setViewPos(ep.id,60_000,300_000)
                com.lagradost.cloudstream3.utils.DataStoreHelper.setLastWatched(ep.parentId,ep.id,ep.episode,ep.season)
                model(a).reloadEpisodes()
            }
            env.await(scenario,"Stored episode resume banner missing") { it.findViewById<TextView>(env.id("nexo_tv_result_resume_title"))?.text?.contains("First")==true }
            scenario.onActivity { a ->
                assertEquals(20,a.findViewById<android.widget.ProgressBar>(env.id("nexo_tv_result_resume_progress")).progress)
                assertFalse(a.findViewById<View>(env.id("nexo_tv_result_resume")).isFocusable)
                model(a).changeSeason(2)
            }
            env.await(scenario,"Season2 card not bound") { it.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes"))?.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(env.id("nexo_tv_episode_title"))?.text=="Seventh" }
            scenario.onActivity { a ->
                assertTrue(a.findViewById<TextView>(env.id("nexo_tv_result_resume_title")).text.contains("First"))
                assertTrue("Unknown runtime leaked from prior season",a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes")).findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(env.id("nexo_tv_episode_runtime")).visibility!=View.VISIBLE)
                a.findViewById<View>(env.id("nexo_tv_result_play")).performClick()
            }
            env.await(scenario,"Reanudar used the browsed season instead of saved episode") { provider.extracts.contains("${provider.mainUrl}/season-1-episode-1") }
        } }
    }
    @Test fun returningFromPlayerKeepsEpisodeIdentity() {
        com.lagradost.cloudstream3.LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val provider=Provider(server.url("episode.wav"))
            NexoTvTestEnvironment(provider).use { env ->
                val paused=com.lagradost.cloudstream4.AppSettings(env.context).player.startPaused;val old=paused.get();paused.set(false)
                try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    fun fragment(a:MainActivity)=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment
                    env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
                    scenario.onActivity { it.loadResult("${provider.mainUrl}/series",provider.name,"Series fixture") }
                    env.await(scenario,"Details initial focus missing") { it.currentFocus?.id==env.id("nexo_tv_result_play") }
                    scenario.onActivity { ViewModelProvider(fragment(it)!!)[ResultViewModel2::class.java].changeSeason(2) }
                    env.await(scenario,"Season2 card missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes"))?.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(env.id("nexo_tv_episode_title"))?.text?.contains("Seventh")==true }
                    var key:Any?=null
                    scenario.onActivity { a ->
                        val card=a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes")).findViewHolderForAdapterPosition(0)!!.itemView
                        card.requestFocus();key=card.tag
                    }
                    env.await(scenario,"Episode did not receive focus before playback") { it.currentFocus?.tag==key }
                    scenario.onActivity { a -> a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes")).findViewHolderForAdapterPosition(0)!!.itemView.performClick() }
                    env.await(scenario,"Native player did not start local media") { (fragment(it) as? com.lagradost.cloudstream3.ui.player.GeneratorPlayer)?.playerView?.player?.let { p -> p.isPlaying && p.currentPosition>0 }==true }
                    env.key(android.view.KeyEvent.KEYCODE_BACK)
                    env.instrumentation.waitForIdleSync()
                    var stillPlayer=false;scenario.onActivity { stillPlayer=fragment(it) is com.lagradost.cloudstream3.ui.player.GeneratorPlayer }
                    // Native TV Back first hides visible controls, then exits; preserve that behavior.
                    if(stillPlayer) env.key(android.view.KeyEvent.KEYCODE_BACK)
                    env.await(scenario,"Return did not restore same episode identity") { fragment(it) is com.lagradost.cloudstream3.ui.tv.NexoTvResultFragment && it.currentFocus?.tag==key }
                    assertTrue(server.requests.contains("/episode.wav"))
                    assertEquals("Original episode payload changed",listOf("${provider.mainUrl}/season-2-episode-7"),provider.extracts.toList())
                } } finally { paused.set(old) }
            }
        }
    }
    @Test fun emptyAndManySeasonsKeepNavigationUsable() {
        val provider=Provider()
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Home not ready") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/empty",provider.name,"Empty fixture") }
            env.await(scenario,"Empty title not loaded") { it.findViewById<TextView>(env.id("nexo_tv_result_title"))?.text=="Empty fixture" }
            scenario.onActivity { a ->
                assertTrue(a.findViewById<View>(env.id("nexo_tv_result_episodes")).visibility!=View.VISIBLE)
                assertTrue(a.findViewById<View>(env.id("nexo_tv_result_play")).visibility!=View.VISIBLE)
            }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/many",provider.name,"Many seasons") }
            env.await(scenario,"Many-season selector missing") { it.findViewById<View>(env.id("nexo_tv_result_season"))?.isShown==true }
            scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_result_season")).performClick() }
            // Scroll the native choice list instead of generating 31 inline tabs.
            repeat(35) { env.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
            env.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            env.await(scenario,"Last season payload not shown") { it.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes"))?.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(env.id("nexo_tv_episode_title"))?.text?.contains("Season 31")==true }
        } }
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
