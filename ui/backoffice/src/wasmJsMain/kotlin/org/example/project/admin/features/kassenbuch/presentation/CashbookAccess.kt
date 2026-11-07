package org.example.project.admin.features.kassenbuch.presentation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Which of the Kassenbuch write actions the signed-in principal may be offered (FO-03, FO-05).
 *
 * - [mayPost] — posting an entry, correcting it, linking a document, transfers and managing the
 *   books are `CASHBOOK_WRITE` in `CashBookRules`.
 * - [mayClose] — the Kassensturz (`POST /api/v1/kassenbuch/books/{id}/close`) is the RESERVED
 *   `CASHBOOK_CLOSE`, deliberately **not** `CASHBOOK_WRITE`: an accountant may post but not close.
 */
data class CashbookAccess(
    val mayPost: Boolean,
    val mayClose: Boolean,
) {
    companion object {
        fun of(policy: AccessPolicy): CashbookAccess =
            CashbookAccess(
                mayPost = policy.allows(Capability.CASHBOOK_WRITE),
                mayClose = policy.allows(Capability.CASHBOOK_CLOSE),
            )
    }
}
