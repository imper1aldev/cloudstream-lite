package com.lagradost.cloudstream3

import androidx.media3.common.Player
import com.lagradost.cloudstream4.AppSettings
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Local PCM media exercises the actual CS3IPlayer/Media3 lifecycle, not an IPlayer proxy. */
class LiteRealPlayerSwitchTest {
    private class MediaServer : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val clients = CopyOnWriteArrayList<Socket>()
        val stop = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        private val media = ByteBuffer.allocate(44 + 60 * 16000).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(capacity()-8);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16)
            put("data".toByteArray());putInt(capacity()-44)
        }.array()
        init { thread(isDaemon=true) {
            while(!socket.isClosed) {
                val client=try { socket.accept() } catch(_:Exception) { break }
                clients.add(client)
                thread(isDaemon=true) { try { client.use {
                    val reader=it.getInputStream().bufferedReader()
                    val first=reader.readLine() ?: return@use
                    val path=first.split(' ').getOrNull(1).orEmpty();requests.add(path)
                    var offset=0
                    while(true) { val line=reader.readLine() ?: break; if(line.isEmpty())break
                        if(line.startsWith("Range:",true))offset=Regex("bytes=(\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 0
                    }
                    if(path=="/wait") { stop.await();return@use }
                    val output=it.getOutputStream()
                    if(path=="/fail") { output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());output.flush();return@use }
                    offset=offset.coerceIn(0,media.size-1)
                    val status=if(offset>0)"206 Partial Content" else "200 OK"
                    val range=if(offset>0)"Content-Range: bytes $offset-${media.size-1}/${media.size}\r\n" else ""
                    output.write("HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: ${media.size-offset}\r\nAccept-Ranges: bytes\r\n${range}Connection: close\r\n\r\n".toByteArray())
                    output.write(media,offset,media.size-offset);output.flush()
                } } catch(_:Exception) { } }
            }
        } }
        fun url(path:String)="http://127.0.0.1:${socket.localPort}/$path"
        override fun close() { stop.countDown();socket.close();clients.forEach { runCatching { it.close() } } }
    }
    private fun state(f:LiteProgressivePlaybackTest.Fixture, predicate:(Player)->Boolean):Boolean {
        var result=false
        f.scenario.onActivity { result=f.fragment.playerView?.player?.let(predicate)==true }
        return result
    }
    private fun real(block:(LiteProgressivePlaybackTest.Fixture,MediaServer)->Unit) {
        MediaServer().use { server -> LiteProgressivePlaybackTest.Fixture(realPlayer=true).use { f ->
            val setting=AppSettings(f.context).player.startPaused;val previous=setting.get();setting.set(false)
            try { block(f,server) } finally { setting.set(previous) }
        } }
    }
    @Test fun bufferingUpgradePreservesPlayIntent() = real { f,server ->
        f.emit("LAT",quality=720,url=server.url("wait"))
        f.await(10000) { state(f) { it.playbackState==Player.STATE_BUFFERING && it.playWhenReady } }
        var old:Player?=null;f.scenario.onActivity { old=f.fragment.playerView?.player }
        f.emit("LAT",quality=1080,url=server.url("good"));f.finish()
        f.await(10000) { state(f) { it!==old } }
        assertTrue("Buffering was converted into a permanent pause",state(f) { it.playWhenReady })
        try {
            f.await(10000) { state(f) { it.isPlaying && it.currentPosition>150 } }
        } catch (failure:AssertionError) {
            var diagnostic="no native player"
            f.scenario.onActivity { f.fragment.playerView?.player?.let {
                diagnostic="state=${it.playbackState}, intent=${it.playWhenReady}, playing=${it.isPlaying}, suppression=${it.playbackSuppressionReason}, position=${it.currentPosition}, buffer=${it.bufferedPosition}, error=${it.playerError?.errorCode}, requests=${server.requests}"
            } }
            throw AssertionError(diagnostic,failure)
        }
    }
    @Test fun playingUpgradeContinuesFromSavedPosition() = real { f,server ->
        f.emit("LAT",quality=720,url=server.url("early"))
        f.await(10000) { state(f) { it.isPlaying } }
        f.scenario.onActivity { f.fragment.playerView?.player?.seekTo(5000) }
        f.await(10000) { state(f) { it.isPlaying && it.currentPosition>=5000 } }
        var old:Player?=null;f.scenario.onActivity { old=f.fragment.playerView?.player }
        f.emit("LAT",quality=1080,url=server.url("better"));f.finish()
        f.await(10000) { state(f) { it!==old } }
        assertTrue("Replacing a playing source paused its successor",state(f) { it.playWhenReady })
        f.await(10000) { state(f) { it.isPlaying && it.currentPosition>=4900 } }
    }
    @Test fun deliberatelyPausedUpgradeStaysPaused() = real { f,server ->
        f.emit("LAT",quality=720,url=server.url("early"));f.await(10000) { state(f) { it.isPlaying } }
        f.scenario.onActivity { f.fragment.playerView?.player?.apply { seekTo(5000);pause() } }
        f.await(10000) { state(f) { !it.playWhenReady && it.currentPosition>=4900 } }
        var old:Player?=null;f.scenario.onActivity { old=f.fragment.playerView?.player }
        f.emit("LAT",quality=1080,url=server.url("better"));f.finish()
        f.await(10000) { state(f) { it!==old && it.playbackState==Player.STATE_READY } }
        assertTrue("Explicit pause must survive source replacement",state(f) { !it.playWhenReady && it.currentPosition>=4900 })
    }
    @Test fun nativeHttpFailureFallsThroughToAnotherServer() = real { f,server ->
        f.emit("CAST",url=server.url("good"))
        f.emit("LAT",url=server.url("fail"))
        f.await(12000) { state(f) { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.path=="/good" } }
        assertTrue("Failed source was not recorded",f.vm.state.erroredLinks.isNotEmpty())
        f.finish()
        assertTrue(state(f) { it.playWhenReady })
    }
    @Test fun stalledAutomaticSourceUsesAvailableLatBeforeExtractionFinishes() = real { f,server ->
        f.emit("LAT",quality=2160,url=server.url("wait"))
        f.await(10000) { state(f) { it.playbackState==Player.STATE_BUFFERING && it.playWhenReady } }
        // Video can render its initial image before Media3 has enough data to play.
        f.scenario.onActivity {
            val metrics=f.fragment.javaClass.getDeclaredField("liteMetrics").apply { isAccessible=true }.get(f.fragment)!!
            val observer=metrics.javaClass.getDeclaredField("listener").apply { isAccessible=true }.get(metrics) as androidx.media3.exoplayer.analytics.AnalyticsListener
            observer.onRenderedFirstFrame(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime(
                0,androidx.media3.common.Timeline.EMPTY,0,null,0,androidx.media3.common.Timeline.EMPTY,0,null,0,0),Any(),0)
        }
        f.emit("LAT",quality=720,url=server.url("good"))
        f.await(12000) { state(f) { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.path=="/good" } }
        assertTrue("Source extraction must continue",f.vm.state.loading is com.lagradost.cloudstream3.mvvm.Resource.Loading)
        assertTrue("Slow sources must remain manually available",f.vm.state.erroredLinks.isEmpty())
        f.emit("LAT",quality=1080,url=server.url("better"));f.finish()
        f.await(10000) { state(f) { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.path=="/better" } }
    }
    @Test fun expiredAttemptWaitsForLateAlternativeWithoutClosingPlayer() = real { f,server ->
        f.emit("LAT",url=server.url("wait"))
        f.await(10000) { state(f) { it.playbackState==Player.STATE_BUFFERING } }
        Thread.sleep(8000)
        assertTrue(state(f) { it.currentMediaItem?.localConfiguration?.uri?.path=="/wait" })
        f.emit("CAST",url=server.url("late"))
        f.await(5000) { state(f) { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.path=="/late" } }
    }
    @Test fun pausedStartupAndManualChoiceAreProtected() = real { f,server ->
        f.emit("LAT",quality=720,url=server.url("wait"))
        f.await(10000) { state(f) { it.playbackState==Player.STATE_BUFFERING } }
        f.scenario.onActivity { f.fragment.playerView?.player?.pause() }
        f.emit("LAT",quality=1080,url=server.url("good"))
        Thread.sleep(8000)
        assertTrue(state(f) { !it.playWhenReady && it.currentMediaItem?.localConfiguration?.uri?.path=="/wait" })
        f.manual() // Existing explicit skip/select action permanently owns this attempt.
        f.scenario.onActivity { f.fragment.playerView?.player?.play() }
        Thread.sleep(8000)
        assertTrue(state(f) { it.playWhenReady && it.currentMediaItem?.localConfiguration?.uri?.path=="/wait" })
    }
    @Test fun successfulStartupIsNotChangedWhileExtractionIsPending() = real { f,server ->
        f.emit("LAT",quality=720,url=server.url("early"))
        f.await(10000) { state(f) { it.isPlaying } }
        f.emit("LAT",quality=1080,url=server.url("better"))
        Thread.sleep(8000)
        assertTrue(state(f) { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.path=="/early" })
    }
}
