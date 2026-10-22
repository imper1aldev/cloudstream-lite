package com.lagradost.cloudstream3

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.lite.*
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in measurement: no fixed source preference or timeout is inferred from the title. */
class LiteTrueBeautyBenchmarkTest {
    @Test fun measureEpisodeOne() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureStartup")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=PreferenceManager.getDefaultSharedPreferences(context);val key=context.getString(R.string.app_layout_key);val previous=prefs.all[key]
        val startPaused=AppSettings(context).player.startPaused;val previousPaused=startPaused.get()
        val output=File(context.cacheDir,"true-beauty-startup.jsonl").apply { writeText("") }
        fun record(data:Map<String,Any?>) { output.appendText(JSONObject(data).toString()+"\n") }
        val ownedIds=mutableListOf<Int>()
        prefs.edit().putInt(key,0).commit();context.updateTv();startPaused.set(false)
        LitePlayerProbeActivity.instrumentedPlayer=null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { boot ->
                val providerDeadline=SystemClock.elapsedRealtime()+20000
                while(APIHolder.getApiFromNameNull("NexoStream")==null && SystemClock.elapsedRealtime()<providerDeadline)delay(100)
                val provider=checkNotNull(APIHolder.getApiFromNameNull("NexoStream"))
                val results=withTimeout(45000) { provider.search("True Beauty") }.orEmpty()
                record(mapOf("stage" to "search","titles" to results.map { it.name },"provider_class" to provider.javaClass.name))
                var detail:TvSeriesLoadResponse?=null
                for(result in results.sortedBy { if(it.name.equals("Belleza verdadera",true))0 else 1 }) {
                    val candidate=withTimeout(60000) { provider.load(result.url) }
                    record(mapOf("stage" to "metadata","title" to candidate?.name,"year" to candidate?.year,"kind" to candidate?.type?.name))
                    if(candidate is TvSeriesLoadResponse && candidate.year==2020) { detail=candidate;break }
                }
                val page=checkNotNull(detail) { "True Beauty live-action 2020 metadata not found" }
                val episode=page.episodes.first { (it.season ?: 1)==1 && it.episode==1 }
                record(mapOf("stage" to "target","title" to page.name,"year" to page.year,"season" to episode.season,"episode" to episode.episode))
                val links=mutableListOf<ExtractorLink>()
                var initial:ExtractorLink?=null;var final:ExtractorLink?=null
                suspend fun sample(label:String, chosen:ExtractorLink?, limitMs:Long, baseline:Boolean) {
                    val id=-21021000-ownedIds.size;ownedIds.add(id)
                    val ep=buildResultEpisode(page.name,episode=1,season=1,data=episode.data,apiName="NexoStream",id=id,index=0,tvType=page.type,parentId=id)
                    if(chosen!=null)RepoLinkGenerator.cache["NexoStream" to id]=Cache(mutableSetOf(chosen),mutableSetOf(),saturated=true)
                    val generator=RepoLinkGenerator(listOf(ep),page)
                    ActivityScenario.launch(LitePlayerProbeActivity::class.java).use { scenario ->
                        lateinit var fragment:GeneratorPlayer;lateinit var vm:PlayerGeneratorViewModel
                        scenario.onActivity { activity ->
                            fragment=GeneratorPlayer().apply { arguments=GeneratorPlayer.newInstance(generator,0) }
                            activity.supportFragmentManager.beginTransaction().add(android.R.id.content,fragment,"benchmark").commitNow()
                            vm=ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
                        }
                        val selectedField=GeneratorPlayer::class.java.getDeclaredField("currentSelectedLink").apply { isAccessible=true }
                        val metricsField=GeneratorPlayer::class.java.getDeclaredField("liteMetrics").apply { isAccessible=true }
                        val identity=GeneratorPlayer::class.java.getDeclaredMethod("linkIdentity",Pair::class.java).apply { isAccessible=true }
                        val started=SystemClock.elapsedRealtime();var frameAt:Long?=null;var terminalAt:Long?=null
                        var lastSelected:ExtractorLink?=null;val seen=mutableSetOf<ExtractorLink>()
                        while(SystemClock.elapsedRealtime()-started<limitMs) {
                            if(scenario.state==Lifecycle.State.DESTROYED) {
                                record(mapOf("run" to label,"stage" to "native_closed","elapsed_ms" to SystemClock.elapsedRealtime()-started,"failed" to vm.state.erroredLinks.size))
                                break
                            }
                            val elapsed=SystemClock.elapsedRealtime()-started
                            var done=false
                            scenario.onActivity {
                                if(vm.state.loading !is Resource.Loading && terminalAt==null)terminalAt=elapsed
                                vm.state.links.mapNotNull { it.first }.forEach { link -> if(seen.add(link)) {
                                    record(mapOf("run" to label,"stage" to "link","elapsed_ms" to elapsed,"name" to link.name,"quality" to link.quality,"format" to link.type.name,"host" to android.net.Uri.parse(link.url).host))
                                } }
                                @Suppress("UNCHECKED_CAST") val selected=selectedField.get(fragment) as? VideoLink
                                val link=selected?.first
                                if(link!=null && lastSelected!==link) {
                                    lastSelected=link
                                    if(baseline && initial==null)initial=link
                                    record(mapOf("run" to label,"stage" to "selected","elapsed_ms" to elapsed,"name" to link.name,"quality" to link.quality,"host" to android.net.Uri.parse(link.url).host))
                                }
                                val sample=selected?.let { (metricsField.get(fragment) as LitePlaybackMetrics).snapshot(identity.invoke(fragment,it) as String) }
                                val native=fragment.playerView?.player
                                if(sample?.startupMs!=null && frameAt==null)frameAt=elapsed
                                record(mapOf("run" to label,"stage" to "tick","elapsed_ms" to elapsed,"name" to link?.name,"state" to native?.playbackState,
                                    "play_when_ready" to native?.playWhenReady,"playing" to native?.isPlaying,"position_ms" to native?.currentPosition,"buffered_ms" to native?.bufferedPosition,
                                    "total_buffer_ms" to native?.totalBufferedDuration,"loading" to native?.isLoading,"suppression" to native?.playbackSuppressionReason,
                                    "first_frame_ms" to sample?.startupMs,"extraction_done_ms" to terminalAt,"links" to vm.state.links.size,"failed" to vm.state.erroredLinks.size))
                                done=if(baseline)terminalAt!=null && frameAt!=null && elapsed>maxOf(terminalAt!!,frameAt!!)+2000 && native?.isPlaying==true && sample?.startupMs!=null else frameAt!=null && native?.isPlaying==true && elapsed>frameAt!!+1000
                                if(baseline) { final=link;links.clear();links.addAll(vm.state.links.mapNotNull { it.first }) }
                            }
                            if(done)break
                            delay(250)
                        }
                        record(mapOf("run" to label,"stage" to "summary","elapsed_ms" to SystemClock.elapsedRealtime()-started,"first_frame_at_ms" to frameAt,"extraction_done_ms" to terminalAt,"selected" to lastSelected?.name,"failed" to vm.state.erroredLinks.size))
                    }
                }
                sample("baseline",null,140000,true)
                val candidates=(listOfNotNull(initial,final)+links.filter { parseLiteLinkName(it.name).audioCode=="LAT" }.sortedByDescending { it.quality })
                    .distinctBy { listOf(it.url,it.name,it.quality) }.take(3)
                check(candidates.isNotEmpty()) { "No extracted links to measure" }
                for((index,link)in candidates.withIndex()) {
                    sample("source-$index-pass-1",link,45000,false)
                    if(index<2)sample("source-$index-pass-2",link,45000,false)
                }
            }
        } finally {
            for(id in ownedIds) {
                RepoLinkGenerator.cache.remove("NexoStream" to id);DataStoreHelper.removeLastWatched(id)
                removeKey("${DataStoreHelper.currentAccount}/$VIDEO_POS_DUR",id.toString())
            }
            startPaused.set(previousPaused)
            val edit=prefs.edit();if(previous is Int)edit.putInt(key,previous)else edit.remove(key);edit.commit();context.updateTv()
        }
    }
}
