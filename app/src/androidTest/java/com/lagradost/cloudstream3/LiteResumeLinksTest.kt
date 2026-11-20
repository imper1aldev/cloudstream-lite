package com.lagradost.cloudstream3

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.lite.LitePlayerProbeActivity
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.tv.NexoTvTestEnvironment
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LiteResumeLinksTest {
    private class Provider(
        private val server: LiteRealPlayerSwitchTest.MediaServer,
        private val paths: List<String>,
    ) : MainAPI() {
        override var name = "NexoStream"
        override var mainUrl = "https://resume-fixture.invalid"
        val extractions = AtomicInteger()

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val path = paths[extractions.getAndIncrement().coerceAtMost(paths.lastIndex)]
            callback(newExtractorLink(name, "[Fixture][LAT] Server", server.url(path), ExtractorLinkType.VIDEO) {
                quality = 1080
            })
            return true
        }
    }

    private class Session(generator: RepoLinkGenerator) : AutoCloseable {
        private val scenario = ActivityScenario.launch(LitePlayerProbeActivity::class.java)
        private lateinit var fragment: GeneratorPlayer

        init {
            scenario.onActivity { activity ->
                fragment = GeneratorPlayer().apply { arguments = GeneratorPlayer.newInstance(generator, 0) }
                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, fragment, "resume-fixture").commitNow()
            }
        }

        fun withPlayer(action: (Player) -> Unit) {
            scenario.onActivity { fragment.playerView?.player?.let(action) }
        }

        fun awaitPlayback(path: String, minimumPosition: Long = 0) {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                withPlayer {
                    ready = it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.path == "/$path" &&
                        it.currentPosition >= minimumPosition
                }
                if (ready) return
                SystemClock.sleep(100)
            }
            throw AssertionError("Playback did not start at /$path from $minimumPosition ms")
        }

        override fun close() = scenario.close()
    }

    private fun fixture(vararg paths: String, test: (Provider, RepoLinkGenerator, Int) -> Unit) {
        val layout = InstrumentationRegistry.getArguments().getString("nexo_tv_layout")?.toInt() ?: 0
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val provider = Provider(server, paths.toList())
            NexoTvTestEnvironment(provider, layout).use { env ->
                val setting = AppSettings(env.context).player.startPaused
                val previous = setting.get()
                setting.set(false)
                val id = -24912003
                val episode = buildResultEpisode("Resume fixture", episode = 1, season = null,
                    data = "fixture", apiName = provider.name, id = id, index = 0, tvType = TvType.Movie, parentId = id)
                val detail = runBlocking { provider.newMovieLoadResponse("Resume fixture", provider.mainUrl, TvType.Movie, "fixture") }
                try {
                    test(provider, RepoLinkGenerator(listOf(episode), detail), id)
                } finally {
                    setting.set(previous)
                    RepoLinkGenerator.cache.remove(provider.name to id)
                }
            }
        }
    }

    @Test fun recentReopenUsesCacheAndAgedReopenResumesFromSavedPosition() = fixture("first", "fresh") { provider, generator, id ->
        Session(generator).use { it.awaitPlayback("first", 250) }
        Session(generator).use { it.awaitPlayback("first", 250) }
        assertEquals(1, provider.extractions.get())
        DataStoreHelper.setViewPos(id, 5000, 60_000)
        checkNotNull(RepoLinkGenerator.cache[provider.name to id]).lastCachedTimestamp -= 1201

        Session(generator).use { it.awaitPlayback("fresh", 4700) }
        assertEquals(2, provider.extractions.get())
    }

    @Test fun playInTheSameSessionKeepsItsLinkEvenWhenCacheAges() = fixture("first", "fresh") { provider, generator, id ->
        Session(generator).use { session ->
            session.awaitPlayback("first", 250)
            session.withPlayer { it.seekTo(5000); it.pause() }
            SystemClock.sleep(500)
            checkNotNull(RepoLinkGenerator.cache[provider.name to id]).lastCachedTimestamp -= 1201
            session.withPlayer { it.play() }
            session.awaitPlayback("first", 4700)
            assertEquals(1, provider.extractions.get())
        }
    }

    @Test fun unresponsiveServerDoesNotTriggerAnotherExtraction() = fixture("wait", "unexpected") { provider, generator, _ ->
        val started = SystemClock.elapsedRealtime()
        val nativeErrorAt = java.util.concurrent.atomic.AtomicLong()
        Session(generator).use { session ->
            val attachDeadline = started + 10_000
            var attached = false
            while (!attached && SystemClock.elapsedRealtime() < attachDeadline) {
                session.withPlayer { player ->
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            nativeErrorAt.compareAndSet(0, SystemClock.elapsedRealtime() - started)
                        }
                    })
                    attached = true
                }
                if (!attached) SystemClock.sleep(25)
            }
            org.junit.Assert.assertTrue("Media3 did not open the test stream", attached)
            val observationMs = InstrumentationRegistry.getArguments()
                .getString("silentObservationMs")?.toLong() ?: 65_000L
            val observationDeadline = started + observationMs
            while (nativeErrorAt.get() == 0L && SystemClock.elapsedRealtime() < observationDeadline) {
                SystemClock.sleep(100)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            android.util.Log.i("NexoRealRenewal",
                "unresponsive stream: native_error_ms=${nativeErrorAt.get()} observed_ms=$elapsed extractions=${provider.extractions.get()}")
            assertEquals(1, provider.extractions.get())
        }
    }


    @Test fun incompleteCacheEmitsSavedLinkThenContinuesExtraction() = fixture("first", "fresh") { provider, generator, id ->
        fun collectLinks(): List<String> = runBlocking {
            val paths = mutableListOf<String>()
            generator.generateLinks(false, setOf(ExtractorLinkType.VIDEO), { (link, _) ->
                paths.add(checkNotNull(link).url.substringAfterLast('/'))
            }, {}, 0, false)
            paths
        }

        assertEquals(listOf("first"), collectLinks())
        val partial = checkNotNull(RepoLinkGenerator.cache[provider.name to id])
        synchronized(partial) { partial.saturated = false }
        assertEquals(listOf("first", "fresh"), collectLinks())
        assertEquals(listOf("first", "fresh"), collectLinks())
        assertEquals(2, provider.extractions.get())
    }
}
