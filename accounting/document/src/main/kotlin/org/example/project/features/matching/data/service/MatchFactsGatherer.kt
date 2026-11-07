package org.example.project.features.matching.data.service

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.features.allocation.domain.repository.AllocationQueueRepository
import org.example.project.features.contract.database.tables.ContractInstalmentTable
import org.example.project.features.contract.database.tables.ContractTable
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.supplier.data.service.normalizeSupplierName
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal

/**
 * Turns one bank transaction id into the facts and candidates the pure rankers consume.
 *
 * This is the only part of the match ladder that touches the database. Everything below it —
 * [InvoiceMatchRanker], [ContractMatchRanker], [supplierNameSuggestions], [isEligible] — is a
 * function over values, and stays that way because the loading happens here.
 *
 * **Requires an ambient transaction** and never opens one, like every repository in this feature;
 * the service opens it. The Buchungsbeginn must be resolved *before* that transaction is opened —
 * `BookkeepingStartService.cutoff()` opens one of its own, and nesting is forbidden — which is why
 * [gather] takes it as a parameter rather than reaching for the service.
 *
 * ## Six queries, whatever the row counts
 *
 * 1. the transaction joined to its statement (the statement is where `bank_account_id` lives),
 * 2. the counterparty IBAN's supplier — skipped when the transaction carries no IBAN,
 * 3. the supplier whose recorded `sepa_creditor_id` is the transaction's — skipped when there is none,
 * 4. the open invoices, through [AllocationQueueRepository],
 * 5. those invoices' suppliers' normalised names — skipped when no candidate names a supplier,
 * 6. the settleable instalments joined to their contracts.
 *
 * Not one of them is per row. Ids come off `readValues` rather than through an `optionalReferencedOn`
 * accessor, which would materialise the whole referenced entity once per candidate and turn this
 * into the N+1 this branch has already fixed three times.
 */
class MatchFactsGatherer(
    private val queueRepository: AllocationQueueRepository,
) {
    /**
     * The facts for [transactionId], or null when no such transaction exists — which the endpoint
     * above turns into a 404. An *ineligible* transaction is not null: it is gathered like any
     * other, and [isEligible] is what silences it, so the caller can tell "no such row" apart from
     * "nothing to suggest".
     *
     * @param bookedFrom the Buchungsbeginn, already resolved by the caller. Null means no cut-off is
     *   set and nothing is filtered on that ground.
     */
    fun gather(
        transactionId: Long,
        bookedFrom: LocalDate?,
    ): MatchFacts? {
        val row = transactionRow(transactionId) ?: return null
        val transaction = row.toTransactionFacts()

        val invoiceCandidates = eligibleCandidates(openInvoiceCandidates(bookedFrom), bookedFrom)
        val salesInvoiceCandidates = openSalesInvoiceCandidates(bookedFrom)
        val contractRows = settleableInstalmentRows()

        return MatchFacts(
            transaction = transaction,
            currency = row[BankStatementTransactionTable.currency],
            counterpartySupplierId = supplierForIban(transaction.counterpartyIban),
            sepaCreditorSupplierId = supplierForCreditorId(transaction.creditorId),
            invoiceCandidates = invoiceCandidates,
            contractCandidates = contractRows.map { it.toContractCandidate() },
            supplierNormalizedNames = normalizedNamesFor(invoiceCandidates.mapNotNull { it.supplierId }),
            contractNames = contractRows.associate { it[ContractTable.id].value to it[ContractTable.name] },
            salesInvoiceCandidates = salesInvoiceCandidates,
            debtorNormalizedNames = debtorNormalizedNamesFor(salesInvoiceCandidates),
        )
    }

    private fun transactionRow(transactionId: Long): ResultRow? =
        BankStatementTransactionTable
            .innerJoin(BankStatementTable)
            .selectAll()
            .where { BankStatementTransactionTable.id eq transactionId }
            .firstOrNull()

    /**
     * `hasBankAccount` is read off the joined statement rather than through the `bankAccount`
     * accessor: only its presence matters, and the accessor would load the whole account row.
     */
    private fun ResultRow.toTransactionFacts(): TransactionFacts =
        TransactionFacts(
            id = this[BankStatementTransactionTable.id].value,
            valueDate = this[BankStatementTransactionTable.valueDate].date,
            amount = this[BankStatementTransactionTable.amount],
            creditDebit = this[BankStatementTransactionTable.creditDebit],
            counterpartyName = this[BankStatementTransactionTable.counterpartyName],
            counterpartyIban = this[BankStatementTransactionTable.counterpartyIban],
            remittanceUnstructured = this[BankStatementTransactionTable.remittanceUnstructured],
            remittanceStructuredRef = this[BankStatementTransactionTable.remittanceStructuredRef],
            endToEndId = this[BankStatementTransactionTable.endToEndId],
            accountServicerReference = this[BankStatementTransactionTable.accountServicerReference],
            mandateId = this[BankStatementTransactionTable.mandateId],
            creditorId = this[BankStatementTransactionTable.creditorId],
            reversalIndicator = this[BankStatementTransactionTable.reversalIndicator],
            hasBankAccount = this[BankStatementTable.bankAccountId] != null,
            // V91's `duplicate_of_transaction_id`, written by StatementDuplicateMarker whenever an
            // overlapping statement re-delivers a transaction we already hold. Hardcoded `false`
            // until the banking merge landed the column, which left MatchEligibility's duplicate
            // guard as dead code and let a re-imported payment be suggested against — and paid
            // onto — an invoice it had already settled.
            isDuplicate = this[BankStatementTransactionTable.duplicateOfTransactionId] != null,
        )

    /**
     * Who was paid. Matched exactly, as `SupplierRepository.findPartnerByIban` does — one query,
     * id only.
     *
     * The account is resolved to its **party** and the party to its creditor role: an IBAN belongs
     * to the company, not to the creditor role, so `supplier_bank_account` hangs off the party.
     * There is no `supplier_bank_account.supplier_id` to read instead — the join through
     * `supplier.partner_id` is the only route from an IBAN to a creditor.
     */
    private fun supplierForIban(iban: String?): Long? {
        val key = iban?.takeIf { it.isNotBlank() } ?: return null
        return SupplierTable
            .innerJoin(
                SupplierBankAccountTable,
                { SupplierTable.partnerId },
                { SupplierBankAccountTable.partnerId },
            ).select(SupplierTable.id)
            .where { SupplierBankAccountTable.iban eq key }
            .firstOrNull()
            ?.get(SupplierTable.id)
            ?.value
    }

    /**
     * Who is collecting under a mandate — a **different** question from [supplierForIban], answered
     * from what an operator recorded rather than from where the money went. Null on every
     * installation until somebody fills the column in, and signal 3 is correctly silent until then.
     */
    private fun supplierForCreditorId(creditorId: String?): Long? {
        val key = creditorId?.takeIf { it.isNotBlank() } ?: return null
        return SupplierTable
            .select(SupplierTable.id)
            .where { SupplierTable.sepaCreditorId eq key }
            .firstOrNull()
            ?.get(SupplierTable.id)
            ?.value
    }

    /**
     * The open invoices, mapped to values.
     *
     * `openAmount ?: amountDue ?: taxInclusiveTotal` is the same coalesce the queue and the writer
     * use: a NULL `open_amount` means no residual has been **computed**, not that nothing is owed,
     * and reading it as zero would drop every invoice imported since the last back-fill.
     */
    private fun openInvoiceCandidates(bookedFrom: LocalDate?): List<InvoiceCandidate> =
        queueRepository
            .openInvoices(
                supplierId = null,
                overdueAsOf = null,
                bookedFrom = bookedFrom?.atTime(0, 0),
                limit = OPEN_INVOICE_LIMIT,
                offset = 0,
            ).map { invoice ->
                InvoiceCandidate(
                    id = invoice.id.value,
                    invoiceNumber = invoice.invoiceNumber,
                    paymentReference = invoice.paymentReference,
                    supplierId = invoice.readValues[InvoiceTable.supplierId]?.value,
                    issueDate = invoice.issueDate?.date,
                    openAmount = invoice.effectiveOpenAmount(),
                    dueDate = invoice.dueDate?.date,
                )
            }

    private fun Invoice.effectiveOpenAmount(): BigDecimal? = openAmount ?: amountDue ?: taxInclusiveTotal

    /**
     * The open case-C invoices, mapped to values — the CREDIT ladder's candidates.
     *
     * The same coalesce as [openInvoiceCandidates]: a NULL `open_amount` on a freshly issued
     * STANDALONE invoice means nobody has computed a residual yet, not that nothing is owed.
     */
    private fun openSalesInvoiceCandidates(bookedFrom: LocalDate?): List<SalesInvoiceCandidate> =
        queueRepository
            .openSalesInvoices(
                debtorId = null,
                overdueAsOf = null,
                bookedFrom = bookedFrom?.atTime(0, 0),
                limit = OPEN_INVOICE_LIMIT,
                offset = 0,
            ).map { invoice ->
                SalesInvoiceCandidate(
                    id = invoice.id.value,
                    invoiceNumber = invoice.invoiceNumber,
                    paymentReference = invoice.paymentReference,
                    debtorId = invoice.readValues[SalesInvoiceTable.debtorId]?.value,
                    issueDate = invoice.issueDate.date,
                    openAmount = invoice.openAmount ?: invoice.amountDue ?: invoice.taxInclusiveTotal,
                    dueDate = invoice.dueDate?.date,
                )
            }

    /**
     * Each candidate debtor's name, normalised on the fly for signal 6′.
     *
     * The name is the **party's**, read through the debtor role's join: identity lives on the
     * company, and since the creditor role carries no copy of its own the join is the only way there.
     * The same normaliser is still applied on the fly — the party does store a `normalized_name`,
     * but leaving the normalisation here keeps this signal reading the displayed name, which is
     * what an operator compares against.
     *
     * A candidate with no debtor id (a one-off guest) is absent from the map and skipped by that
     * signal, which is correct: there is no record to agree with.
     */
    private fun debtorNormalizedNamesFor(candidates: List<SalesInvoiceCandidate>): Map<Long, String> =
        candidates
            .mapNotNull { it.debtorId }
            .distinct()
            .associateWith { debtorId ->
                DebtorTable
                    .innerJoin(BusinessPartnerTable)
                    .select(BusinessPartnerTable.name)
                    .where { DebtorTable.id eq debtorId }
                    .firstOrNull()
                    ?.get(BusinessPartnerTable.name)
                    .orEmpty()
                    .let { normalizeSupplierName(it) }
            }.filterValues { it.isNotBlank() }

    /**
     * The instalments of contracts an operator has left switched on, newest due date first.
     *
     * A null `open_amount` is kept: nothing writes one until an allocation is made, so treating it
     * as settled would silence the contract signals entirely. [ContractMatchRanker] reads it the
     * same way.
     */
    private fun settleableInstalmentRows(): List<ResultRow> {
        val settleable =
            ContractInstalmentTable.openAmount.isNull() or
                (ContractInstalmentTable.openAmount greater BigDecimal.ZERO)
        return ContractInstalmentTable
            .innerJoin(ContractTable)
            .selectAll()
            .where { (ContractTable.isActive eq true) and settleable }
            .orderBy(ContractInstalmentTable.dueDate to SortOrder.DESC, ContractInstalmentTable.id to SortOrder.DESC)
            .limit(INSTALMENT_LIMIT)
            .toList()
    }

    private fun ResultRow.toContractCandidate(): ContractCandidate =
        ContractCandidate(
            instalmentId = this[ContractInstalmentTable.id].value,
            contractId = this[ContractTable.id].value,
            supplierId = this[ContractTable.supplierId]?.value,
            sepaMandateId = this[ContractTable.sepaMandateId],
            expectedAmount = this[ContractInstalmentTable.expectedAmount] ?: this[ContractTable.expectedAmount],
            tolerancePct = this[ContractTable.tolerancePct],
            toleranceAbs = this[ContractTable.toleranceAbs],
            dueDate = this[ContractInstalmentTable.dueDate].date,
            openAmount = this[ContractInstalmentTable.openAmount],
        )

    /**
     * One query for every candidate's supplier, not one per candidate.
     *
     * Keyed by supplier id, because that is what the candidates carry, but the name comes off the
     * **party** — the creditor role has no `normalized_name` of its own, so the party's column is
     * the only one left, and reaching it costs the join to `business_partner`.
     */
    private fun normalizedNamesFor(supplierIds: List<Long>): Map<Long, String> {
        val ids = supplierIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return SupplierTable
            .innerJoin(BusinessPartnerTable)
            .select(SupplierTable.id, BusinessPartnerTable.normalizedName)
            .where { SupplierTable.id inList ids }
            .associate { it[SupplierTable.id].value to it[BusinessPartnerTable.normalizedName] }
    }

    private companion object {
        /**
         * How many open invoices one transaction is ranked against.
         *
         * A bound rather than an unbounded read: the endpoint answers a single click, and ranking
         * is a convenience. Five hundred open supplier invoices is already an unusual book, and
         * beyond it the far older ones are not what a payment made today settles — they sort last,
         * because the repository orders by due date ascending with the undated ones after.
         */
        const val OPEN_INVOICE_LIMIT = 500

        /**
         * How many settleable instalments one transaction is ranked against, newest due date first.
         *
         * `contract_instalment.open_amount` stays NULL until something is allocated against it, so
         * every instalment the generator has ever materialised counts as settleable. Newest-first
         * keeps the ones a recent payment could plausibly settle; an older one, unpaid for over
         * five hundred instalments, is a bookkeeping problem rather than a matching one.
         */
        const val INSTALMENT_LIMIT = 500
    }
}
