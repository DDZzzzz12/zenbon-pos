package org.example.project.ui.components.pdf

import androidx.compose.ui.graphics.ImageBitmap

/**
 * LRU cache of rendered pages keyed by page index and render width; bounded by bitmap bytes (w*h*4).
 * Not thread-safe: meant for single-thread (UI) use.
 */
class PageBitmapCache(
    private val maxBytes: Long,
) {
    private data class Key(
        val page: Int,
        val widthPx: Int,
    )

    // Insertion-ordered; get() re-inserts so iteration starts at the least recently used entry.
    private val entries = LinkedHashMap<Key, ImageBitmap>()
    private var usedBytes = 0L

    fun get(
        page: Int,
        widthPx: Int,
    ): ImageBitmap? {
        val key = Key(page, widthPx)
        val bitmap = entries.remove(key) ?: return null
        entries[key] = bitmap
        return bitmap
    }

    fun put(
        page: Int,
        widthPx: Int,
        bitmap: ImageBitmap,
    ) {
        val key = Key(page, widthPx)
        entries.remove(key)?.let { usedBytes -= costOf(it) }
        val cost = costOf(bitmap)
        if (cost > maxBytes) return
        entries[key] = bitmap
        usedBytes += cost
        evictOverCap()
    }

    fun clear() {
        entries.clear()
        usedBytes = 0L
    }

    private fun evictOverCap() {
        val iterator = entries.entries.iterator()
        while (usedBytes > maxBytes && iterator.hasNext()) {
            usedBytes -= costOf(iterator.next().value)
            iterator.remove()
        }
    }

    private fun costOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height * 4
}
