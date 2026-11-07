package org.example.project.core.domain.enums.document

import kotlinx.serialization.Serializable

/**
 * A booking field an operator may explicitly clear.
 *
 * `null` on a booking request means "leave unchanged", never "clear" — that is a deliberate
 * contract, so that a partial edit is structurally incapable of wiping a field the caller did not
 * mention. Clearing therefore needs to be *said*, and this enum is how it is said.
 *
 * Typed rather than a list of strings: both ends of the wire get compile-time checking, no string
 * validation is needed on the server, and the set of clearable fields is self-documenting. A
 * misspelled string would otherwise be indistinguishable from "clear nothing".
 *
 * `paymentDunningBlock` is deliberately absent. Its column is `NOT NULL DEFAULT false`, so "clear"
 * and "set to false" are the same operation and the value itself already expresses it — a caller
 * sends `paymentDunningBlock = false`, which is non-null and therefore written. Adding it here
 * would offer two ways to say one thing.
 *
 * Adding a constant is safe. **Removing or renaming one is not**: an older client's stored request
 * would fail to deserialize. Nothing persists these values today, but the wire is shared.
 */
@Serializable
enum class ClearableBookingField {
    ACCOUNT_NO,
    BP_ACCOUNT_NO,
    BU_CODE,
    BOOKING_TEXT,
    COST_CATEGORY_ID,
    COST_CATEGORY_ID_2,
    COST_AMOUNT,
    TYPE_OF_RECEIVABLE,
    PAID_AT,
}
