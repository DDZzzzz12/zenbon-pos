package org.example.project.admin.features.dashboard.presentation.sections

/**
 * The four counts of work waiting in the document flow.
 *
 * [failed] is not [isEmpty]. A refused call must never render as "nothing to do" — that is the same
 * class of defect as a truncated amount: a number the operator has no reason to distrust, that is
 * wrong.
 */
data class OpenWorkState(
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val toCapture: Long = 0,
    val toReview: Long = 0,
    val overdue: Long = 0,
    val unassignedPayments: Long = 0,
) {
    val isEmpty: Boolean
        get() = toCapture == 0L && toReview == 0L && overdue == 0L && unassignedPayments == 0L
}
