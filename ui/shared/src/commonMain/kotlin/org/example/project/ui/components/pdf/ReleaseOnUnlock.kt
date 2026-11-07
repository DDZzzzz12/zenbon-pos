package org.example.project.ui.components.pdf

import co.touchlab.kermit.Logger

/**
 * Lets a renderer's `close()` return without waiting for its document lock, while the document is still only
 * ever freed under that lock.
 *
 * `close()` marks the renderer closed (so [isClosed] turns true for good) and calls [releaseIfClosed], which frees
 * the document at once if the lock is free. If the lock is held, the holder frees it instead: every acquisition
 * goes through [withDocumentLock], which re-checks [isClosed] after letting go. Because `close()` sets the flag
 * before it tries the lock, and a holder checks the flag after it unlocks, one of them always gets to release:
 * either `close()`'s try succeeds, or a holder exists whose check comes later and sees the flag. A release that
 * races with a newly queued caller is safe too: that caller takes the lock, finds the renderer closed, refuses, and
 * releases on its own way out. [release] must be idempotent.
 *
 * The lock primitives are passed in so this works over any JVM/Android lock and can be tested without threads.
 */
internal class ReleaseOnUnlock(
    private val lock: () -> Unit,
    private val tryLock: () -> Boolean,
    private val unlock: () -> Unit,
    private val isClosed: () -> Boolean,
    /** Frees the document; called only while the lock is held, possibly more than once. */
    private val release: () -> Unit,
) {
    private val log = Logger.withTag("PdfViewer")

    /** Runs [block] under the lock; if the renderer was closed meanwhile, frees the document before returning. */
    fun <T> withDocumentLock(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
            releaseIfClosed()
        }
    }

    /** Frees the document now if the renderer is closed and the lock is free; otherwise leaves it to the holder. */
    fun releaseIfClosed() {
        if (!isClosed() || !tryLock()) return
        try {
            release()
        } catch (e: Exception) {
            // Called from close() on the UI thread and from the finally of unrelated work: a failing close of a
            // document nobody will use again must not surface as that work's failure.
            log.w(e) { "releasing the closed PDF document failed" }
        } finally {
            unlock()
        }
    }
}
