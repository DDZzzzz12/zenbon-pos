package org.example.project.features.contract.data.service

import kotlinx.datetime.LocalDate
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import java.math.BigDecimal

/**
 * The status an instalment reports, derived from what is stored about it.
 *
 * `contract_instalment.status` holds only what a human decides — `OPEN`, the generator's default,
 * and `WAIVED`. `PAID`, `PARTIAL` and `OVERDUE` are computed here from the residual and the due
 * date. A stored `PAID` would be a second copy of what `open_amount` already says, and the two drift
 * the moment anything touches an allocation by another path; a stored `OVERDUE` would be wrong from
 * the moment the date passes until something runs. One derivation, one truth.
 *
 * Pure, and the clock is an argument — [asOf] is passed in so every case is a two-line test.
 *
 * The order of the guard clauses *is* the specification:
 * 1. a stored `WAIVED` outranks everything — it is a human decision;
 * 2. a residual at or below zero is `PAID`, even long past its due date;
 * 3. anything still owed past its due date is `OVERDUE`, which is the more urgent fact;
 * 4. a residual strictly between zero and [expectedAmount] is `PARTIAL`;
 * 5. otherwise `OPEN` — including a null residual, which means nothing has touched it yet.
 *
 * Comparisons use `compareTo`, never `equals`: `BigDecimal("0.00") != BigDecimal("0.0000")`.
 */
fun deriveInstalmentStatus(
    stored: String,
    dueDate: LocalDate,
    openAmount: BigDecimal?,
    expectedAmount: BigDecimal?,
    asOf: LocalDate,
): ContractInstalmentStatus {
    if (stored == ContractInstalmentStatus.WAIVED.name) return ContractInstalmentStatus.WAIVED
    if (openAmount != null && openAmount <= BigDecimal.ZERO) return ContractInstalmentStatus.PAID
    if (dueDate < asOf) return ContractInstalmentStatus.OVERDUE
    // With nothing expected there is no fraction to be part of, so an untouched or unbounded
    // instalment stays OPEN rather than being called PARTIAL.
    if (openAmount != null && expectedAmount != null && openAmount < expectedAmount) {
        return ContractInstalmentStatus.PARTIAL
    }
    return ContractInstalmentStatus.OPEN
}
