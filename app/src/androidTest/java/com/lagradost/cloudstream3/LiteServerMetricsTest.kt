package com.lagradost.cloudstream3

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.lite.LitePlaybackMetrics
import com.lagradost.cloudstream3.ui.lite.LitePlaybackProbeActivity
import com.lagradost.cloudstream3.ui.lite.parseLiteLinkName
import com.lagradost.cloudstream3.utils.ExtractorLink
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** Bounded, serial live-media diagnostic. Persists sanitized measurements, never media URLs. */
class LiteServerMetricsTest {
    @Test fun representativePlayback() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.cacheDir, "nexo-playback-metrics.jsonl").apply { writeText("") }
        fun record(data: Map<String, Any?>) { output.appendText(JSONObject(data).toString() + "\n") }
        val plugin = File(context.cacheDir, "nexo-metrics.cs3")
        check(plugin.exists()) { "Diagnostic plugin missing" }
        val loader = PathClassLoader(plugin.absolutePath, context.classLoader)
        val entry = loader.loadClass("com.lagradost.NexoStreamProviderPlugin").getDeclaredConstructor().newInstance()
        entry.javaClass.getMethod("load", android.content.Context::class.java).invoke(entry, context)
        val provider = loader.loadClass("com.lagradost.NexoStreamProvider").getDeclaredConstructor().newInstance() as MainAPI
        var rendered = 0
        for (case in InstrumentationRegistry.getArguments().getString("cases", "movie,anime,drama").split(',')) {
            val links = CopyOnWriteArrayList<ExtractorLink>()
            val started = SystemClock.elapsedRealtime()
            try {
                val url = when (case) {
                    "movie" -> """{"id":27205,"type":"movie"}"""
                    "anime" -> """{"id":1429,"type":"tv"}"""
                    else -> withTimeout(30_000) { provider.search("La reina de las lágrimas") }
                        .orEmpty().first { it.name.contains("lágrimas", true) || it.name.equals("Queen of Tears", true) }.url
                }
                val detail = withTimeout(45_000) { provider.load(url) } ?: error("No metadata")
                val data = when (detail) {
                    is MovieLoadResponse -> detail.dataUrl
                    is TvSeriesLoadResponse -> detail.episodes.first {
                        it.season == (if (case == "anime") 2 else 1) && it.episode == 1
                    }.data
                    else -> error("Unsupported metadata")
                }
                val metadataMs = SystemClock.elapsedRealtime() - started
                val extractAt = SystemClock.elapsedRealtime()
                var firstLinkMs: Long? = null
                withTimeout(100_000) {
                    provider.loadLinks(data, false, {}, { link ->
                        synchronized(links) {
                            if (firstLinkMs == null) firstLinkMs = SystemClock.elapsedRealtime() - extractAt
                            links.add(link)
                        }
                    })
                }
                record(mapOf("case" to case, "stage" to "extraction", "title" to detail.name,
                    "metadata_ms" to metadataMs, "first_link_ms" to firstLinkMs,
                    "extraction_ms" to SystemClock.elapsedRealtime() - extractAt, "links" to links.size))
            } catch (error: Exception) {
                record(mapOf("case" to case, "stage" to "extraction_error", "error" to error.javaClass.simpleName,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - started, "partial_links" to links.size))
            }
            val candidates = if (case == "movie") {
                listOf("LAT", "CAST", "ENG").mapNotNull { lang -> links.firstOrNull { parseLiteLinkName(it.name).audioCode == lang } }
            } else if (case == "anime") {
                listOf("SPA", "JPN").mapNotNull { lang -> links.firstOrNull { parseLiteLinkName(it.name).audioCode == lang } }
            } else links.take(1)
            record(mapOf("case" to case, "stage" to "sample", "audio_available" to
                links.map { parseLiteLinkName(it.name).audioCode ?: "UNK" }.distinct(), "sample_count" to candidates.size))
            for ((index, link) in candidates.withIndex()) {
                val info = parseLiteLinkName(link.name)
                val headers = link.headers.toMutableMap().apply {
                    if (link.referer.isNotBlank() && keys.none { it.equals("referer", true) }) put("Referer", link.referer)
                }
                val host = runCatching { URI(link.url).host }.getOrNull().orEmpty()
                val accessAt = SystemClock.elapsedRealtime()
                var accessStatus: Int? = null
                var contentType: String? = null
                var firstBytesMs: Long? = null
                try {
                    val request = Request.Builder().url(link.url).apply { headers.forEach { (k,v) -> header(k,v) } }.build()
                    app.baseClient.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
                        .newCall(request).execute().use { response ->
                            accessStatus = response.code
                            contentType = response.header("Content-Type")
                            response.body?.byteStream()?.use { it.read(ByteArray(1024)) }
                            firstBytesMs = SystemClock.elapsedRealtime() - accessAt
                        }
                } catch (_: Exception) { }
                val metrics = LitePlaybackMetrics { SystemClock.elapsedRealtime() }
                var position = 0L
                var elapsed = 0L
                ActivityScenario.launch(LitePlaybackProbeActivity::class.java).use { scenario ->
                    var player: ExoPlayer? = null
                    val selectedAt = SystemClock.elapsedRealtime()
                    scenario.onActivity { activity ->
                        metrics.select("probe", host, link.type.name, selectedAt)
                        val source = OkHttpDataSource.Factory(app.baseClient).setDefaultRequestProperties(headers)
                        player = ExoPlayer.Builder(activity).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
                        activity.video.player = player
                        metrics.attach(player)
                        val item = MediaItem.Builder().setUri(link.url).apply {
                            if (link.type.name == "M3U8") setMimeType(MimeTypes.APPLICATION_M3U8)
                            if (link.type.name == "DASH") setMimeType(MimeTypes.APPLICATION_MPD)
                        }.build()
                        player!!.setMediaItem(item)
                        player!!.prepare()
                        player!!.playWhenReady = true
                    }
                    var firstFrameAt: Long? = null
                    while (SystemClock.elapsedRealtime() - selectedAt < 20_000) {
                        var ready = false
                        var errors = 0
                        scenario.onActivity {
                            ready = metrics.snapshot("probe")?.startupMs != null
                            errors = metrics.snapshot("probe")?.errors ?: 0
                        }
                        if (ready && firstFrameAt == null) firstFrameAt = SystemClock.elapsedRealtime()
                        if (errors > 0 || firstFrameAt?.let { SystemClock.elapsedRealtime() - it >= 5_000 } == true) break
                        Thread.sleep(200)
                    }
                    scenario.onActivity {
                        position = player?.currentPosition ?: 0L
                        elapsed = SystemClock.elapsedRealtime() - selectedAt
                        val sample = metrics.snapshot("probe")
                        if (sample?.startupMs != null && position > 0) rendered++
                        record(mapOf("case" to case, "sample" to index, "source" to info.source, "audio" to info.audioCode,
                            "server" to info.server, "host" to host, "format" to link.type.name, "quality" to link.quality,
                            "http_status" to accessStatus, "content_type" to contentType, "first_bytes_ms" to firstBytesMs,
                            "startup_ms" to sample?.startupMs, "buffering_ms" to sample?.bufferingMs,
                            "errors" to sample?.errors, "observed_ms" to elapsed, "position_ms" to position))
                        metrics.close()
                        it.video.player = null
                        player?.release()
                    }
                }
            }
        }
        record(mapOf("stage" to "finished", "rendered_samples" to rendered, "device" to android.os.Build.MODEL))
        assertTrue("No representative sample rendered; inspect sanitized diagnostic", rendered > 0)
    }
}
