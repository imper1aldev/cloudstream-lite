package com.lagradost.cloudstream3

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.lite.*
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.VIDEO_POS_DUR
import dalvik.system.PathClassLoader
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real RepoLinkGenerator -> GeneratorPlayer -> CS3IPlayer -> rendered Media3 frames. */
class LiteLivePlaybackTest {
    @Test fun realPipelineAndNoLatFallback() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val layoutKey = context.getString(R.string.app_layout_key)
        val previous = prefs.all[layoutKey]
        val output = File(context.cacheDir, "nexo-live-pipeline.jsonl").apply { writeText("") }
        fun record(values: Map<String,Any?>) { output.appendText(JSONObject(values).toString()+"\n") }
        val plugin = File(context.cacheDir, "nexo-metrics.cs3")
        check(plugin.exists())
        val loader = PathClassLoader(plugin.absolutePath, context.classLoader)
        val entry = loader.loadClass("com.lagradost.NexoStreamProviderPlugin").getDeclaredConstructor().newInstance()
        entry.javaClass.getMethod("load", android.content.Context::class.java).invoke(entry, context)
        val provider = APIHolder.getApiFromNameNull("NexoStream") ?: error("Provider missing")
        prefs.edit().putInt(layoutKey, 0).commit()
        context.updateTv()
        assertTrue(Globals.isLayout(Globals.PHONE))
        LitePlayerProbeActivity.instrumentedPlayer = null
        var rendered = 0
        val ownedIds = mutableListOf<Int>()
        try {
            val cases = InstrumentationRegistry.getArguments().getString("cases", "movie,anime,drama").split(',')
            for ((caseIndex, case) in cases.withIndex()) {
                val url = when(case) {
                    "movie" -> """{"id":27205,"type":"movie"}"""
                    "anime" -> """{"id":1429,"type":"tv"}"""
                    else -> withTimeout(30_000) { provider.search("La reina de las lágrimas") }.orEmpty()
                        .first { it.name.contains("lágrimas",true) || it.name.equals("Queen of Tears",true) }.url
                }
                val detail = withTimeout(45_000) { provider.load(url) } ?: error("Missing detail")
                val data = when(detail) {
                    is MovieLoadResponse -> listOf(detail.dataUrl)
                    is TvSeriesLoadResponse -> detail.episodes.filter {
                        it.season == (if(case == "anime") 2 else 1) && it.episode in 1..2
                    }.sortedBy { it.episode }.map { it.data }
                    else -> error("Unsupported detail")
                }
                val parent = -1700600-caseIndex*10
                val episodes = data.mapIndexed { index, payload ->
                    val id = parent-index
                    ownedIds.add(id)
                    buildResultEpisode(detail.name, episode=index+1, season=if(case=="anime")2 else 1,
                        data=payload, apiName="NexoStream", id=id,index=index,tvType=detail.type,parentId=parent)
                }
                val generator = RepoLinkGenerator(episodes, detail)
                ActivityScenario.launch(LitePlayerProbeActivity::class.java).use { scenario ->
                    lateinit var fragment: GeneratorPlayer
                    lateinit var vm: PlayerGeneratorViewModel
                    scenario.onActivity { activity ->
                        fragment = GeneratorPlayer().apply { arguments=GeneratorPlayer.newInstance(generator,0) }
                        activity.supportFragmentManager.beginTransaction().add(android.R.id.content,fragment,"live-probe").commitNow()
                        vm=ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
                    }
                    for (index in episodes.indices) {
                        val started = SystemClock.elapsedRealtime()
                        var startup: Long? = null
                        var frameWhileLoading = false
                        var audioTrack = false
                        var selectedLanguage: String? = null
                        var position = 0L
                        val selectionField=GeneratorPlayer::class.java.getDeclaredField("currentSelectedLink").apply { isAccessible=true }
                        val metricsField=GeneratorPlayer::class.java.getDeclaredField("liteMetrics").apply { isAccessible=true }
                        val identity=GeneratorPlayer::class.java.getDeclaredMethod("linkIdentity",Pair::class.java).apply { isAccessible=true }
                        while(SystemClock.elapsedRealtime()-started<105_000 && startup==null) {
                            scenario.onActivity {
                                @Suppress("UNCHECKED_CAST")
                                val selected=selectionField.get(fragment) as? VideoLink
                                if(selected!=null) {
                                    val sample=(metricsField.get(fragment) as LitePlaybackMetrics).snapshot(identity.invoke(fragment,selected) as String)
                                    startup=sample?.startupMs
                                    selectedLanguage=parseLiteLinkName(selected.first?.name.orEmpty()).audioCode
                                    frameWhileLoading=startup!=null && vm.state.loading is Resource.Loading
                                    position=fragment.player.getPosition() ?: 0
                                    audioTrack=fragment.player.getVideoTracks().currentAudioTrack!=null
                                }
                            }
                            if(startup==null) delay(200)
                        }
                        record(mapOf("case" to case,"episode" to index+1,"stage" to "frame", "startup_ms" to startup,
                            "frame_while_extracting" to frameWhileLoading,"audio_declared" to selectedLanguage,
                            "audio_track" to audioTrack,"position_ms" to position,"elapsed_ms" to SystemClock.elapsedRealtime()-started))
                        assertNotNull("No real frame for $case episode${index+1}",startup)
                        assertTrue("No audio track selected",audioTrack)
                        rendered++
                        // Leave extraction alive and exercise the actual open server dialog.
                        scenario.onActivity { fragment.showMirrorsDialogue() }
                        delay(1_000)
                        scenario.onActivity {
                            val field=FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog").apply { isAccessible=true }
                            (field.get(fragment) as? android.app.Dialog)?.dismiss()
                        }
                        val terminalDeadline=SystemClock.elapsedRealtime()+100_000
                        while(vm.state.loading is Resource.Loading && SystemClock.elapsedRealtime()<terminalDeadline) delay(200)
                        assertFalse("Extraction never completed",vm.state.loading is Resource.Loading)
                        val cached=RepoLinkGenerator.cache["NexoStream" to episodes[index].id]
                        record(mapOf("case" to case,"episode" to index+1,"stage" to "terminal", "links" to vm.state.links.size,
                            "saturated" to cached?.saturated,"elapsed_ms" to SystemClock.elapsedRealtime()-started))
                        assertTrue("Completed cache not saturated",cached?.saturated==true)
                        if(case=="movie") {
                            val eng=vm.state.links.mapNotNull { it.first }.firstOrNull { parseLiteLinkName(it.name).audioCode=="ENG" }
                            assertNotNull("No real ENG fallback fixture",eng)
                            val fallbackId=parent-5
                            ownedIds.add(fallbackId)
                            RepoLinkGenerator.cache["NexoStream" to fallbackId]=Cache(mutableSetOf(eng!!),mutableSetOf(),saturated=true)
                            val fallback=RepoLinkGenerator(listOf(episodes[0].copy(id=fallbackId)),detail)
                            scenario.onActivity { vm.attachGenerator(fallback,0); vm.loadLinks() }
                            var fallbackFrame=false
                            val fallbackDeadline=SystemClock.elapsedRealtime()+30_000
                            while(!fallbackFrame && SystemClock.elapsedRealtime()<fallbackDeadline) {
                                scenario.onActivity {
                                    @Suppress("UNCHECKED_CAST")
                                    val selected=selectionField.get(fragment) as? VideoLink
                                    if(selected!=null && parseLiteLinkName(selected.first?.name.orEmpty()).audioCode=="ENG") {
                                        fallbackFrame=(metricsField.get(fragment) as LitePlaybackMetrics).snapshot(identity.invoke(fragment,selected) as String)?.startupMs!=null
                                    }
                                }
                                if(!fallbackFrame)delay(200)
                            }
                            record(mapOf("case" to "movie_no_lat", "stage" to "frame", "rendered" to fallbackFrame,"fixture" to "completed ENG-only real-media cache"))
                            assertTrue("No real frame after noLAT fallback",fallbackFrame)
                            rendered++
                        }
                        if(index<episodes.lastIndex) scenario.onActivity { fragment.nextEpisode() }
                    }
                }
                DataStoreHelper.removeLastWatched(parent)
            }
            record(mapOf("stage" to "finished","rendered" to rendered,"phone_layout" to Globals.isLayout(Globals.PHONE)))
        } finally {
            ownedIds.forEach { id ->
                RepoLinkGenerator.cache.remove("NexoStream" to id)
                removeKey("${DataStoreHelper.currentAccount}/$VIDEO_POS_DUR",id.toString())
                DataStoreHelper.removeLastWatched(id)
            }
            val editor=prefs.edit()
            if(previous is Int)editor.putInt(layoutKey,previous)else editor.remove(layoutKey)
            editor.commit()
            context.updateTv()
        }
    }
}
