package com.lagradost.cloudstream3

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.tv.NexoTvTestEnvironment
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiteMobileDownloadTest {
    private class Provider(private val server: LiteRealPlayerSwitchTest.MediaServer) : MainAPI() {
        override var name = "NexoStream"
        override var mainUrl = "https://download-fixture-${System.nanoTime()}.invalid"

        override suspend fun load(url: String): LoadResponse = if (url.endsWith("/movie")) {
            newMovieLoadResponse("Download fixture movie", url, TvType.Movie, "movie")
        } else {
            newTvSeriesLoadResponse("Download fixture series", url, TvType.TvSeries,
                listOf(newEpisode(url.substringAfterLast('/'), initializer = {
                    episode = 1
                    season = 1
                    name = "Download fixture episode"
                }, fix = false)))
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val mirrors = when (data) {
                "series1" -> listOf("ENG" to 1080, "LAT" to 720, "LAT" to 1080)
                "series2" -> listOf("ENG" to 1080, "CAST" to 720)
                else -> listOf("ENG" to 1080, "LAT" to 720)
            }
            for ((audio, height) in mirrors) {
                callback(newExtractorLink(name, "[Fixture][$audio] $audio-$height",
                    server.url("$data-$audio-$height"), ExtractorLinkType.VIDEO) { quality = height })
            }
            return true
        }
    }

    @Test fun automaticAndManualDownloadUseTheExpectedServers() {
        LiteRealPlayerSwitchTest.MediaServer().use { server ->
            val provider = Provider(server)
            NexoTvTestEnvironment(provider, layout = 0).use { env ->
                val context = env.context
                val automation = env.instrumentation.uiAutomation
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val pathKey = context.getString(R.string.download_path_key)
                val previous = prefs.getString(pathKey, null)
                val folder = File(context.getExternalFilesDir(null), "download-fixture-${System.nanoTime()}")
                    .apply { mkdirs() }
                prefs.edit().putString(pathKey, folder.absolutePath).commit()
                fun nodes(): List<AccessibilityNodeInfo> {
                    if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
                    return LiteUiAutomation.nodes(automation.rootInActiveWindow).filter { it.isVisibleToUser }
                }
                fun await(label: String, find: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    while (!find() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                    if (!find()) {
                        automation.takeScreenshot()?.let { screenshot ->
                            File(context.filesDir, "nexo-download-fixture.png").outputStream().use {
                                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                            screenshot.recycle()
                        }
                        throw AssertionError("$label. Visible: " + nodes().mapNotNull { node ->
                            (node.contentDescription ?: node.text)?.let { "$it (long=${node.isLongClickable})" }
                        })
                    }
                }
                fun downloadButtons() = nodes().filter { node ->
                    node.isLongClickable && node.actionList.any {
                        it.label?.toString() == context.getString(R.string.lite_choose_download_link)
                    }
                }.sortedBy { Rect().also(it::getBoundsInScreen).top }

                fun tap(node: AccessibilityNodeInfo, long: Boolean = false) {
                    val rect = Rect().also(node::getBoundsInScreen)
                    val start = SystemClock.uptimeMillis()
                    fun send(action: Int) {
                        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                            rect.exactCenterX(), rect.exactCenterY(), 0)
                        try { env.instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    send(MotionEvent.ACTION_DOWN)
                    if (long) SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180L)
                    send(MotionEvent.ACTION_UP)
                }
                fun firstRequest(episode: String, expected: String) {
                    val prefix = "/$episode-"
                    await("No request for $episode") { server.requests.any { it.startsWith(prefix) } }
                    assertEquals("/$episode-$expected", server.requests.first { it.startsWith(prefix) })
                }

                fun cancelFixtureDownloads() {
                    DownloadQueueManager.queue.value.filter { entry ->
                        entry.downloadItem?.resultUrl?.startsWith(provider.mainUrl) == true
                    }.forEach { DownloadQueueManager.cancelDownload(it.id) }
                }
                cancelFixtureDownloads()
                try {
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        env.await(scenario, "Main navigation not ready") {
                            it.findViewById<android.view.View>(R.id.nav_host_fragment) != null
                        }
                        env.register()
                        fun details(entry: String) {
                            scenario.onActivity { it.loadResult("${provider.mainUrl}/$entry", provider.name, "Download fixture $entry") }
                        }
                        details("movie")
                        await("Movie download button not visible") { downloadButtons().size == 1 }
                        tap(downloadButtons().single())
                        firstRequest("movie", "LAT-720")
                        env.key(KeyEvent.KEYCODE_BACK)

                        details("series1")
                        await("Episode download button not visible") { downloadButtons().size == 1 }
                        tap(downloadButtons().single())
                        firstRequest("series1", "LAT-1080")
                        env.key(KeyEvent.KEYCODE_BACK)

                        details("series2")
                        await("Episode without LAT button not visible") { downloadButtons().size == 1 }
                        tap(downloadButtons().single())
                        firstRequest("series2", "CAST-720")
                        env.key(KeyEvent.KEYCODE_BACK)

                        details("series3")
                        await("New episode download button not visible") { downloadButtons().size == 1 }
                        tap(downloadButtons().single(), long = true)
                        await("Manual picker missing") { nodes().any { it.text?.toString()?.contains("ENG-1080") == true } }
                        env.key(KeyEvent.KEYCODE_BACK)
                        assertTrue(server.requests.none { it.startsWith("/series3-") })
                        await("Download button not visible after cancel") { downloadButtons().size == 1 }
                        tap(downloadButtons().single(), long = true)
                        await("Manual picker did not open again") {
                            nodes().any { it.text?.toString()?.contains("ENG-1080") == true }
                        }
                        val english = nodes().first { it.text?.toString()?.contains("ENG-1080") == true }
                        tap(english)
                        firstRequest("series3", "ENG-1080")
                        assertTrue(server.requests.none { it.startsWith("/series3-LAT") })
                    }
                } finally {
                    cancelFixtureDownloads()
                    val restore = prefs.edit()
                    if (previous == null) restore.remove(pathKey) else restore.putString(pathKey, previous)
                    restore.commit()
                    folder.deleteRecursively()
                }
            }
        }
    }
}
