package com.lagradost.cloudstream3

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.tv.NexoTvTestEnvironment
import com.lagradost.cloudstream3.ui.lite.LitePlayerProbeActivity
import com.lagradost.cloudstream3.ui.player.Cache
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiteProviderRenewalProbeTest {
    @Test fun installedProviderReusesRecentCacheAndReplacesAgedOrManuallyReloadedCache() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("realNexoRenewal") == "true")
        val layout = arguments.getString("nexo_tv_layout")?.toInt() ?: 0
        val title = arguments.getString("probeTitle", "True Beauty")
        NexoTvTestEnvironment(layout = layout).use { env ->
            val playback = AppSettings(env.context).player.startPaused
            val wasPaused = playback.get()
            playback.set(false)
            val id = -24_912_006
            val key = "NexoStream" to id
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    val page = runBlocking {
                        val provider = withTimeout(30_000) {
                            var installed = APIHolder.getApiFromNameNull("NexoStream")
                            while (installed == null) {
                                delay(100)
                                installed = APIHolder.getApiFromNameNull("NexoStream")
                            }
                            checkNotNull(installed)
                        }
                        val matches = withTimeout(45_000) { provider.search(title) }.orEmpty()
                        val match = checkNotNull(matches.firstOrNull { it.name.equals(title, true) ||
                            it.name.equals("Belleza verdadera", true) } ?: matches.firstOrNull()) {
                            "Installed provider returned no title for the probe"
                        }
                        withTimeout(45_000) { provider.load(match.url) } as? TvSeriesLoadResponse
                            ?: error("The probe title requires a series")
                    }
                    val episode = page.episodes.first { it.episode == 1 && (it.season ?: 1) == 1 }
                    val item = buildResultEpisode(page.name, episode = 1, season = 1, data = episode.data,
                        apiName = key.first, id = id, index = 0, tvType = page.type, parentId = id)
                    val generator = RepoLinkGenerator(listOf(item), page)
                    fun sample(label: String): Cache {
                        ActivityScenario.launch(LitePlayerProbeActivity::class.java).use { scenario ->
                            lateinit var player: GeneratorPlayer
                            lateinit var model: PlayerGeneratorViewModel
                            val start = SystemClock.elapsedRealtime()
                            scenario.onActivity { activity ->
                                player = GeneratorPlayer().apply { this.arguments = GeneratorPlayer.newInstance(generator, 0) }
                                activity.supportFragmentManager.beginTransaction()
                                    .add(android.R.id.content, player, "provider-renewal").commitNow()
                                model = ViewModelProvider(player)[PlayerGeneratorViewModel::class.java]
                            }
                            var playingAt: Long? = null
                            val deadline = start + 115_000
                            while (SystemClock.elapsedRealtime() < deadline) {
                                var complete = false
                                scenario.onActivity {
                                    if (playingAt == null && player.playerView?.player?.isPlaying == true) {
                                        playingAt = SystemClock.elapsedRealtime() - start
                                    }
                                    complete = model.state.loading !is Resource.Loading
                                }
                                if (complete && playingAt != null) break
                                SystemClock.sleep(200)
                            }
                            val cache = checkNotNull(RepoLinkGenerator.cache[key]) { "$label did not reach the cache" }
                            val links = synchronized(cache) { cache.linkCache.size }
                            Log.i("NexoRealRenewal", "$label links=$links first_playback_ms=$playingAt")
                            assertTrue("$label did not find a playable source", links > 0 && playingAt != null)
                            return cache
                        }
                    }
                    val original = sample("cold")
                    assertSame(original, sample("recent"))
                    original.lastCachedTimestamp -= 1201
                    val aged = sample("expired")
                    assertNotSame(original, aged)
                    val reloadLinks = runBlocking {
                        var count = 0
                        generator.generateLinks(true, LOADTYPE_INAPP, { count++ }, {}, 0, false)
                        count
                    }
                    Log.i("NexoRealRenewal", "manual extraction returned $reloadLinks links")
                    assertTrue("Manual extraction returned no links", reloadLinks > 0)
                    val manual = checkNotNull(RepoLinkGenerator.cache[key])
                    assertNotSame(aged, manual)
                    assertSame(manual, sample("after manual"))
                }
            } finally {
                RepoLinkGenerator.cache.remove(key)
                playback.set(wasPaused)
            }
        }
    }
}
