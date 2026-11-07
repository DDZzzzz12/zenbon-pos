package org.example.project.features.dashboard.presentation.state

/**
 * A Storno that asked to re-open its table session was refused because new guests now occupy the
 * table (server 409 `TABLE_HAS_ACTIVE_SESSION`). Held until the cashier confirms a Storno without
 * re-opening the table, which retries with the same [billId] and [deletingReason].
 */
data class StornoRestoreConflict(
    val billId: Long,
    val deletingReason: String?,
)
