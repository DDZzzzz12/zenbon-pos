package org.example.project.features.maintenance.data.service

/**
 * Progressive delay for the maintainer login: the 5th consecutive failure blocks for 30 s and each
 * further one doubles that, up to 15 min. A success resets it.
 *
 * A delay, not a hard lock, on purpose: there is one account, so a lock would let anyone who can
 * reach the port lock the maintainer out indefinitely. The cost accepted instead is that they can
 * make the maintainer wait up to 15 minutes. Global (not per address) because behind a reverse proxy
 * the address is not trustworthy. In memory: a restart clears it (the manual says so).
 */
class LoginThrottle(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val freeFailures: Int = 4,
    private val firstDelayMillis: Long = 30_000,
    private val maxDelayMillis: Long = 15 * 60_000L,
) {
    private var consecutiveFailures = 0
    private var blockedUntilMillis = 0L

    /** Seconds until another attempt is allowed (rounded up), or null when none is blocked. */
    @Synchronized
    fun retryAfterSeconds(): Long? {
        val remaining = blockedUntilMillis - nowMillis()
        return if (remaining > 0) (remaining + 999) / 1000 else null
    }

    /** Records a failed attempt and returns the number of consecutive failures so far. */
    @Synchronized
    fun recordFailure(): Int {
        consecutiveFailures++
        val beyondFree = consecutiveFailures - freeFailures
        if (beyondFree >= 1) {
            val delay = (firstDelayMillis shl (beyondFree - 1).coerceAtMost(20)).coerceAtMost(maxDelayMillis)
            blockedUntilMillis = nowMillis() + delay
        }
        return consecutiveFailures
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        blockedUntilMillis = 0L
    }
}
