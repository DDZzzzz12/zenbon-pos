package org.example.project.core.network

/** Exponential-backoff reconnect state machine. Pure + testable; no coroutines. */
class ReconnectPolicy(
    private val maxAttempts: Int = 5,
    private val baseDelayMs: Long = 1000,
    private val maxDelayMs: Long = 30_000,
    private val slowRetryDelayMs: Long = 15_000,
) {
    var attempts: Int = 0
        private set

    /**
     * True once the fast ladder is spent. Retries do not stop here — they continue at
     * [slowRetryDelayMs]; this flag exists so the UI can show a degraded state once, rather
     * than once per slow retry.
     */
    val isExhausted: Boolean get() = attempts >= maxAttempts

    /**
     * Next backoff delay. **Never null**: an order station that loses its network for six minutes
     * has to come back on its own, and the previous contract — null once [maxAttempts] was reached —
     * made it give up permanently until someone restarted the app.
     *
     * The return type was narrowed from `Long?` to `Long` deliberately. The nullable type no longer
     * had a value it could take, and keeping it would leave every `?: giveUp()` at a call site
     * compiling and silently unreachable; as a non-null type, the compiler flags each stale null
     * check instead.
     *
     * The first [maxAttempts] calls walk the exponential ladder capped at [maxDelayMs]; every call
     * after that returns [slowRetryDelayMs].
     *
     * [slowRetryDelayMs] is 15s, not the 60s it started at. A till that has been disconnected long
     * enough to spend the fast ladder is one whose floor plan is going stale, and the 2026-09-19
     * rehearsal measured the cost: after pos-server came back, the till stayed "Getrennt" for the
     * rest of the minute, missing every table update another device made. Polling a down server
     * four times a minute instead of once is nothing next to that.
     */
    fun nextDelay(): Long {
        val delay =
            if (isExhausted) slowRetryDelayMs else (baseDelayMs * (1L shl attempts)).coerceAtMost(maxDelayMs)
        attempts++
        return delay
    }

    fun reset() {
        attempts = 0
    }
}
