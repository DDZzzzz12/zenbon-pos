package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/**
 * Observed TSE hardware health, derived per health-check tick.
 *
 * Orthogonal to configured [org.example.project.core.domain.enums.setup.TseMode]:
 *   - mode says what the operator chose (NORMAL / SIMULATION / DISABLED)
 *   - health says what the hardware is actually doing right now
 */
@Serializable
enum class TseHealthState {
    /** Initialized, valid time, self-test passed, signatures > 1000. */
    HEALTHY,

    /** At least one tracked maintenance op is in flight (selftest, timesync, init, reconnect). */
    MAINTENANCE,

    /** Raw unhealthy snapshot outside any maintenance window — investigate. */
    DEGRADED,

    /** Hardware not present, configured DISABLED, or connection lost. */
    UNREACHABLE,
}
