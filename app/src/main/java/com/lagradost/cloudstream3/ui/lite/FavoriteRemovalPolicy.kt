package com.lagradost.cloudstream3.ui.lite

/** Recheck persistence and all profiles at the deletion boundary, including after restart. */
internal fun canDeleteFavoriteDownloads(now: Long, deadline: Long, savedDeadline: Long?, favoriteInAnyAccount: Boolean): Boolean =
    now >= deadline && savedDeadline == deadline && !favoriteInAnyAccount
