package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppContextUtils.isCastApiAvailable
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class NexoTvSecondaryActionsTest {
    private class Provider(val trailer:String?=null, val raw:Boolean=true, cast:Boolean=false):MainAPI() {
        override var name="NexoStream"
        override var mainUrl="https://nexo-secondary-${System.nanoTime()}.invalid"
        override val hasMainPage=true
        override val hasDownloadSupport=false
        override val hasChromecastSupport=cast
        override val mainPage=mainPageOf("movie" to "Fixture")
        val extracts=CopyOnWriteArrayList<String>()
        override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,listOf(newMovieSearchResponse("TV actions fixture","$mainUrl/movie",TvType.Movie)),false)
        override suspend fun load(url:String):LoadResponse {
            val response=if(url.endsWith("/series")) newTvSeriesLoadResponse("TV actions fixture",url,TvType.TvSeries,listOf(
                newEpisode("original-first") { season=1;episode=1;name="First" },newEpisode("original-seventh") { season=2;episode=7;name="Seventh" }))
            else newMovieLoadResponse("TV actions fixture",url,TvType.Movie,"original-movie") { comingSoon=url.endsWith("/soon") }
            response.trailers=trailer?.let { listOf(TrailerData(it,"https://trailer-reference.invalid/",raw,mapOf("X-Nexo-Fixture" to "trailer"))) }.orEmpty().toMutableList()
            return response
        }
        override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean { extracts.add(data);return false }
    }
    private fun result(a:MainActivity)=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment!!
    private fun model(a:MainActivity)=ViewModelProvider(result(a))[ResultViewModel2::class.java]
    private fun dialog(a:MainActivity)=result(a).childFragmentManager.findFragmentByTag("nexo_tv_trailer") as? DialogFragment
    private fun trailerPlayer(a:MainActivity)=dialog(a)?.view?.findViewById<androidx.media3.ui.PlayerView>(a.resources.getIdentifier("nexo_tv_trailer_player","id",a.packageName))?.player
    private fun labels(env:NexoTvTestEnvironment)=LiteUiAutomation.nodes(env.instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { (it.text ?: it.contentDescription)?.toString() }
    private fun open(env:NexoTvTestEnvironment,s:ActivityScenario<MainActivity>,p:Provider,path:String="movie") {
        env.await(s,"Home missing") { it.findViewById<RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
        s.onActivity { it.loadResult("${p.mainUrl}/$path",p.name,"TV actions fixture") }
        env.await(s,"Details Play missing") { (model(it).page.value as? Resource.Success)?.value?.url=="${p.mainUrl}/$path" && (it.currentFocus?.id==env.id("nexo_tv_result_play") || path=="soon") }
    }
    private fun pressTrailer(env:NexoTvTestEnvironment,s:ActivityScenario<MainActivity>) {
        env.await(s,"Trailer action missing") { it.findViewById<View>(env.id("nexo_tv_result_trailer"))?.isShown==true }
        s.onActivity { it.findViewById<View>(env.id("nexo_tv_result_play")).requestFocus() }
        env.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        env.await(s,"Remote cannot reach Favorite") { it.currentFocus?.id==env.id("nexo_tv_result_favorite") }
        env.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        env.await(s,"Remote cannot reach Trailer") { it.currentFocus?.id==env.id("nexo_tv_result_trailer") }
        env.instrumentation.waitForIdleSync()
        s.onActivity { a ->
            val title=a.findViewById<View>(env.id("nexo_tv_result_title"));val rect=android.graphics.Rect()
            assertTrue("Trailer focus cropped the hero title",title.getGlobalVisibleRect(rect) && rect.height()==title.height)
            assertEquals(a.getString(R.string.trailer),a.currentFocus?.contentDescription)
        }
        env.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun capture(env:NexoTvTestEnvironment,name:String) {
        env.instrumentation.waitForIdleSync();SystemClock.sleep(500)
        env.instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(env.context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
    }
    @Test fun lateTrailerAppearsWithoutStartingPlayback() {
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val gate=CompletableDeferred<Unit>()
            val extractor=object:ExtractorApi() {
                override val name="TV trailer fixture"
                override val mainUrl="https://nexo-trailer-${System.nanoTime()}.invalid"
                override val requiresReferer=false
                override suspend fun getUrl(url:String,referer:String?,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit) {
                    gate.await();callback(newExtractorLink(name,name,server.url("late.wav"),ExtractorLinkType.VIDEO) { quality=1080 })
                }
            }
            extractorApis.add(extractor)
            try {
                val p=Provider(extractor.mainUrl+"/video",raw=false)
                NexoTvTestEnvironment(p).use { env -> ActivityScenario.launch(MainActivity::class.java).use { s ->
                    open(env,s,p)
                    s.onActivity { assertFalse(it.findViewById<View>(env.id("nexo_tv_result_trailer"))?.isShown==true) }
                    gate.complete(Unit)
                    env.await(s,"Late usable trailer did not appear") { it.findViewById<View>(env.id("nexo_tv_result_trailer"))?.isShown==true }
                    assertTrue("Trailer was fetched before an explicit click",server.requests.isEmpty())
                    assertTrue(p.extracts.isEmpty())
                } }
            } finally { gate.complete(Unit);extractorApis.remove(extractor) }
        }
    }
    @Test fun closingTrailerRestoresFocusAndEpisodeHistory() {
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val p=Provider(server.url("trailer.wav"))
            NexoTvTestEnvironment(p).use { env ->
                val paused=com.lagradost.cloudstream4.AppSettings(env.context).player.startPaused;val old=paused.get();paused.set(false)
                try { ActivityScenario.launch(MainActivity::class.java).use { s ->
                    open(env,s,p,"series")
                    var episodeId=0;var parentId=0
                    s.onActivity { a ->
                        val m=model(a);val ep=(m.episodes.value as Resource.Success).value.first();episodeId=ep.id;parentId=ep.parentId
                        DataStoreHelper.setViewPos(ep.id,9000,60000);DataStoreHelper.setLastWatched(ep.parentId,ep.id,ep.episode,ep.season);m.changeSeason(2)
                    }
                    env.await(s,"Season2 not selected") { (model(it).episodes.value as? Resource.Success)?.value?.firstOrNull()?.season==2 }
                    assertTrue(server.requests.isEmpty())
                    capture(env,"phase4-controls.png");pressTrailer(env,s)
                    env.await(s,"Native trailer did not start") { trailerPlayer(it)?.let { player -> player.isPlaying && player.currentPosition>0 }==true }
                    capture(env,"phase4-native-trailer.png")
                    assertTrue(server.requestHeaders.any { hs -> hs.any { it.equals("X-Nexo-Fixture: trailer",true) } && hs.any { it.equals("Referer: https://trailer-reference.invalid/",true) } })
                    env.key(android.view.KeyEvent.KEYCODE_BACK)
                    env.await(s,"Trailer close lost Details focus") { dialog(it)==null && it.currentFocus?.id==env.id("nexo_tv_result_trailer") }
                    s.onActivity { a ->
                        assertEquals(2,(model(a).episodes.value as Resource.Success).value.first().season)
                        assertEquals(9000L,DataStoreHelper.getViewPos(episodeId)?.position)
                        assertEquals(episodeId,DataStoreHelper.getLastWatched(parentId)?.episodeId)
                    }
                    assertTrue("Episode extraction ran during trailer",p.extracts.isEmpty())
                } } finally { paused.set(old) }
            }
        }
    }
    @Test fun trailerFailureAndBufferingHaveAWorkingExit() {
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            for(path in listOf("fail","wait")) {
                val p=Provider(server.url(path))
                NexoTvTestEnvironment(p).use { env -> ActivityScenario.launch(MainActivity::class.java).use { s ->
                    open(env,s,p);pressTrailer(env,s)
                    if(path=="fail") env.await(s,"Failed trailer has no error exit") { dialog(it)?.view?.findViewById<View>(env.id("nexo_tv_trailer_error"))?.isShown==true && dialog(it)?.view?.findViewById<View>(env.id("nexo_tv_trailer_close"))?.hasFocus()==true }
                    else env.await(s,"Trailer did not buffer") { trailerPlayer(it)?.playbackState==Player.STATE_BUFFERING }
                    env.key(android.view.KeyEvent.KEYCODE_BACK)
                    env.await(s,"Cannot close failed or buffering trailer") { dialog(it)==null && it.currentFocus?.id==env.id("nexo_tv_result_trailer") }
                    assertTrue(p.extracts.isEmpty())
                } }
            }
        }
    }
    @Test fun pausedTrailerRecreationPreservesUserIntent() {
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val p=Provider(server.url("recreate.wav"))
            NexoTvTestEnvironment(p).use { env ->
                val paused=com.lagradost.cloudstream4.AppSettings(env.context).player.startPaused;val old=paused.get();paused.set(false)
                try { ActivityScenario.launch(MainActivity::class.java).use { s ->
                    open(env,s,p);pressTrailer(env,s)
                    env.await(s,"Trailer did not play") { trailerPlayer(it)?.isPlaying==true }
                    s.onActivity { trailerPlayer(it)!!.apply { seekTo(4000);pause() } }
                    env.await(s,"Trailer pause not applied") { trailerPlayer(it)?.let { x -> !x.playWhenReady && x.currentPosition>=4000 }==true }
                    s.recreate()
                    env.await(s,"Paused trailer intent lost on recreation") { trailerPlayer(it)?.let { x -> x.playbackState==Player.STATE_READY && !x.playWhenReady && x.currentPosition>=3900 }==true }
                    env.key(android.view.KeyEvent.KEYCODE_BACK)
                    env.await(s,"Recreated trailer cannot return to Details") { dialog(it)==null && it.currentFocus?.id==env.id("nexo_tv_result_trailer") }
                    assertTrue(p.extracts.isEmpty())
                } } finally { paused.set(old) }
            }
        }
    }
    @Test fun moreUsesNativeOptionsAndRestoresFocus() {
        val p=Provider()
        NexoTvTestEnvironment(p).use { env -> ActivityScenario.launch(MainActivity::class.java).use { s ->
            open(env,s,p)
            env.await(s,"More missing with native options") { it.findViewById<View>(env.id("nexo_tv_result_more_actions"))?.isShown==true }
            s.onActivity { a -> assertFalse(a.findViewById<View>(env.id("nexo_tv_result_trailer"))?.isShown==true);a.findViewById<View>(env.id("nexo_tv_result_more_actions")).apply { requestFocus();performClick() } }
            env.await(s,"Original option menu did not open") { labels(env).contains(env.context.getString(R.string.episode_action_play_in_app)) }
            assertFalse(labels(env).contains(env.context.getString(R.string.lite_cast_action)))
            env.key(android.view.KeyEvent.KEYCODE_BACK)
            env.await(s,"Native option cancel did not restore More") { it.currentFocus?.id==env.id("nexo_tv_result_more_actions") }
            s.onActivity { it.loadResult("${p.mainUrl}/soon",p.name,"TV actions fixture") }
            env.await(s,"Soon page missing") { (model(it).page.value as? Resource.Success)?.value?.comingSoon==true }
            s.onActivity { assertFalse("Empty More is visible",it.findViewById<View>(env.id("nexo_tv_result_more_actions"))?.isShown==true) }
        } }
    }
    @Test fun trailerControlsRespectTvMarginsAndFullVideoViewport() {
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val p=Provider(server.url("bounds.wav"))
            NexoTvTestEnvironment(p).use { env -> ActivityScenario.launch(MainActivity::class.java).use { s ->
                open(env,s,p);pressTrailer(env,s)
                env.await(s,"Trailer view missing") { trailerPlayer(it)!=null }
                env.instrumentation.waitForIdleSync()
                s.onActivity { a ->
                    val root=dialog(a)!!.requireView();val density=a.resources.displayMetrics.density
                    val video=root.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
                    val full=android.graphics.Rect();val content=android.graphics.Rect()
                    root.getGlobalVisibleRect(full);video.getGlobalVisibleRect(content)
                    assertEquals("Safe margins shrank the video",full,content)
                    for(id in listOf(androidx.media3.ui.R.id.exo_bottom_bar,androidx.media3.ui.R.id.exo_progress)) {
                        val v=root.findViewById<View>(id);val bounds=android.graphics.Rect()
                        assertTrue("Native controls hidden",v.getGlobalVisibleRect(bounds))
                        assertTrue("Control reaches left TV edge",bounds.left>=full.left+(48*density).toInt())
                        assertTrue("Control reaches right TV edge",bounds.right<=full.right-(48*density).toInt())
                        assertTrue("Control reaches bottom TV edge",bounds.bottom<=full.bottom-(27*density).toInt())
                    }
                }
                capture(env,"phase4-polish-safe-controls.png")
                env.key(android.view.KeyEvent.KEYCODE_BACK)
                env.await(s,"Safe controller cannot return to Details") { dialog(it)==null && it.currentFocus?.id==env.id("nexo_tv_result_trailer") }
            } }
        }
    }
    @Test fun trailerHeaderFollowsControlsAndKeepsFocusedExitVisible() {
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val p=Provider(server.url("chrome.wav"))
            NexoTvTestEnvironment(p).use { env ->
                val paused=com.lagradost.cloudstream4.AppSettings(env.context).player.startPaused;val old=paused.get();paused.set(false)
                try { ActivityScenario.launch(MainActivity::class.java).use { s ->
                    open(env,s,p);pressTrailer(env,s)
                    env.await(s,"Trailer not playing") { trailerPlayer(it)?.isPlaying==true }
                    env.await(s,"Header stayed visible after controller timeout") { a ->
                        val root=dialog(a)?.view
                        root?.findViewById<View>(env.id("nexo_tv_trailer_close"))?.isShown==false &&
                            root.findViewById<androidx.media3.ui.PlayerView>(env.id("nexo_tv_trailer_player"))?.isControllerFullyVisible==false
                    }
                    capture(env,"phase4-polish-chrome-hidden.png")
                    env.key(android.view.KeyEvent.KEYCODE_DPAD_UP)
                    env.await(s,"Remote did not reveal trailer header") { dialog(it)?.view?.findViewById<View>(env.id("nexo_tv_trailer_close"))?.isShown==true }
                    s.onActivity { a ->
                        val root=dialog(a)!!.requireView()
                        assertTrue(root.findViewById<View>(env.id("nexo_tv_trailer_close")).requestFocus())
                        root.findViewById<androidx.media3.ui.PlayerView>(env.id("nexo_tv_trailer_player")).hideController()
                    }
                    env.instrumentation.waitForIdleSync()
                    s.onActivity { a ->
                        val close=dialog(a)!!.requireView().findViewById<View>(env.id("nexo_tv_trailer_close"))
                        assertTrue("Focused exit was hidden",close.isShown && close.hasFocus())
                    }
                    env.key(android.view.KeyEvent.KEYCODE_BACK)
                    env.await(s,"Hidden controller changed Back behavior") { dialog(it)==null && it.currentFocus?.id==env.id("nexo_tv_result_trailer") }
                    assertTrue(p.extracts.isEmpty())
                } } finally { paused.set(old) }
            }
        }
    }
    @Test fun castOnlyMoreMatchesNativeAvailability() {
        val p=Provider(cast=true)
        NexoTvTestEnvironment(p).use { env -> ActivityScenario.launch(MainActivity::class.java).use { s ->
            open(env,s,p,"soon")
            var available=false
            s.onActivity { available=it.isCastApiAvailable() }
            File(env.context.getExternalFilesDir(null),"phase4-cast-capability.txt").writeText("nativeCastApiAvailable=$available")
            if(available) {
                env.await(s,"Single Cast entry must keep More") { it.findViewById<View>(env.id("nexo_tv_result_more_actions"))?.isShown==true }
                s.onActivity { it.findViewById<View>(env.id("nexo_tv_result_more_actions")).apply { requestFocus();performClick() } }
                env.await(s,"Native Cast chooser button missing") { labels(env).contains(env.context.getString(R.string.lite_cast_action)) }
                assertFalse(labels(env).contains(env.context.getString(R.string.lite_options)))
                capture(env,"phase4-cast-only-more.png")
                env.key(android.view.KeyEvent.KEYCODE_BACK)
                env.await(s,"Cast-only More lost return focus") { it.currentFocus?.id==env.id("nexo_tv_result_more_actions") }
            } else s.onActivity { assertFalse(it.findViewById<View>(env.id("nexo_tv_result_more_actions"))?.isShown==true) }
        } }
    }
}

