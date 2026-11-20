package com.lagradost.cloudstream3.tv

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import org.junit.Assert.*
import org.junit.Test

class NexoTvDetailsSectionsTest {
    private class Provider:MainAPI() {
        override var name="NexoStream"
        override var mainUrl="https://nexo-sections-${System.nanoTime()}.invalid"
        override val hasMainPage=true
        override val hasChromecastSupport=false
        override val hasDownloadSupport=false
        override val mainPage=mainPageOf("series" to "Fixture")
        override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,listOf(newTvSeriesSearchResponse("Sections","$mainUrl/full",TvType.TvSeries)),false)
        override suspend fun load(url:String)=newTvSeriesLoadResponse("Section framing",url,TvType.TvSeries,
            (1..20).map { n -> newEpisode("episode-$n") {
                episode=n;season=if(url.endsWith("single")) 1 else if(n<=10) 1 else 2;name="Episode $n"
                if(url.endsWith("full") || (url.endsWith("mixed") && n==2)) { description="A useful episode synopsis that occupies two lines in the card.";runTime=2700 }
            } }) {
            plot=if(url.endsWith("full")) "A story with enough detail to test focus-driven section framing and natural content layout. ".repeat(12) else "Short synopsis."
            if(url.endsWith("full")) {
                year=2020;tags=listOf("Drama","Mystery")
                actors=(1..8).map { ActorData(Actor("Actor $it","file:///missing-actor.png"),roleString="Role $it") }
                recommendations=(1..12).map { newMovieSearchResponse("Related $it","$mainUrl/movie-$it",TvType.Movie) }
            }
        }
    }
    private fun rect(v:View)=Rect().also { v.getDrawingRect(it);(v.rootView as android.view.ViewGroup).offsetDescendantRectToMyCoords(v,it) }
    private fun open(env:NexoTvTestEnvironment,s:ActivityScenario<MainActivity>,p:Provider,path:String) {
        s.onActivity { it.loadResult("${p.mainUrl}/$path",p.name,"Section framing") }
        env.await(s,"Details initial Play focus missing") { it.currentFocus?.id==env.id("nexo_tv_result_play") && it.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes"))?.adapter?.itemCount?.let { n -> n>0 }==true }
        env.instrumentation.waitForIdleSync()
    }
    private fun launch(p:Provider,body:(NexoTvTestEnvironment,ActivityScenario<MainActivity>)->Unit) {
        NexoTvTestEnvironment(p).use { env -> ActivityScenario.launch(MainActivity::class.java).use { s ->
            env.await(s,"Home missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            body(env,s)
        } }
    }
    private fun settle(env:NexoTvTestEnvironment) { env.instrumentation.waitForIdleSync();android.os.SystemClock.sleep(650) }
    @Test fun remoteSectionRoundTripFramesHeroEpisodesAndRecommendations() {
        val p=Provider();launch(p) { env,s ->
            open(env,s,p,"full")
            env.key(KeyEvent.KEYCODE_DPAD_DOWN);settle(env)
            s.onActivity { a ->
                assertEquals("One Down must reach season selector",env.id("nexo_tv_result_season"),a.currentFocus?.id)
                val scroll=a.findViewById<View>(env.id("nexo_tv_result_scroll"));val season=a.findViewById<View>(env.id("nexo_tv_result_season"))
                assertTrue("Episode section not framed at top",rect(season).top-rect(scroll).top in 0..(48*a.resources.displayMetrics.density).toInt())
            }
            env.key(KeyEvent.KEYCODE_DPAD_DOWN);settle(env)
            s.onActivity { a -> assertTrue("Episode not focused",a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes")).hasFocus()) }
            env.key(KeyEvent.KEYCODE_DPAD_DOWN);settle(env)
            s.onActivity { a -> assertTrue("Actor rail not focused",a.findViewById<View>(env.id("nexo_tv_result_actors")).hasFocus()) }
            env.key(KeyEvent.KEYCODE_DPAD_DOWN);settle(env)
            s.onActivity { a ->
                assertTrue("Recommendations not focused",a.findViewById<View>(env.id("nexo_tv_result_recommendations")).hasFocus())
                val header=a.findViewById<View>(env.id("nexo_tv_result_recommendation_title"));val scroll=a.findViewById<View>(env.id("nexo_tv_result_scroll"))
                assertTrue("Recommendation heading lost above viewport",rect(header).top>=rect(scroll).top)
            }
            repeat(4) { env.key(KeyEvent.KEYCODE_DPAD_UP);settle(env) }
            s.onActivity { a ->
                assertEquals("One Up per section must return to Play",env.id("nexo_tv_result_play"),a.currentFocus?.id)
                val title=a.findViewById<View>(env.id("nexo_tv_result_title"));val visible=Rect()
                assertTrue("Returning Play focus did not restore hero",title.getGlobalVisibleRect(visible) && visible.height()==title.height)
            }
        }
    }
    @Test fun returningToPlayRestoresHeroWithoutExtraPresses() {
        val p=Provider();launch(p) { env,s ->
            open(env,s,p,"full")
            s.onActivity { a -> a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes")).findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
            settle(env)
            env.key(KeyEvent.KEYCODE_DPAD_UP);settle(env)
            s.onActivity { a -> assertEquals(env.id("nexo_tv_result_season"),a.currentFocus?.id) }
            env.key(KeyEvent.KEYCODE_DPAD_UP);settle(env)
            s.onActivity { a ->
                assertEquals(env.id("nexo_tv_result_play"),a.currentFocus?.id)
                val title=a.findViewById<View>(env.id("nexo_tv_result_title"));val visible=Rect()
                assertTrue("Play focused but hero still scrolled away",title.getGlobalVisibleRect(visible) && visible.height()==title.height)
            }
        }
    }
    @Test fun mixedEpisodeCardsFitContentWithBalancedBottomPadding() {
        val p=Provider();launch(p) { env,s ->
            open(env,s,p,"mixed")
            s.onActivity { a ->
                val row=a.findViewById<RecyclerView>(env.id("nexo_tv_result_episodes"));val plain=row.findViewHolderForAdapterPosition(0)!!.itemView;val detailed=row.findViewHolderForAdapterPosition(1)!!.itemView
                val blank=plain.findViewById<View>(env.id("nexo_tv_episode_description"));assertEquals(View.GONE,blank.visibility)
                assertTrue("Missing description reserves the same height",plain.height<detailed.height)
                for(card in listOf(plain,detailed)) {
                    val last=listOf("nexo_tv_episode_title","nexo_tv_episode_runtime","nexo_tv_episode_description").map { card.findViewById<View>(env.id(it)) }.filter { it.visibility!=View.GONE }.maxOf { it.bottom }
                    assertEquals("Unused bottom space inside episode card",card.paddingBottom,card.height-last)
                }
                assertTrue("Episode rail reserves empty height",row.height<=maxOf(plain.height,detailed.height)+row.paddingTop+row.paddingBottom+2)
            }
        }
    }
    @Test fun sparseHeroKeepsNextSectionCloseWithAndWithoutSelectors() {
        val p=Provider();launch(p) { env,s ->
            for(path in listOf("single","seasons")) {
                open(env,s,p,path)
                s.onActivity { a ->
                    val play=a.findViewById<View>(env.id("nexo_tv_result_play"))
                    val controls=a.findViewById<View>(env.id("nexo_tv_result_episode_controls"))
                    val next=if(controls.visibility==View.VISIBLE) controls else a.findViewById<View>(env.id("nexo_tv_result_episode_status"))
                    val gap=rect(next).top-rect(play).bottom
                    assertTrue("Sparse hero reserves excessive gap: $gap",gap in 0..(36*a.resources.displayMetrics.density).toInt())
                }
            }
        }
    }
}
