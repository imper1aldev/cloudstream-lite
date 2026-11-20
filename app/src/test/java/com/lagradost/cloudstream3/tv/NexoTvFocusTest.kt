package com.lagradost.cloudstream3.tv

import org.junit.Assert.*
import org.junit.Test

class NexoTvFocusTest {
    private fun resolve(keys: List<String>, key: String?, index: Int = 0): Int? {
        val anchorClass = Class.forName("com.lagradost.cloudstream3.ui.tv.TvFocusAnchor")
        val anchor = key?.let { anchorClass.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType).newInstance("row", it, index) }
        return Class.forName("com.lagradost.cloudstream3.ui.tv.NexoTvFocusKt")
            .getMethod("resolveTvFocusIndex", List::class.java, anchorClass).invoke(null, keys, anchor) as Int?
    }
    @Test fun followsIdentityWhenItemsMove() { assertEquals(2, resolve(listOf("b", "c", "a"), "a", 0)) }
    @Test fun removedItemUsesValidNeighbor() { assertEquals(1, resolve(listOf("a", "c"), "b", 1)) }
    @Test fun removedLastItemClampsToNewLast() { assertEquals(1, resolve(listOf("a", "b"), "c", 2)) }
    @Test fun negativeFallbackClampsToFirst() { assertEquals(0, resolve(listOf("a", "b"), "c", -9)) }
    @Test fun emptyListHasNoFocusTarget() { assertNull(resolve(emptyList(), "a", 3)) }
    @Test fun noAnchorUsesFirst() { assertEquals(0, resolve(listOf("a", "b"), null)) }
}
