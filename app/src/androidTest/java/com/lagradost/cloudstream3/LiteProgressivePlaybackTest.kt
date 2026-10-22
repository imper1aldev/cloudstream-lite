package com.lagradost.cloudstream3

import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.VIDEO_POS_DUR
import com.lagradost.cloudstream3.ui.lite.LitePlayerProbeActivity
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class LiteProgressivePlaybackTest {
    internal class ControlledGenerator : VideoGenerator<String>(listOf("one", "two")) {
        override val hasCache = false
        override val canSkipLoading = true
        override fun getId(index: Int) = -21022000-index
        val callbacks = CopyOnWriteArrayList<(VideoLink) -> Unit>()
        val endings = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        override suspend fun generateLinks(clearCache: Boolean, sourceTypes: Set<ExtractorLinkType>,
            callback: (VideoLink) -> Unit, subtitleCallback: (SubtitleData) -> Unit, offset: Int, isCasting: Boolean): Boolean {
            val ending = CompletableDeferred<Unit>()
            endings.add(ending)
            callbacks.add(callback)
            ending.await()
            return true
        }
    }
    internal data class Load(val name: String?, val sameEpisode: Boolean)
    internal class Fixture(layout: Int = 0, realPlayer: Boolean = false) : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.app_layout_key)
        val previous = prefs.all[key]
        val generator = ControlledGenerator()
        val loads = CopyOnWriteArrayList<Load>()
        val playArguments = CopyOnWriteArrayList<List<Any?>>()
        var playing = false
        val fakePlayer = Proxy.newProxyInstance(IPlayer::class.java.classLoader, arrayOf(IPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "loadPlayer" -> { loads.add(Load((args!![2] as? ExtractorLink)?.name, args[1] as Boolean)); playArguments.add(args.toList()); null }
                "getIsPlaying" -> playing
                "getPosition" -> 5000L
                "getDuration" -> 100000L
                "getPlaybackSpeed" -> 1f
                "getSubtitleOffset" -> 0L
                "getVideoTracks" -> CurrentTracks(null, null, emptyList(), emptyList(), emptyList(), emptyList())
                "getSubtitleCues" -> emptyList<SubtitleCue>()
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as IPlayer
        val scenario: ActivityScenario<LitePlayerProbeActivity>
        lateinit var fragment: GeneratorPlayer
        lateinit var vm: PlayerGeneratorViewModel
        private fun clearOwnedProgress() {
            repeat(2) { index ->
                val id=generator.getId(index)
                removeKey("${DataStoreHelper.currentAccount}/$VIDEO_POS_DUR",id.toString())
                DataStoreHelper.removeLastWatched(id)
            }
        }
        init {
            clearOwnedProgress()
            prefs.edit().putInt(key, layout).commit()
            context.updateTv()
            LitePlayerProbeActivity.instrumentedPlayer = if (realPlayer) null else fakePlayer
            scenario = ActivityScenario.launch(LitePlayerProbeActivity::class.java)
            scenario.onActivity { activity ->
                fragment = GeneratorPlayer().apply {
                    arguments = GeneratorPlayer.newInstance(generator, 0)
                    if (!realPlayer) player = fakePlayer
                }
                activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment, "probe").commitNow()
                vm = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
            }
            await { generator.callbacks.isNotEmpty() }
            setProvider()
        }
        fun setProvider(apiName: String = "NexoStream") {
            val response = runBlocking { object : MainAPI() { override var name = apiName }
                .newMovieLoadResponse("Fixture", "https://fixture.invalid", TvType.Movie, "data") }
            scenario.onActivity { vm.modifyState { copy(generatorState = generatorState?.copy(response = response)) } }
            idle()
        }
        fun idle() { instrumentation.waitForIdleSync() }
        fun emit(lang: String, index: Int = 0, quality: Int = 1080, server: String = "Server", url: String? = null) {
            val link = runBlocking { newExtractorLink("Fixture", "[Fixture][$lang] $server", url ?: "https://fixture.invalid/$lang/$quality/$server", ExtractorLinkType.VIDEO) { this.quality = quality } }
            generator.callbacks[index](link to null)
            idle()
        }
        fun finish(index: Int = 0) { generator.endings[index].complete(Unit); idle(); Thread.sleep(100); idle() }
        fun manual() { scenario.onActivity { call("startPlayer") }; idle() }
        fun call(name: String, vararg args: Any?): Any? {
            val method = GeneratorPlayer::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            method.isAccessible = true
            return method.invoke(fragment, *args)
        }
        fun await(timeoutMs: Long = 3000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("Timed out waiting for controlled generator", condition())
        }
        override fun close() {
            scenario.close()
            clearOwnedProgress()
            LitePlayerProbeActivity.instrumentedPlayer = null
            val editor = prefs.edit()
            if (previous is Int) editor.putInt(key, previous) else editor.remove(key)
            editor.commit()
            context.updateTv()
        }
    }
    @Test fun seekNearEndKeepsLateServersAndCompletesCurrentExtraction() {
        listOf(0, 1).forEach { episode -> Fixture().use { f ->
            var extraction = 0
            if (episode == 1) {
                f.scenario.onActivity { f.vm.loadThisEpisode(1) }
                f.await { f.generator.callbacks.size == 2 }
                f.setProvider()
                extraction = 1
            }
            f.emit("LAT", extraction)
            f.scenario.onActivity { f.fragment.playerPositionChanged(85_000L, 100_000L) }
            f.emit("ENG", extraction)
            f.finish(extraction)
            f.await { f.vm.state.loading !is Resource.Loading }
            assertEquals("Late server lost after seek, episode $episode", 2, f.vm.state.links.size)
            assertEquals(1, f.loads.size)
        } }
    }
    @Test fun completionUpgradesOnceAndPreservesEpisodePositionAndPlayState() = Fixture().use { f ->
        f.emit("LAT", quality = 720, server = "Early")
        f.emit("LAT", quality = 1080, server = "Better")
        assertEquals(1, f.loads.size)
        f.finish()
        assertEquals(listOf(Load("[Fixture][LAT] Early", false), Load("[Fixture][LAT] Better", true)), f.loads.toList())
        assertNull("Native same-episode position must be retained", f.playArguments.last()[4])
        assertEquals("Paused playback must stay paused", false, f.playArguments.last()[7])
        f.emit("LAT", quality = 2160, server = "Too late")
        assertEquals("Final upgrade must only happen once", 2, f.loads.size)
    }
    @Test fun completionUpgradeKeepsPlayingWhenPlaybackWasActive() = Fixture().use { f ->
        f.emit("LAT", quality = 720); f.playing = true
        f.emit("LAT", quality = 1080); f.finish()
        assertEquals(2, f.loads.size)
        assertEquals(true, f.playArguments.last()[7])
    }
    @Test fun completionDoesNotReloadForAlphabeticalTie() = Fixture().use { f ->
        f.emit("LAT", server = "Zeta"); f.emit("LAT", server = "Alpha"); f.finish()
        assertEquals(1, f.loads.size)
    }
    @Test fun explicitSelectionSuppressesTheFinalUpgrade() = Fixture().use { f ->
        f.emit("CAST"); f.manual(); f.emit("LAT", quality = 2160); f.finish()
        assertEquals(listOf(Load("[Fixture][CAST] Server", false)), f.loads.toList())
    }
    @Test fun startsLatWhileGeneratorIsStillPendingExactlyOnce() = Fixture().use { f ->
        f.emit("CAST")
        assertTrue(f.loads.isEmpty())
        f.emit("LAT")
        assertEquals(listOf(Load("[Fixture][LAT] Server", false)), f.loads.toList())
        assertTrue(f.vm.state.loading is Resource.Loading)
        f.emit("ENG")
        f.finish()
        assertEquals(1, f.loads.size)
        assertEquals(3, f.vm.state.links.size)
    }
    @Test fun completionWithoutLatAutomaticallyFallsBack() = Fixture().use { f ->
        f.emit("ENG"); f.emit("CAST")
        assertTrue(f.loads.isEmpty())
        f.finish()
        assertEquals(listOf(Load("[Fixture][CAST] Server", false)), f.loads.toList())
    }
    @Test fun manualSelectionAndSkipDoNotPretendExtractionIsComplete() = Fixture().use { f ->
        f.emit("CAST")
        f.scenario.onActivity { f.fragment.requireView().findViewById<android.view.View>(R.id.overlay_loading_skip_button).performClick() }
        assertTrue(f.vm.state.loading is Resource.Loading)
        f.emit("LAT"); f.finish()
        assertEquals(listOf(Load("[Fixture][CAST] Server", false)), f.loads.toList())
    }
    @Test fun partialExtractionFailureKeepsUsableFallback() = Fixture().use { f ->
        f.emit("CAST")
        f.generator.endings[0].completeExceptionally(java.io.IOException("controlled"))
        f.await { f.loads.isNotEmpty() }
        assertEquals("[Fixture][CAST] Server", f.loads.single().name)
    }
    @Test fun errorRecoveryCanChooseAnEarlierRankAndPreservesEpisode() = Fixture().use { f ->
        f.emit("CAST"); f.manual(); f.emit("LAT")
        f.scenario.onActivity {
            f.vm.modifyState { addError(links.first { it.first?.name?.contains("CAST") == true }) }
            f.fragment.nextMirror()
        }
        assertEquals(listOf(Load("[Fixture][CAST] Server", false), Load("[Fixture][LAT] Server", true)), f.loads.toList())
    }
    @Test fun completedExtractionAndLateCallbacksNeverStartTwice() = Fixture().use { f ->
        f.emit("CAST"); f.emit("LAT"); f.finish(); f.emit("ENG")
        assertEquals(1, f.loads.size)
    }
    @Test fun previousEpisodeCallbackIsRejected() = Fixture().use { f ->
        f.emit("LAT")
        f.scenario.onActivity { f.fragment.nextEpisode() }
        f.await { f.generator.callbacks.size == 2 }
        f.setProvider()
        f.emit("ENG", 0)
        assertTrue(f.vm.state.links.isEmpty())
        f.emit("CAST", 1); f.finish(1)
        assertEquals("[Fixture][CAST] Server", f.loads.last().name)
    }
    @Test fun queuedLoadingForSameInstanceCannotReleaseStartedPlayer() = Fixture().use { f ->
        f.emit("LAT")
        (f.vm.loadingLinks as androidx.lifecycle.MutableLiveData).postValue(VideoLive(Resource.Loading(), f.vm.state.instance))
        f.idle()
        val active = GeneratorPlayer::class.java.getDeclaredField("isPlayerActive").apply { isAccessible = true }
        assertTrue((active.get(f.fragment) as java.util.concurrent.atomic.AtomicBoolean).get())
        f.emit("ENG"); f.finish()
        assertEquals(1, f.loads.size)
    }
    @Test fun failedLatWaitsForPendingSourcesAndRecoversWithoutRestartingEpisode() = Fixture().use { f ->
        f.emit("LAT")
        f.scenario.onActivity { f.fragment.playerError(java.io.IOException("controlled")) }
        assertEquals(1, f.loads.size)
        f.emit("CAST")
        assertEquals(listOf(Load("[Fixture][LAT] Server", false), Load("[Fixture][CAST] Server", true)), f.loads.toList())
        f.finish()
        assertEquals(2, f.loads.size)
    }
    @Test fun allFailedLinksAreNotAutomaticallyRetriedAtCompletion() = Fixture().use { f ->
        f.emit("CAST"); f.emit("ENG")
        f.scenario.onActivity { f.vm.modifyState { setError(links) } }
        f.finish()
        assertTrue(f.loads.isEmpty())
    }
    @Test fun recreationStartsOncePerPlayerAndRejectsOldExtraction() = Fixture().use { f ->
        f.emit("LAT")
        f.scenario.recreate()
        f.scenario.onActivity { activity ->
            f.fragment = activity.supportFragmentManager.findFragmentByTag("probe") as GeneratorPlayer
            f.vm = ViewModelProvider(f.fragment)[PlayerGeneratorViewModel::class.java]
        }
        f.await { f.generator.callbacks.size == 2 }
        f.setProvider()
        f.emit("ENG", 0)
        assertTrue(f.vm.state.links.isEmpty())
        f.emit("LAT", 1); f.finish(1)
        assertEquals(listOf(Load("[Fixture][LAT] Server", false), Load("[Fixture][LAT] Server", false)), f.loads.toList())
    }
}
