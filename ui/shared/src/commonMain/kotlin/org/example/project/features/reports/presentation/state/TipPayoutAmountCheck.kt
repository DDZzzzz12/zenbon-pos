package org.example.project.features.reports.presentation.state

import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.money.Decimal

/**
 * The till's half of the tip-payout amount guard (CLAUDE.md rule 8); the server re-checks the same
 * rules under its Kassenbuch lock (`EmployeeTipPayoutBooking`).
 *
 * The amount the operator typed is valid when it is a decimal (comma or dot), greater than zero,
 * has at most two places — cash is handed out in cents — and does not exceed what is still open.
 */
enum class TipPayoutAmountCheck {
    VALID,

    /** Not a decimal, zero or negative, or more than two places. */
    INVALID,

    /** More than the employee's open tip. */
    EXCEEDS_REMAINING,
    ;

    companion object {
        /** Classifies [typed] against [remaining] (a server decimal string). */
        fun of(
            typed: String,
            remaining: String,
        ): TipPayoutAmountCheck {
            val amount = typed.toUserInputDecimalOrNull() ?: return INVALID
            if (amount <= Decimal.ZERO) return INVALID
            if (amount.setScale(2) != amount) return INVALID
            val open = Decimal.parseOrNull(remaining) ?: return INVALID
            return if (amount > open) EXCEEDS_REMAINING else VALID
        }
    }
}
