package com.lagradost.cloudstream3

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.tv.NexoTvTestEnvironment
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LiteReloadLinksMenuTest {
    private class Provider(
        private val server: LiteRealPlayerSwitchTest.MediaServer,
        private val series: Boolean,
    ) : MainAPI() {
        override var name = "NexoStream"
        override var mainUrl = "https://reload-fixture-${System.nanoTime()}.invalid"
        override val hasDownloadSupport = false
        val calls = AtomicInteger()
        val cancelled = AtomicBoolean()
        val finishReload = CompletableDeferred<Unit>()

        override suspend fun load(url: String): LoadResponse = if (series) {
            val episode = newEpisode("reload", initializer = {
                episode = 1
                season = 1
                name = "Reload fixture episode"
            }, fix = false)
            newTvSeriesLoadResponse("Reload fixture", url, TvType.TvSeries, listOf(episode))
        } else {
            newMovieLoadResponse("Reload fixture", url, TvType.Movie, "reload")
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val call = calls.incrementAndGet()
            if (series && call == 1) {
                try {
                    finishReload.await()
                } finally {
                    cancelled.set(!currentCoroutineContext().isActive)
                }
                return false
            }
            if (call == 2) {
                if (series) return false
                finishReload.await()
            }
            val source = if (call == 1) "old" else "fresh"
            callback(newExtractorLink(name, "[Fixture][LAT] $source", server.url(source), ExtractorLinkType.VIDEO) {
                quality = 720
            })
            return true
        }
    }

    private class Fixture(
        val provider: Provider,
        val server: LiteRealPlayerSwitchTest.MediaServer,
        private val env: NexoTvTestEnvironment,
        private val scenario: ActivityScenario<MainActivity>,
        private val tv: Boolean,
    ) {
        private val context = env.context
        private val loadingLabel = context.getString(R.string.loading)

        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(75)
            assertTrue(message, condition())
        }

        private fun isVisible(label: String): Boolean {
            val automation = env.instrumentation.uiAutomation
            if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
            return LiteUiAutomation.nodes(automation.rootInActiveWindow).any {
                it.isVisibleToUser && (it.text?.toString().equals(label, true) ||
                    it.contentDescription?.toString().equals(label, true))
            }
        }

        fun openDetails() {
            env.await(scenario, "Main activity ready") { it.findViewById<View>(R.id.nav_host_fragment) != null }
            env.register()
            scenario.onActivity { it.loadResult(provider.mainUrl + "/title", provider.name, "Reload fixture") }
            await("Details did not load") { isVisible("Reload fixture") }
        }

        fun backToDetails() {
            env.key(KeyEvent.KEYCODE_BACK)
            await("Details did not return") { isVisible("Reload fixture") }
        }

        fun play() {
            if (tv) scenario.onActivity { it.findViewById<View>(R.id.nexo_tv_result_play).performClick() }
            else {
                val resume = context.getString(R.string.continue_watching)
                LiteUiAutomation.clickLabel(if (isVisible(resume)) resume else context.getString(R.string.lite_play))
            }
        }

        fun reload(episode: Boolean = false) {
            if (tv) {
                val id = if (episode) R.id.nexo_tv_episode_title else R.id.nexo_tv_result_play
                env.await(scenario, "Context menu target did not appear") { it.findViewById<View>(id)?.isShown == true }
                scenario.onActivity {
                    var target = it.findViewById<View>(id)
                    while (!target.isLongClickable) target = target.parent as View
                    assertTrue(target.performLongClick())
                }
            } else {
                if (!episode) LiteUiAutomation.clickLabel(context.getString(R.string.lite_more_actions))
                LiteUiAutomation.clickLabel(context.getString(R.string.lite_options))
            }
            LiteUiAutomation.clickLabel(context.getString(R.string.episode_action_reload_links))
        }

        fun awaitLoading() = await("Reload in progress is not visible") { isVisible(loadingLabel) }
        fun awaitCompletion() = await("Reload progress did not close") { !isVisible(loadingLabel) }
        fun cancel() = LiteUiAutomation.clickLabel(context.getString(R.string.cancel))

        fun isCached(path: String) = RepoLinkGenerator.cache.any { (key, entry) ->
            key.first == provider.name && synchronized(entry) {
                entry.saturated && entry.linkCache.singleOrNull()?.url == server.url(path)
            }
        }
    }

    private fun fixture(episode: Boolean = false, run: (Fixture) -> Unit) {
        val tv = InstrumentationRegistry.getArguments().getString("nexo_tv_layout") == "1"
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val provider = Provider(server, episode)
            NexoTvTestEnvironment(provider, if (tv) 1 else 0).use { env ->
                try {
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        val fixture = Fixture(provider, server, env, scenario, tv)
                        fixture.openDetails()
                        run(fixture)
                    }
                } finally {
                    provider.finishReload.complete(Unit)
                    RepoLinkGenerator.cache.keys.removeAll { it.first == provider.name }
                }
            }
        }
    }

    @Test fun movieReloadReplacesFreshCacheAndPlayUsesItWithoutAnotherExtraction() = fixture { f ->
        f.play()
        f.await("The first link was not requested") { "/old" in f.server.requests }
        f.await("The initial cache did not complete") { f.isCached("old") }
        f.backToDetails()
        f.reload()
        f.await("The manual action did not call the provider") { f.provider.calls.get() == 2 }
        f.awaitLoading()
        assertTrue("/fresh" !in f.server.requests)
        f.provider.finishReload.complete(Unit)
        f.await("The fresh link did not replace the old cached link") { f.isCached("fresh") }
        f.awaitCompletion()
        f.play()
        f.await("Play did not use the refreshed link") { "/fresh" in f.server.requests }
        assertEquals(2, f.provider.calls.get())
    }

    @Test fun episodeReloadCanBeCancelledAndRetriedAfterAnEmptyResult() = fixture(episode = true) { f ->
        f.reload(episode = true)
        f.await("The episode reload did not call the provider") { f.provider.calls.get() == 1 }
        f.awaitLoading()
        f.cancel()
        f.await("Cancelling did not stop the extraction") { f.provider.cancelled.get() }
        f.awaitCompletion()
        f.reload(episode = true)
        f.await("The first retry did not call the provider") { f.provider.calls.get() == 2 }
        f.awaitCompletion()
        assertTrue(!f.isCached("fresh") && f.server.requests.isEmpty())
        f.reload(episode = true)
        f.await("The second retry did not fill the cache") { f.isCached("fresh") }
        f.awaitCompletion()
        assertEquals(3, f.provider.calls.get())
        assertTrue(f.server.requests.isEmpty())
    }
}
