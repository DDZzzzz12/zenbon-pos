package org.example.project.core.data.remote

/**
 * Whether an operation was carried out, or handed to a manager to approve.
 *
 * `requirePermissionOrDefer` answers **202 Accepted** when the caller may not perform the operation
 * itself: the server stores the action and waits for a manager's decision. The client discarded the
 * status, so 202 travelled the same path as 200 — the view model reloaded the table, closed the
 * dialog and reported success, while the cashier's device showed nothing at all and the request sat
 * live on the manager's screen (E2E-2026-09-20 #6). "Submitted, waiting for a manager" and "the tap
 * did nothing" were indistinguishable at the counter.
 */
enum class ApprovalOutcome {
    /** The server performed the operation. */
    COMPLETED,

    /** 202: an approval request was raised; nothing has happened yet. */
    AWAITING_APPROVAL,
}
