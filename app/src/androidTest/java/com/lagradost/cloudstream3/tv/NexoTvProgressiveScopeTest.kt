package com.lagradost.cloudstream3.tv

import android.view.View
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import org.junit.Assert.*
import org.junit.Test

class NexoTvProgressiveScopeTest {
    @Test fun tvStartsFirstLatWithoutEndingExtractionOrFocusingHiddenSkip() = LiteProgressivePlaybackTest.Fixture(layout=1).use { f ->
        f.emit("ENG",quality=0)
        assertTrue(f.loads.isEmpty())
        var hiddenSkipFocused=false
        f.scenario.onActivity { f.fragment.requireView().findViewById<View>(R.id.overlay_loading_skip_button).setOnFocusChangeListener { v, has -> if(has && !v.isShown) { hiddenSkipFocused=true; android.util.Log.e("NexoTvProgressive", "Focus reached hidden Skip", Throwable()) } } }
        f.emit("LAT",quality=0)
        f.await { f.loads.size==1 }
        assertTrue(f.vm.state.loading is Resource.Loading)
        assertTrue(f.loads.single().name!!.contains("[LAT]"))
        f.scenario.onActivity { assertFalse(f.fragment.requireView().findViewById<View>(R.id.player_loading_overlay).isShown) }
        assertFalse("Hidden skip took focus after automatic start",hiddenSkipFocused)
        f.emit("LAT",quality=720)
        assertEquals(1,f.loads.size)
    }
    @Test fun firstLatCannotFocusSkipAfterHidingTheLoadingOverlay(): Unit = LiteProgressivePlaybackTest.Fixture(layout=1).use { f ->
        var hiddenSkipFocused=false
        f.scenario.onActivity { f.fragment.requireView().findViewById<View>(R.id.overlay_loading_skip_button).setOnFocusChangeListener { v, has -> if(has && !v.isShown) { hiddenSkipFocused=true; android.util.Log.e("NexoTvProgressive", "Focus reached hidden Skip", Throwable()) } } }
        f.emit("LAT",quality=0)
        f.await { f.loads.size==1 }
        assertFalse("Automatic LAT focused Skip in a hidden loading overlay",hiddenSkipFocused)
        f.scenario.onActivity { assertFalse("Hidden Skip retained focus",f.fragment.requireView().findViewById<View>(R.id.overlay_loading_skip_button).hasFocus()) }
    }
    @Test fun remoteSelectionSurvivesLiveReorderingAndApplyKeepsManualChoice(): Unit = LiteProgressivePlaybackTest.Fixture(layout=1).use { f ->
        f.emit("LAT",quality=720); f.emit("ENG",quality=720)
        f.scenario.onActivity { f.fragment.showMirrorsDialogue() }; f.idle()
        val d=com.lagradost.cloudstream3.ui.player.FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog").apply { isAccessible=true }.get(f.fragment) as android.app.Dialog
        val list=d.findViewById<android.widget.ListView>(R.id.sort_providers)
        fun english()=(0 until list.count).first { list.getItemAtPosition(it)?.toString()?.contains("Inglés")==true }
        var ready=false
        f.await { f.scenario.onActivity { ready=d.window!!.decorView.hasWindowFocus() }; ready }
        val clicked=java.util.concurrent.atomic.AtomicBoolean()
        f.scenario.onActivity {
            list.requestFocus(); list.setSelection(english())
            val original=list.onItemClickListener
            list.onItemClickListener=android.widget.AdapterView.OnItemClickListener { parent, view, position, id -> original!!.onItemClick(parent,view,position,id); clicked.set(true) }
        }
        f.idle()
        f.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        f.await { clicked.get() }
        f.emit("LAT",quality=1080)
        f.scenario.onActivity {
            assertTrue("Remote row changed identity",list.getItemAtPosition(list.selectedItemPosition).toString().contains("Inglés"))
            assertEquals(english(),list.checkedItemPosition)
            d.findViewById<View>(R.id.apply_btt).requestFocus()
        }
        f.emit("CAST",quality=1080)
        f.scenario.onActivity { assertEquals("Live links stole Apply focus",R.id.apply_btt,d.currentFocus?.id) }
        f.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);f.idle()
        f.finish()
        assertTrue("Final upgrade overrode remote manual choice",f.loads.last().name!!.contains("[ENG]"))
    }
    @Test fun arrivalDuringNativeClickCannotSelectAnotherServer(): Unit = LiteProgressivePlaybackTest.Fixture(layout=1).use { f ->
        f.emit("LAT",quality=720); f.emit("ENG",quality=720)
        f.scenario.onActivity { f.fragment.showMirrorsDialogue() }; f.idle()
        val d=com.lagradost.cloudstream3.ui.player.FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog").apply { isAccessible=true }.get(f.fragment) as android.app.Dialog
        val list=d.findViewById<android.widget.ListView>(R.id.sort_providers)
        var ready=false
        f.await { f.scenario.onActivity { ready=d.window!!.decorView.hasWindowFocus() };ready }
        f.scenario.onActivity { list.requestFocus(); list.setSelection((0 until list.count).first { list.getItemAtPosition(it)?.toString()?.contains("Inglés")==true }) }; f.idle()
        val link=kotlinx.coroutines.runBlocking { com.lagradost.cloudstream3.utils.newExtractorLink("Fixture","[Fixture][LAT] New", "https://fixture.invalid/new", com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO) { quality=1080 } }
        f.scenario.onActivity {
            val now=android.os.SystemClock.uptimeMillis()
            list.dispatchKeyEvent(android.view.KeyEvent(now,now,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_CENTER,0))
            list.dispatchKeyEvent(android.view.KeyEvent(now,now+1,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DPAD_CENTER,0))
            f.vm.modifyState { add(link to null) }
        }
        f.idle()
        Thread.sleep(android.view.ViewConfiguration.getPressedStateDuration().toLong()+150); f.idle()
        f.scenario.onActivity { assertTrue("A reordered row changed the server chosen by the native remote click",list.getItemAtPosition(list.checkedItemPosition).toString().contains("Inglés")) }
    }
    @Test fun tvOtherProviderKeepsCompletionFirstBehavior() = LiteProgressivePlaybackTest.Fixture(layout=1).use { f ->
        f.setProvider("OtherProvider"); f.emit("LAT",quality=0)
        assertTrue(f.loads.isEmpty()); f.finish(); f.await { f.loads.size==1 }
    }
    @Test fun dedicatedEmulatorLayoutKeepsLegacyBehavior() = LiteProgressivePlaybackTest.Fixture(layout=2).use { f ->
        f.emit("LAT",quality=0)
        assertTrue(f.loads.isEmpty()); f.finish(); f.await { f.loads.size==1 }
    }
    @Test fun absentProviderResponseKeepsLocalRouteOutsideLitePolicy() = LiteProgressivePlaybackTest.Fixture(layout=1).use { f ->
        f.scenario.onActivity { f.vm.modifyState { copy(generatorState=generatorState?.copy(response=null)) } }
        f.emit("LAT",quality=0)
        assertTrue(f.loads.isEmpty()); f.finish(); f.await { f.loads.size==1 }
    }
}
