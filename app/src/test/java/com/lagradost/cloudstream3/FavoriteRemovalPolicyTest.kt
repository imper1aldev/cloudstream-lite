package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.lite.canDeleteFavoriteDownloads
import org.junit.Assert.*
import org.junit.Test

class FavoriteRemovalPolicyTest {
    @Test fun accidentalRemovalAllowsTheFullGracePeriod() {
        val deadline = 20_000L
        assertFalse(canDeleteFavoriteDownloads(19_999L, deadline, deadline, false))
        assertTrue(canDeleteFavoriteDownloads(20_000L, deadline, deadline, false))
    }

    @Test fun undoAndReaddingProtectFilesEvenAfterOriginalDeadline() {
        assertFalse(canDeleteFavoriteDownloads(25_000L, 20_000L, null, false))
        assertFalse(canDeleteFavoriteDownloads(25_000L, 20_000L, 20_000L, true))
    }

    @Test fun repeatedRemovalStartsANewGracePeriod() {
        assertFalse(canDeleteFavoriteDownloads(20_000L, 20_000L, 29_000L, false))
        assertFalse(canDeleteFavoriteDownloads(28_999L, 29_000L, 29_000L, false))
        assertTrue(canDeleteFavoriteDownloads(29_000L, 29_000L, 29_000L, false))
    }

    @Test fun favoriteInAnotherAccountProtectsSharedFiles() {
        assertFalse(canDeleteFavoriteDownloads(30_000L, 20_000L, 20_000L, true))
    }
}
