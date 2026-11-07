package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PageBitmapCacheTest {
    // 10x10 bitmap costs 400 bytes.
    private fun bitmap() = FakeImageBitmap(10, 10)

    @Test
    fun retainsTwoBitmapsUnderCap() {
        val cache = PageBitmapCache(maxBytes = 1000)
        cache.put(0, 10, bitmap())
        cache.put(1, 10, bitmap())
        assertNotNull(cache.get(0, 10))
        assertNotNull(cache.get(1, 10))
    }

    @Test
    fun evictsLeastRecentlyReadWhenOverCap() {
        val cache = PageBitmapCache(maxBytes = 1000)
        cache.put(0, 10, bitmap())
        cache.put(1, 10, bitmap())
        cache.get(0, 10)
        cache.put(2, 10, bitmap())
        assertNotNull(cache.get(0, 10))
        assertNull(cache.get(1, 10))
        assertNotNull(cache.get(2, 10))
    }

    @Test
    fun oversizedBitmapIsNotStored() {
        val cache = PageBitmapCache(maxBytes = 399)
        cache.put(0, 10, bitmap())
        assertNull(cache.get(0, 10))
    }

    @Test
    fun clearEmptiesCache() {
        val cache = PageBitmapCache(maxBytes = 1000)
        cache.put(0, 10, bitmap())
        cache.clear()
        assertNull(cache.get(0, 10))
    }
}
