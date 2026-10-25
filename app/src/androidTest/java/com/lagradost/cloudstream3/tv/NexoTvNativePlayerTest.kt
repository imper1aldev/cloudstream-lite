package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.widget.ListView
import androidx.media3.common.Player
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream4.AppSettings
import org.junit.Assert.*
import org.junit.Test

/** TV intentionally keeps upstream playback; PHONE-only Lite policies are checked separately. */
class NexoTvNativePlayerTest {
    private fun state(f:LiteProgressivePlaybackTest.Fixture, condition:(Player)->Boolean):Boolean {
        var result=false;f.scenario.onActivity { result=f.fragment.playerView?.player?.let(condition)==true };return result
    }
    private fun real(block:(LiteProgressivePlaybackTest.Fixture,LiteRealPlayerSwitchTest.MediaServer)->Unit) {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val setting=AppSettings(context).player.startPaused;val old=setting.get();setting.set(false)
        try { LiteRealPlayerSwitchTest.MediaServer().use { server -> LiteProgressivePlaybackTest.Fixture(layout=1,realPlayer=true).use { block(it,server) } } }
        finally { setting.set(old) }
    }
    @Test fun tvStartsAfterExtractionCompletesAndKeepsNativeRetry() = real { f,server ->
        f.emit("LAT",quality=2160,server="Broken",url=server.url("fail"))
        f.emit("LAT",quality=1080,server="Good",url=server.url("good"))
        SystemClock.sleep(300)
        assertFalse("TV unexpectedly adopted PHONE-only progressive start",state(f) { it.isPlaying })
        f.finish()
        f.await(15_000) { state(f) { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.path=="/good" } }
        assertTrue("Failed native server was not attempted",server.requests.contains("/fail"))
        assertTrue(state(f) { it.playWhenReady && it.currentPosition>0 })
    }
    @Test fun nativeMirrorDialogSwitchKeepsPositionAndPlayingIntent() = real { f,server ->
        f.emit("LAT",quality=1080,server="First",url=server.url("first"))
        f.emit("ENG",quality=720,server="Second",url=server.url("second"))
        f.finish()
        f.await(15_000) { state(f) { it.isPlaying && it.currentPosition>1200 } }
        var position=0L
        f.scenario.onActivity { position=f.fragment.playerView!!.player!!.currentPosition;f.fragment.showMirrorsDialogue() }
        f.idle()
        val dialog=FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog").apply { isAccessible=true }.get(f.fragment) as android.app.Dialog
        f.scenario.onActivity {
            val list=dialog.findViewById<ListView>(R.id.sort_providers)
            val selected=list.checkedItemPosition
            val other=if(selected==0)1 else 0
            list.performItemClick(list.adapter.getView(other,null,list),other,other.toLong())
            dialog.findViewById<android.view.View>(R.id.apply_btt).performClick()
        }
        f.await(15_000) { state(f) { it.isPlaying && it.currentPosition>=position-500 } }
        assertTrue("Native mirror selection did not load second media",server.requests.contains("/first") && server.requests.contains("/second"))
    }
}
