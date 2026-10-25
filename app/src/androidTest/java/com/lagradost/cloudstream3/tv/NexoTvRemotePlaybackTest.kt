package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.ensureAllowedProvider
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Live-provider acceptance, deliberately separate from deterministic UI/player regression fixtures. */
class NexoTvRemotePlaybackTest {
    private fun SearchResponse.releaseYear():Int? = when(this) { is MovieSearchResponse -> year; is TvSeriesSearchResponse -> year; is AnimeSearchResponse -> year; else -> null }
    private fun host(a:MainActivity)=a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
    @Test fun movieKoreanSeriesAndAnimeReachUnchangedPlayer() {
        val report=JSONArray()
        val kindFilter=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("nexo_tv_remote_kind")
        require(kindFilter==null || kindFilter in setOf("movie","korean","anime"))
        val reportName="nexo-tv-remote-playback"+(kindFilter?.let { "-$it" } ?: "")+".json"
        NexoTvTestEnvironment().use { env ->
            val paused=AppSettings(env.context).player.startPaused
            val oldPaused=paused.get();paused.set(false)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var activity:MainActivity?=null
                scenario.onActivity { activity=it }
                val ready=runBlocking(Dispatchers.IO) { withTimeout(90_000) { ensureAllowedProvider(checkNotNull(activity)) } }
                check(ready) { "Allowed provider unavailable" }
                val api=checkNotNull(APIHolder.getApiFromNameNull("NexoStream"))
                val cases=listOf(Triple("movie","Inception",2010),Triple("korean","True Beauty",2020),Triple("anime","Solo Leveling",2024)).filter { kindFilter==null || it.first==kindFilter }
                for ((kind,query,year) in cases) {
                    val item=JSONObject().put("kind",kind).put("query",query).put("year",year)
                    report.put(item)
                    val started=SystemClock.elapsedRealtime()
                    try {
                        val search=runBlocking(Dispatchers.IO) { withTimeout(60_000) { checkNotNull(api.search(query,1)) } }
                        val match=search.items.firstOrNull { it.releaseYear()==year } ?: error("Expected release year absent; candidates="+search.items.map { "${it.name} (${it.releaseYear()})" })
                        item.put("title",match.name)
                        scenario.onActivity { it.loadResult(match.url,match.apiName,match.name) }
                        wait(scenario,60_000) { a -> (host(a).childFragmentManager.primaryNavigationFragment as? com.lagradost.cloudstream3.ui.tv.NexoTvResultFragment)?.let { ViewModelProvider(it)[ResultViewModel2::class.java].page.value is Resource.Success }==true }
                        var selectedSeason:Int?=null
                        scenario.onActivity { a ->
                            val model=ViewModelProvider(host(a).childFragmentManager.primaryNavigationFragment!!)[ResultViewModel2::class.java]
                            if(kind!="movie") {
                                selectedSeason=model.seasonSelections.value?.firstOrNull { it.second>0 }?.second
                                selectedSeason?.let(model::changeSeason)
                            }
                        }
                        if(selectedSeason!=null) wait(scenario,15_000) { a ->
                            val model=ViewModelProvider(host(a).childFragmentManager.primaryNavigationFragment!!)[ResultViewModel2::class.java]
                            (model.episodes.value as? Resource.Success)?.value?.firstOrNull()?.seasonIndex==selectedSeason
                        }
                        wait(scenario,15_000) { it.findViewById<View>(env.id("nexo_tv_result_play"))?.isShown==true }
                        scenario.onActivity { a ->
                            val model=ViewModelProvider(host(a).childFragmentManager.primaryNavigationFragment!!)[ResultViewModel2::class.java]
                            val ep=(model.episodes.value as? Resource.Success)?.value?.firstOrNull() ?: (model.movie.value as? Resource.Success)?.value?.second
                            item.put("season",ep?.season).put("episode",ep?.episode)
                            a.findViewById<View>(env.id("nexo_tv_result_play")).requestFocus()
                        }
                        env.key(KeyEvent.KEYCODE_DPAD_CENTER)
                        var firstPosition=0L
                        wait(scenario,150_000) { a ->
                            val player=(host(a).childFragmentManager.primaryNavigationFragment as? GeneratorPlayer)?.playerView?.player
                            if(player?.isPlaying==true && player.currentPosition>500) { firstPosition=player.currentPosition;true } else false
                        }
                        item.put("startedMs",SystemClock.elapsedRealtime()-started)
                        SystemClock.sleep(3000)
                        scenario.onActivity { a ->
                            val fragment=host(a).childFragmentManager.primaryNavigationFragment as GeneratorPlayer
                            val player=checkNotNull(fragment.playerView?.player)
                            val state=ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java].state
                            @Suppress("UNCHECKED_CAST")
                            val selected=GeneratorPlayer::class.java.getDeclaredField("currentSelectedLink").apply { isAccessible=true }.get(fragment) as? com.lagradost.cloudstream3.ui.player.VideoLink
                            item.put("server",selected?.first?.name).put("quality",selected?.first?.quality)
                                .put("links",state.links.size).put("extracting",state.loading is Resource.Loading)
                                .put("positionMs",player.currentPosition).put("playWhenReady",player.playWhenReady)
                            assertTrue("Playback stopped progressing",player.isPlaying && player.currentPosition>firstPosition)
                        }
                        item.put("success",true)
                    } catch(error:Throwable) {
                        // No tokenized URLs or headers in the artifact.
                        item.put("success",false).put("failure",error.javaClass.simpleName+": "+error.message.orEmpty().substringBefore("https://").take(250))
                    } finally {
                        File(env.context.getExternalFilesDir(null),reportName).writeText(report.toString(2))
                        scenario.onActivity { a ->
                            val nav=host(a).navController
                            while(nav.currentDestination?.id!=R.id.navigation_home && nav.popBackStack()) { }
                        }
                    }
                }
            } } finally { paused.set(oldPaused) }
        }
        assertEquals("Live acceptance failures; inspect $reportName",if(kindFilter==null)3 else 1,(0 until report.length()).count { report.getJSONObject(it).optBoolean("success") })
    }
    private fun wait(scenario:ActivityScenario<MainActivity>,timeout:Long,condition:(MainActivity)->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<deadline) {
            var done=false;scenario.onActivity { done=condition(it) }
            if(done)return
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out after ${timeout/1000}s")
    }
}
