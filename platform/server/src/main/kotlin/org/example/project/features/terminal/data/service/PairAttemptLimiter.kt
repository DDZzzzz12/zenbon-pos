/**
 * In-memory rate limiter for the public `/api/v1/terminals/pair` endpoint.
 *
 * Pairing codes are 6-digit numeric (~1e6 possibilities) and, without a guard, are
 * brute-forceable within the code's TTL. Keyed by client IP; single-process/in-memory —
 * fine for the single pos-server instance per shop this project targets.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.data.service

import org.example.project.utils.TestOnlyApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks failed pairing attempts per key (source IP) within a rolling window and blocks
 * further attempts once the threshold is reached, until the window elapses.
 *
 * Only failures create a window, and elapsed windows are swept at most once per window
 * length, so the map holds just the keys that failed recently — a key that never returns
 * does not stay in memory forever.
 */
object PairAttemptLimiter {
    private data class Window(
        var count: Int,
        var firstAtMs: Long,
    )

    private val windows = ConcurrentHashMap<String, Window>()
    private val lastSweepAtMs = AtomicLong(0L)
    private const val MAX = 5
    private const val WINDOW_MS = 5 * 60 * 1000L

    /** Whether [key] is still allowed to attempt a pairing right now. */
    fun checkAllowed(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        sweepExpired(nowMs)
        val w =
            windows.computeIfPresent(key) { _, cur ->
                if (cur.isExpired(nowMs)) null else cur
            }
        return w == null || w.count < MAX
    }

    /** Record a failed pairing attempt (invalid/expired code) for [key]. */
    fun recordFailure(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        sweepExpired(nowMs)
        windows.compute(key) { _, cur ->
            if (cur == null || cur.isExpired(nowMs)) Window(1, nowMs) else cur.also { it.count++ }
        }
    }

    /** A successful pairing clears the counter for [key]. */
    fun recordSuccess(key: String) {
        windows.remove(key)
    }

    /** Test-only: clears all tracked windows so tests don't leak state across runs. */
    fun resetAllForTests() {
        windows.clear()
        lastSweepAtMs.set(0L)
    }

    /** Test-only: number of keys currently holding a window. */
    @TestOnlyApi
    fun trackedKeyCount(): Int = windows.size

    private fun Window.isExpired(nowMs: Long): Boolean = nowMs - firstAtMs > WINDOW_MS

    /**
     * Drops every elapsed window. Runs at most once per [WINDOW_MS] (or again if the clock
     * went backwards), and only on the thread that wins the CAS, so the per-request cost
     * stays O(1) amortised. Each removal is atomic per key, so a concurrent
     * [recordFailure] that has just restarted a window is never lost.
     */
    private fun sweepExpired(nowMs: Long) {
        val last = lastSweepAtMs.get()
        if ((nowMs - last) in 0L until WINDOW_MS) return
        if (!lastSweepAtMs.compareAndSet(last, nowMs)) return
        for (key in windows.keys) {
            windows.computeIfPresent(key) { _, cur -> if (cur.isExpired(nowMs)) null else cur }
        }
    }
}
