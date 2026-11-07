package org.example.project.core.browser

/** Where the time of the last automatic reload survives the reload itself (sessionStorage in a browser). */
interface ReloadStampStore {
    fun read(): Long?

    fun write(epochMillis: Long)
}

/**
 * Stops an automatic update reload from looping: should the reloaded page still report an old
 * bundle (stale cache), a second automatic reload within [minIntervalMillis] is refused so only the
 * banner remains. The manual "reload now" button is never limited.
 */
class ReloadLoopGuard(
    private val store: ReloadStampStore,
    private val clock: () -> Long,
    private val minIntervalMillis: Long = 60_000,
) {
    /** True and records the attempt when an automatic reload is allowed now. */
    fun tryAcquire(): Boolean {
        val now = clock()
        val last = store.read()
        if (last != null && now - last in 0 until minIntervalMillis) return false
        store.write(now)
        return true
    }
}
