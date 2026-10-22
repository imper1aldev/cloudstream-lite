package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.player.DisplayLink
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.settings.Globals
import org.junit.Assert.*
import org.junit.Test

class LiteLegacyPlaybackTest {
    private fun matchesLegacyOrder(f: LiteProgressivePlaybackTest.Fixture) {
        f.emit("ENG"); f.emit("CAST")
        f.scenario.onActivity {
            val field=FullScreenPlayer::class.java.getDeclaredField("currentQualityProfile").apply { isAccessible=true }
            val profile=field.getInt(f.fragment)
            @Suppress("UNCHECKED_CAST")
            val adapted=f.call("orderedPlaybackLinks") as List<DisplayLink>
            assertEquals(f.vm.state.sortLinks(profile),adapted)
            assertEquals("[Fixture][ENG] Server",adapted.first().link.first?.name)
        }
        f.finish()
        assertEquals("[Fixture][ENG] Server",f.loads.single().name)
    }
    @Test fun otherProviderRetainsLegacyRankingOnPhone() = LiteProgressivePlaybackTest.Fixture().use { f ->
        assertTrue(Globals.isLayout(Globals.PHONE))
        f.setProvider("OtherProvider")
        matchesLegacyOrder(f)
    }
    @Test fun nexoProviderRetainsLegacyRankingOnTv() = LiteProgressivePlaybackTest.Fixture(layout=1).use { f ->
        assertTrue(Globals.isLayout(Globals.TV))
        matchesLegacyOrder(f)
    }
}
