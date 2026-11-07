package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/** Why a Vorgang went to the TSE signing queue instead of being signed immediately. */
@Serializable
enum class TseLateSigningReason {
    /** The TSE was not reachable (hardware absent, mount point missing, device unreachable). */
    TSE_UNAVAILABLE,

    /** The TSE answered with an error for this Vorgang. */
    TSE_ERROR,

    /**
     * The TSE was connected and answered, but is not in a state to sign: not commissioned,
     * decommissioned, client not registered, certificate expired, PIN/PUK change pending
     * (E2E R2-6; design 2026-09-22 §4.5).
     */
    TSE_NOT_OPERATIONAL,

    /** The Vorgang was found unsigned (PENDING) and queued later by the pending sweeper. */
    PENDING_SWEEP,

    /** Re-queued because the simulator had signed it while NORMAL was configured. */
    SIMULATED_WHILE_NORMAL,

    /** Queued before V82; the original reason was never recorded. */
    UNKNOWN_HISTORIC,
}
