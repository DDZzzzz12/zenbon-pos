package org.example.project.features.allocation.data.service

import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.allocation.domain.repository.AllocationRepository
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoice

/**
 * The only supported way to add or remove an allocation.
 *
 * An allocation and the two residuals it changes are written **together or not at all**. A caller
 * able to write one without the other would drift, and a drifted residual is money reported wrong —
 * that pairing is the whole reason this class exists, and why nothing else should call
 * [AllocationRepository.create] or [AllocationRepository.delete].
 *
 * Both residuals are **recomputed** from the surviving allocation rows, never adjusted by a delta.
 * Delta arithmetic is faster and drifts the moment any other path touches a row; a recompute cannot.
 *
 * **Requires an ambient transaction** and never opens its own, so the allocation and the residuals
 * commit as one. Nothing here writes to `kassenbuch_header`: those rows are TSE-signed and lockable,
 * and a cash-book entry is a *source*, read only.
 */
class AllocationWriter(
    private val repository: AllocationRepository,
) {
    /** Writes [allocation], then recomputes the residual on each side that has one. */
    fun allocate(allocation: NewAllocation): PaymentAllocation {
        requireSourceId(allocation)
        val written = repository.create(allocation)
        recomputeSourceResidual(allocation.sourceKind, allocation.bankTransactionId)
        recomputeTargetResidual(allocation.targetKind, allocation.invoiceId, allocation.contractInstalmentId, allocation.salesInvoiceId)
        return written
    }

    /**
     * Removes the allocation and restores both residuals. A no-op when [allocationId] matches
     * nothing — there is then nothing whose removal could have changed a residual.
     */
    fun remove(allocationId: Long) {
        val existing = repository.findById(allocationId) ?: return
        val sourceKind = AllocationSourceKind.valueOf(existing.sourceKind)
        val targetKind = AllocationTargetKind.valueOf(existing.targetKind)
        val bankTransactionId = existing.bankTransaction?.id?.value
        val invoiceId = existing.invoice?.id?.value
        // Read off the stored row, not a request: reversal is the only path that learns which
        // target a residual has to be restored on from the allocation itself.
        val contractInstalmentId = existing.contractInstalment?.id?.value
        val salesInvoiceId = existing.salesInvoice?.id?.value

        repository.delete(allocationId)

        recomputeSourceResidual(sourceKind, bankTransactionId)
        recomputeTargetResidual(targetKind, invoiceId, contractInstalmentId, salesInvoiceId)
    }

    /**
     * A cash-book source has no residual to keep: `kassenbuch_header` carries no `open_amount`, and
     * it is never written to anyway. The bank side is the only source with a queue behind it.
     */
    private fun recomputeSourceResidual(
        kind: AllocationSourceKind,
        bankTransactionId: Long?,
    ) {
        when (kind) {
            AllocationSourceKind.BANK_TRANSACTION -> bankTransactionId?.let { recomputeBankTransaction(it) }
            AllocationSourceKind.CASH_BOOK_ENTRY -> Unit

            // A till receipt is a clearing Bill, not a transaction with a residual behind it.
            AllocationSourceKind.TILL_RECEIPT -> Unit
        }
    }

    /**
     * An invoice, a contract instalment and an outgoing invoice each carry a residual, computed the
     * same way from the same surviving rows. The remaining targets are terminal — a fee or an
     * undocumented expense is settled by definition the moment it is booked.
     */
    private fun recomputeTargetResidual(
        kind: AllocationTargetKind,
        invoiceId: Long?,
        contractInstalmentId: Long?,
        salesInvoiceId: Long?,
    ) {
        when (kind) {
            AllocationTargetKind.INVOICE -> invoiceId?.let { recomputeInvoice(it) }
            AllocationTargetKind.CONTRACT_INSTALMENT -> contractInstalmentId?.let { recomputeInstalment(it) }
            AllocationTargetKind.SALES_INVOICE -> salesInvoiceId?.let { recomputeSalesInvoice(it) }
            AllocationTargetKind.CASH_TRANSIT,
            AllocationTargetKind.INTERNAL_TRANSFER,
            AllocationTargetKind.EXPENSE_NO_DOC,
            AllocationTargetKind.FEE,
            -> Unit
        }
    }

    private fun recomputeBankTransaction(transactionId: Long) {
        val bankTransaction = BankStatementTransaction.findById(transactionId) ?: return
        bankTransaction.openAmount =
            residualOf(bankTransaction.amount, repository.allocatedAmountsForBankTransaction(transactionId))
    }

    /** `amount_due` wins over the gross: it is what the supplier actually asks to be paid. */
    private fun recomputeInvoice(invoiceId: Long) {
        val invoice = Invoice.findById(invoiceId) ?: return
        invoice.openAmount =
            residualOf(
                invoice.amountDue ?: invoice.taxInclusiveTotal,
                repository.allocatedAmountsForInvoice(invoiceId),
            )
    }

    /** The AR mirror of [recomputeInvoice]: `amount_due` wins over the gross here too. */
    private fun recomputeSalesInvoice(salesInvoiceId: Long) {
        val invoice = SalesInvoice.findById(salesInvoiceId) ?: return
        invoice.openAmount =
            residualOf(
                invoice.amountDue ?: invoice.taxInclusiveTotal,
                repository.allocatedAmountsForSalesInvoice(salesInvoiceId),
            )
    }

    /**
     * The base is `expected_amount`, and an instalment without one keeps a **null** residual:
     * "nothing is expected" is not "nothing is owed", and a zero here would render it settled.
     */
    private fun recomputeInstalment(instalmentId: Long) {
        val instalment = ContractInstalment.findById(instalmentId) ?: return
        instalment.openAmount =
            residualOf(instalment.expectedAmount, repository.allocatedAmountsForInstalment(instalmentId))
    }

    /**
     * Fail fast: an allocation whose declared source carries no id would write a row whose money came
     * from nowhere, and whose residual nothing would ever recompute.
     */
    private fun requireSourceId(allocation: NewAllocation) {
        when (allocation.sourceKind) {
            AllocationSourceKind.BANK_TRANSACTION ->
                require(allocation.bankTransactionId != null) { "BANK_TRANSACTION needs a bankTransactionId" }
            AllocationSourceKind.CASH_BOOK_ENTRY ->
                require(allocation.kassenbuchHeaderId != null) { "CASH_BOOK_ENTRY needs a kassenbuchHeaderId" }
            AllocationSourceKind.TILL_RECEIPT ->
                require(allocation.billId != null) { "TILL_RECEIPT needs a billId" }
        }
    }
}
