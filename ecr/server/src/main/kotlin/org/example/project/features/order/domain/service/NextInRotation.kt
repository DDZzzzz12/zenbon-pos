package org.example.project.features.order.domain.service

/**
 * Round-robin successor of [cursor] in [pool]: the element after the cursor, wrapping at the end.
 *
 * A null cursor, or one no longer in the pool, restarts at the first element. An empty pool has no
 * successor and returns null.
 */
fun <T> nextInRotation(
    pool: List<T>,
    cursor: T?,
): T? {
    if (pool.isEmpty()) return null
    val cursorIndex = if (cursor == null) -1 else pool.indexOf(cursor)
    return pool[(cursorIndex + 1) % pool.size]
}
