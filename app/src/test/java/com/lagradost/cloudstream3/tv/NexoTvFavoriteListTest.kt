package com.lagradost.cloudstream3.tv

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.DataStoreHelper.FavoritesData
import org.junit.Assert.*
import org.junit.Test

class NexoTvFavoriteListTest {
    private fun item(id:Int,name:String,time:Long,year:Int?,rating:Int?) = FavoritesData(time,id,time,name,"fixture/$id","NexoStream",null,null,year,score=Score.from100(rating))
    private val items=listOf(item(1,"Zulu",10,2020,80),item(2,"alpha",30,2024,50),item(3,"ALPHA",20,null,null))
    @Suppress("UNCHECKED_CAST")
    private fun sorted(mode:ListSorting, query:String="", downloaded:Set<Int> = emptySet(),only:Boolean=false):List<Int?> =
        (Class.forName("com.lagradost.cloudstream3.ui.lite.FavoriteListPolicyKt").getMethod("filterAndSortFavorites",List::class.java,String::class.java,Set::class.java,Boolean::class.javaPrimitiveType,ListSorting::class.java)
            .invoke(null,items,query,downloaded,only,mode) as List<FavoritesData>).map { it.id }
    @Test fun alphabeticalOrdersKeepExistingTieOrder() {
        assertEquals(listOf(2,3,1),sorted(ListSorting.AlphabeticalA))
        assertEquals(listOf(1,2,3),sorted(ListSorting.AlphabeticalZ))
    }
    @Test fun missingRatingUsesZero() {
        assertEquals(listOf(1,2,3),sorted(ListSorting.RatingHigh))
        assertEquals(listOf(3,2,1),sorted(ListSorting.RatingLow))
    }
    @Test fun releaseDatesPreserveMissingYearSemantics() {
        assertEquals(listOf(2,1,3),sorted(ListSorting.ReleaseDateNew))
        assertEquals(listOf(3,1,2),sorted(ListSorting.ReleaseDateOld))
    }
    @Test fun updatedAndFallbackUseLatestUpdateNotFavoriteTime() {
        assertEquals(listOf(2,3,1),sorted(ListSorting.UpdatedNew))
        assertEquals(listOf(1,3,2),sorted(ListSorting.UpdatedOld))
        assertEquals(listOf(2,3,1),sorted(ListSorting.Query))
    }
    @Test fun queryIsCaseInsensitiveAndDownloadedFilterCombines() {
        assertEquals(listOf(2,3),sorted(ListSorting.UpdatedNew,"AlPhA"))
        assertEquals(listOf(3),sorted(ListSorting.UpdatedNew,"alpha",setOf(1,3),true))
        assertTrue(sorted(ListSorting.UpdatedNew,"missing",setOf(1),true).isEmpty())
    }
}
