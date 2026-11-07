package org.example.project.features.contract.data.service

import kotlinx.datetime.LocalDate
import org.example.project.features.contract.domain.models.ContractAlertKind
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import java.math.BigDecimal

/**
 * Everything one contract is currently complaining about.
 *
 * Derived on read, never stored, exactly like the instalment status and both residuals: an alert is
 * a statement about how things stand right now, so it disappears by itself when the situation is
 * resolved and there is nothing for an operator to tick off.
 *
 * Pure — plain values in, plain values out, no Exposed and no clock: [asOf] is an argument, so every
 * case is a few lines of test.
 *
 * One instalment can raise more than one alert; they answer different questions and suppressing the
 * second would hide half the problem.
 */
fun deriveAlerts(
    rules: ContractAlertRules,
    instalments: List<InstalmentFacts>,
    asOf: LocalDate,
): List<ContractAlert> = instalments.flatMap { alertsFor(rules, it, asOf) }

/**
 * The rules for a single instalment, in the order they are asked:
 * 1. a stored `WAIVED` raises nothing at all — that is what waiving means;
 * 2. past due with a residual still above zero is a missing payment;
 * 3. past due with no invoice is a missing invoice, but only when the contract expects one;
 * 4. an actual amount outside the band is a changed price.
 */
private fun alertsFor(
    rules: ContractAlertRules,
    instalment: InstalmentFacts,
    asOf: LocalDate,
): List<ContractAlert> {
    if (instalment.storedStatus == ContractInstalmentStatus.WAIVED.name) return emptyList()

    val alerts = mutableListOf<ContractAlert>()
    val pastDue = instalment.dueDate < asOf

    if (pastDue && instalment.isUnsettled()) {
        alerts += ContractAlert(ContractAlertKind.PAYMENT_MISSING, instalment.id, instalment.dueDate)
    }
    if (pastDue && rules.expectsInvoice && !instalment.hasInvoice) {
        alerts += ContractAlert(ContractAlertKind.INVOICE_MISSING, instalment.id, instalment.dueDate)
    }
    offBandAlert(rules, instalment)?.let { alerts += it }
    return alerts
}

/**
 * `null` means nothing has touched the instalment yet, which is money that has not gone out — the
 * same reading [deriveInstalmentStatus] gives it.
 */
private fun InstalmentFacts.isUnsettled(): Boolean = openAmount == null || openAmount > BigDecimal.ZERO

/**
 * Whether the price changed behind our back.
 *
 * With no actual amount there is nothing to answer with, so this stays silent: an instalment with no
 * invoice yet and nothing allocated may well be late, but it is not answerable on amount.
 */
private fun offBandAlert(
    rules: ContractAlertRules,
    instalment: InstalmentFacts,
): ContractAlert? {
    val actual = actualAmount(rules, instalment) ?: return null
    val expected = instalment.expectedAmount ?: rules.expectedAmount ?: return null
    val within =
        isWithinTolerance(
            expected = expected,
            actual = actual,
            tolerancePct = rules.tolerancePct,
            toleranceAbs = rules.toleranceAbs,
        )
    if (within) return null
    return ContractAlert(
        kind = ContractAlertKind.AMOUNT_OFF_BAND,
        instalmentId = instalment.id,
        dueDate = instalment.dueDate,
        expectedAmount = expected,
        actualAmount = actual,
    )
}

/**
 * What the obligation actually cost, which differs by contract kind:
 * - **invoice-bearing**: the invoice's total. That is what the supplier asked for, and it is what
 *   "89,00 became 129,00" refers to; comparing the *paid* amount would stay silent until the direct
 *   debit cleared.
 * - **invoice-less**: what was actually allocated, because there is no document and the payment is
 *   the only evidence of what it cost. A zero allocation is no evidence at all, so it reads as
 *   nothing rather than as an amount of zero — otherwise every unpaid rent instalment would be off
 *   band the day it was generated.
 */
private fun actualAmount(
    rules: ContractAlertRules,
    instalment: InstalmentFacts,
): BigDecimal? =
    if (rules.expectsInvoice) {
        instalment.invoiceTotal
    } else {
        instalment.allocatedTotal?.takeIf { it.signum() != 0 }
    }
