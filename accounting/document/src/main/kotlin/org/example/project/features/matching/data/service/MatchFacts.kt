package org.example.project.features.matching.data.service

/**
 * Everything one bank transaction needs to be ranked, loaded once and handed to the pure signals.
 *
 * The rankers are functions over values, so somebody has to turn a transaction id into those values.
 * This is what that somebody produces — see [MatchFactsGatherer].
 *
 * **[counterpartySupplierId] and [sepaCreditorSupplierId] answer different questions and are never
 * interchangeable.** The first is who the money went to, resolved from the counterparty IBAN through
 * `supplier_bank_account`. The second is who is collecting under a mandate, resolved from the
 * transaction's `creditor_id` through the supplier's *recorded* `sepa_creditor_id`. Signal 3 earns
 * `EXACT` from the second; feeding it the first would award `EXACT` to evidence that only earns
 * `STRONG`, on every direct debit whose IBAN happened to resolve, with nothing actually recorded.
 * Both are null far more often than not, and that is the correct, quiet outcome.
 *
 * @property invoiceCandidates already narrowed by `eligibleCandidates` — open, and inside the books.
 * @property contractCandidates settleable instalments of active contracts.
 * @property supplierNormalizedNames each candidate supplier's stored `normalized_name`, for signal 6.
 * @property contractNames each candidate instalment's contract name, keyed by contract id, so a
 *   suggestion can be labelled without a second query per row.
 * @property currency the transaction's own currency, which the wire contract reports beside the
 *   amount — money without one is a number, not an amount.
 */
data class MatchFacts(
    val transaction: TransactionFacts,
    val currency: String,
    val counterpartySupplierId: Long?,
    val sepaCreditorSupplierId: Long?,
    val invoiceCandidates: List<InvoiceCandidate>,
    val contractCandidates: List<ContractCandidate>,
    val supplierNormalizedNames: Map<Long, String>,
    val contractNames: Map<Long, String>,
    /** Open case-C invoices, for the CREDIT ladder — the AR mirror of [invoiceCandidates]. */
    val salesInvoiceCandidates: List<SalesInvoiceCandidate>,
    /** Each candidate debtor's normalized name, keyed by debtor id, for signal 6′. */
    val debtorNormalizedNames: Map<Long, String>,
)
