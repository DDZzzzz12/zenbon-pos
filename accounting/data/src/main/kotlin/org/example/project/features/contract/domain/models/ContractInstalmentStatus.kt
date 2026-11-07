package org.example.project.features.contract.domain.models

/**
 * What has happened to one expected payment.
 *
 * The generator writes only [OPEN]. [PAID] and [PARTIAL] follow allocations (slice 4b) and [WAIVED]
 * is a human decision.
 *
 * **[OVERDUE] is never stored.** It is derived at read time from the due date and the residual: a
 * stored status would say OPEN whenever nothing has run since the date passed, which is worse than
 * having no status at all. The constant exists because the read model reports it.
 */
enum class ContractInstalmentStatus {
    OPEN,
    PAID,
    PARTIAL,
    OVERDUE,
    WAIVED,
}
