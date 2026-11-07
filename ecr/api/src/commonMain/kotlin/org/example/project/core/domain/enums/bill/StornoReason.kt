package org.example.project.core.domain.enums.bill

import kotlinx.serialization.Serializable

/**
 * Why a Storno Beleg was booked, when the reason is machine-relevant. Null on a Storno means an
 * ordinary cancellation with its free-text reason in `deleting_reason`.
 */
@Serializable
enum class StornoReason {
    /** The Storno half of a Zahlartwechsel: the sale is booked again at once with a new tender/tip. */
    ZAHLARTWECHSEL,
}
