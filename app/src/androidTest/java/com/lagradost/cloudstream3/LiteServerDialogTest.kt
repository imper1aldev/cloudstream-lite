package com.lagradost.cloudstream3

import android.app.Dialog
import android.widget.ListView
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import org.junit.Assert.*
import org.junit.Test

class LiteServerDialogTest {
    private fun dialog(f: LiteProgressivePlaybackTest.Fixture): Dialog {
        val field = FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog").apply { isAccessible = true }
        return field.get(f.fragment) as Dialog
    }
    private fun open(f: LiteProgressivePlaybackTest.Fixture): Dialog {
        f.scenario.onActivity { f.fragment.showMirrorsDialogue() }
        f.idle()
        return dialog(f)
    }
    private fun row(list: ListView, language: String): Int = (0 until list.count).first {
        list.getItemAtPosition(it)?.toString()?.let { text ->
            text.contains("[$language]") || text.contains(when(language) { "LAT" -> "Latino"; "CAST" -> "Castellano"; else -> "Inglés" })
        } == true
    }
    @Test fun lateLatReordersWithoutChangingPendingSelection(): Unit = LiteProgressivePlaybackTest.Fixture().use { f ->
        f.emit("CAST"); f.emit("ENG"); f.manual()
        val d = open(f)
        val list = d.findViewById<ListView>(R.id.sort_providers)
        f.scenario.onActivity {
            val index = row(list, "ENG")
            list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
        }
        f.emit("LAT")
        f.scenario.onActivity {
            assertTrue("New server missing from live dialog", (0 until list.count).any { list.getItemAtPosition(it)?.toString()?.contains("Latino") == true })
            assertEquals(row(list, "ENG"), list.checkedItemPosition)
            d.findViewById<android.view.View>(R.id.apply_btt).performClick()
        }
        assertEquals("[Fixture][ENG] Server", f.loads.last().name)
        assertEquals(2, f.loads.size)
    }
    @Test fun refreshKeepsCurrentChoiceAndSubtitleSelection(): Unit = LiteProgressivePlaybackTest.Fixture().use { f ->
        f.emit("CAST"); f.manual()
        f.scenario.onActivity { f.vm.modifyState { add(com.lagradost.cloudstream3.ui.player.SubtitleData("Spanish", "", "https://fixture.invalid/sub.srt", com.lagradost.cloudstream3.ui.player.SubtitleOrigin.URL, "application/x-subrip", emptyMap(), "es")) } }
        f.idle()
        val d = open(f)
        val list = d.findViewById<ListView>(R.id.sort_providers)
        val subtitles = d.findViewById<ListView>(R.id.sort_subtitles)
        f.scenario.onActivity { subtitles.performItemClick(subtitles.adapter.getView(1, null, subtitles), 1, 1) }
        f.emit("LAT")
        f.scenario.onActivity {
            assertTrue(list.count >= 2)
            assertEquals(row(list, "CAST"), list.checkedItemPosition)
            assertEquals(1, subtitles.checkedItemPosition)
            assertEquals(1, f.loads.size)
            d.dismiss()
        }
    }
    @Test fun dismissReopenDuplicateAndEpisodeChangeHaveNoStaleObserver(): Unit = LiteProgressivePlaybackTest.Fixture().use { f ->
        f.emit("CAST"); f.manual()
        val old = open(f)
        val oldList = old.findViewById<ListView>(R.id.sort_providers)
        val oldCount = oldList.count
        f.scenario.onActivity { old.dismiss() }
        val current = open(f)
        val list = current.findViewById<ListView>(R.id.sort_providers)
        f.emit("LAT")
        f.scenario.onActivity {
            assertEquals(oldCount, oldList.count)
            assertTrue(list.count > oldCount)
        }
        val before = list.count
        f.generator.callbacks[0](f.vm.state.links.first { it.first?.name?.contains("[LAT]") == true })
        f.idle()
        f.scenario.onActivity {
            assertEquals(before, list.count)
            f.fragment.nextEpisode()
        }
        f.idle()
        assertFalse(current.isShowing)
    }
    @Test fun removedPendingChoiceDoesNotApplyAnotherServerAndLoadingIndicatorEnds(): Unit = LiteProgressivePlaybackTest.Fixture().use { f ->
        f.emit("CAST"); f.emit("ENG"); f.manual()
        val d = open(f)
        val list = d.findViewById<ListView>(R.id.sort_providers)
        lateinit var status: android.view.View
        f.scenario.onActivity {
            val matches = arrayListOf<android.view.View>()
            d.window!!.decorView.findViewsWithText(matches, f.context.getString(R.string.lite_searching_servers), android.view.View.FIND_VIEWS_WITH_TEXT)
            status = matches.single()
            assertTrue(status.isShown)
            val index = row(list, "ENG")
            list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
            f.vm.modifyState { copy(links = links.remove(links.first { it.first?.name?.contains("[ENG]") == true })) }
        }
        f.idle()
        f.scenario.onActivity { d.findViewById<android.view.View>(R.id.apply_btt).performClick() }
        assertEquals(1, f.loads.size)
        assertTrue(d.isShowing)
        f.finish()
        f.scenario.onActivity {
            assertFalse(status.isShown)
            d.dismiss()
        }
    }
}
