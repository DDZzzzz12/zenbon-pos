package org.example.project.admin.core.util.pdf

/** How long after `afterprint` the print frame is kept, so a browser still spooling from it is not cut off. */
internal const val AFTER_PRINT_GRACE_MS = 5_000

/**
 * Fallback for browsers that never fire `afterprint` for an iframe's print: the frame is released this long
 * after the print started. Generous on purpose — removing the frame while its print dialog is still open
 * cancels the job, and nobody keeps a print dialog open for ten minutes.
 */
internal const val PRINT_FALLBACK_RELEASE_MS = 10 * 60 * 1_000

/**
 * When to free the hidden print frame and the `blob:` URL of the last printed PDF: a short grace period after
 * the print finished (`afterprint`), at the latest [fallbackMs] after it started, at once when the next print
 * starts, and at once when the session ends ([releaseNow]). At most one print is held at a time.
 *
 * Pure scheduling; the timer functions are passed in so the rules can be tested without a browser clock.
 */
internal class PrintFrameRelease(
    private val setTimer: (delayMs: Int, action: () -> Unit) -> Int,
    private val clearTimer: (timerId: Int) -> Unit,
    private val fallbackMs: Int = PRINT_FALLBACK_RELEASE_MS,
    private val graceMs: Int = AFTER_PRINT_GRACE_MS,
) {
    private var release: (() -> Unit)? = null
    private var timerId: Int? = null

    /** Bumped per print, so a late event or timer of an earlier print never frees a later one. */
    private var generation = 0

    /** True while a print's frame and URL are held. */
    val isHolding: Boolean get() = release != null

    /**
     * Frees the previous print, holds [release] for this one and starts the fallback timer. Returns the callback
     * for this print's `afterprint`; calling it more than once, or after the print was released, does nothing.
     */
    fun hold(release: () -> Unit): () -> Unit {
        releaseNow()
        val print = ++generation
        this.release = release
        restartTimer(fallbackMs, print)
        return { if (generation == print && isHolding) restartTimer(graceMs, print) }
    }

    /** Frees the held print, if any, now; idempotent. */
    fun releaseNow() {
        val pending = release ?: return
        release = null
        cancelTimer()
        pending()
    }

    private fun restartTimer(
        delayMs: Int,
        print: Int,
    ) {
        cancelTimer()
        timerId = setTimer(delayMs) { if (generation == print) releaseNow() }
    }

    private fun cancelTimer() {
        timerId?.let(clearTimer)
        timerId = null
    }
}
