package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.lite.*
import org.junit.Assert.*
import org.junit.Test

class LiteUpgradeTest {
    private fun candidate(id: String, audio: String = "LAT", quality: Int = 1080, startup: Long? = null, failed: Boolean = false) =
        LiteCandidate(id, LiteLinkInfo("Fixture", id, audio, false, false), quality, startupMs = startup, failed = failed)
    @Test fun onlyObjectiveQualityImprovementUpgrades() {
        val early=candidate("Zeta", quality=720)
        assertEquals("Alpha", chooseLiteUpgrade(listOf(early,candidate("Alpha")),early.id))
        assertNull(chooseLiteUpgrade(listOf(candidate("Zeta"),candidate("Alpha")),"Zeta"))
    }
    @Test fun existingLanguageOrderStillPrecedesResolution() {
        val cast=candidate("Cast", "CAST", 2160)
        val lat=candidate("Lat", "LAT", 720)
        assertEquals("Lat",chooseLiteUpgrade(listOf(cast,lat),"Cast"))
        assertNull(chooseLiteUpgrade(listOf(cast,lat),"Lat"))
    }
    @Test fun measuredStartupRequiresTheWholeGroupToBeComparable() {
        val slow=candidate("Zeta",startup=2000)
        val fast=candidate("Alpha",startup=1000)
        assertEquals("Alpha",chooseLiteUpgrade(listOf(slow,fast),"Zeta"))
        assertNull(chooseLiteUpgrade(listOf(slow,fast,candidate("Unknown")),"Zeta"))
    }
    @Test fun failedSourcesAndUnknownCurrentIdentityDoNotTriggerUpgrade() {
        val current=candidate("Current",quality=720)
        assertNull(chooseLiteUpgrade(listOf(current,candidate("Failed",quality=2160,failed=true)),"Current"))
        assertNull(chooseLiteUpgrade(listOf(current),"Missing"))
    }
}
