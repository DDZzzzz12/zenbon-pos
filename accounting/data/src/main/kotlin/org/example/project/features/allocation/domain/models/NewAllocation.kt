package org.example.project.features.allocation.domain.models

import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import java.math.BigDecimal

/**
 * One allocation about to be written — *this much of this payment goes to that*.
 *
 * A parameter object rather than sixteen positional arguments, because both sides of an allocation
 * are typed: only the id belonging to the declared [sourceKind] / [targetKind] is ever set, and every
 * other one stays null.
 *
 * [amount] is always **positive**; direction comes from the source, matching ISO 20022 `CdtDbtInd`.
 */
data class NewAllocation(
    val sourceKind: AllocationSourceKind,
    val targetKind: AllocationTargetKind,
    val amount: BigDecimal,
    val matchedBy: AllocationMatchedBy,
    /** Set for [AllocationSourceKind.BANK_TRANSACTION]. */
    val bankTransactionId: Long? = null,
    /** Set for [AllocationSourceKind.CASH_BOOK_ENTRY]. */
    val kassenbuchHeaderId: Long? = null,
    /** Set when [sourceKind] is `TILL_RECEIPT`: the clearing Bill the money arrived on. */
    val billId: Long? = null,
    /** Set for [AllocationTargetKind.INVOICE]. */
    val invoiceId: Long? = null,
    /** Set for [AllocationTargetKind.SALES_INVOICE]. */
    val salesInvoiceId: Long? = null,
    /** Set for [AllocationTargetKind.CONTRACT_INSTALMENT]. */
    val contractInstalmentId: Long? = null,
    /** Set for [AllocationTargetKind.CASH_TRANSIT] — the other half of a Geldtransit. */
    val counterKassenbuchHeaderId: Long? = null,
    /** Set for [AllocationTargetKind.INTERNAL_TRANSFER] — the other own account. */
    val counterBankAccountId: Long? = null,
    /** Carries the booking for a target with no document — [AllocationTargetKind.EXPENSE_NO_DOC], [AllocationTargetKind.FEE]. */
    val accountNo: String? = null,
    val bookingText: String? = null,
    /**
     * The `MatchSignal` name behind an accepted suggestion; null for a manual allocation. Kept apart
     * from [ruleId] on purpose — see [PaymentAllocationTable.matchedSignal].
     */
    val matchedSignal: String? = null,
    val ruleId: Long? = null,
    val confidence: BigDecimal? = null,
    val note: String? = null,
    val createdBy: String? = null,
)
