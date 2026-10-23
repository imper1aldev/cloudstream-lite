package com.lagradost.cloudstream3.ui.lite

import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.DataStoreHelper.FavoritesData

/** Same ordering/filter contract for phone, tablet and TV; stable ties keep storage order. */
fun filterAndSortFavorites(items: List<FavoritesData>, query: String, downloadedIds: Set<Int>, onlyDownloaded: Boolean, sorting: ListSorting): List<FavoritesData> {
    val filtered = items.filter { it.name.contains(query, ignoreCase = true) && (!onlyDownloaded || it.id in downloadedIds) }
    return when (sorting) {
        ListSorting.AlphabeticalA -> filtered.sortedBy { it.name.lowercase() }
        ListSorting.AlphabeticalZ -> filtered.sortedByDescending { it.name.lowercase() }
        ListSorting.RatingHigh -> filtered.sortedByDescending { it.score?.toInt(100) ?: 0 }
        ListSorting.RatingLow -> filtered.sortedBy { it.score?.toInt(100) ?: 0 }
        ListSorting.ReleaseDateNew -> filtered.sortedByDescending { it.year ?: 0 }
        ListSorting.ReleaseDateOld -> filtered.sortedBy { it.year ?: 0 }
        ListSorting.UpdatedOld -> filtered.sortedBy { it.latestUpdatedTime }
        else -> filtered.sortedByDescending { it.latestUpdatedTime }
    }
}
