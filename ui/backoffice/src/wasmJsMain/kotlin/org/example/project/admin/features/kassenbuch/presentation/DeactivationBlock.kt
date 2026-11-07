package org.example.project.admin.features.kassenbuch.presentation

/**
 * Why a cash book may not be deactivated right now.
 *
 * The two reasons are the client half of `CashBookServiceImpl.requireDeactivatable` (CLAUDE.md
 * rule 8), and they are kept apart rather than collapsed into one boolean because the operator's
 * next move differs: [FISCAL] is permanent and there is nothing to do about it, while
 * [NON_ZERO_BALANCE] is cleared by transferring the remaining cash out of the book first.
 */
enum class DeactivationBlock {
    /** The fiscal Ladenkasse. A shop must always have one, so it can never be deactivated. */
    FISCAL,

    /**
     * The book still holds cash. Deactivating it would strand that balance in a ledger daily
     * operation no longer shows.
     */
    NON_ZERO_BALANCE,
}
