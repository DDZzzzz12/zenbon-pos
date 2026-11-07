package org.example.project.features.matching.data.service

import kotlinx.datetime.daysUntil
import org.example.project.features.supplier.data.service.normalizeSupplierName
import java.math.BigDecimal

/**
 * The AR (case-C) ladder: which STANDALONE invoices a **credit** payment probably settles.
 *
 * The mirror of [InvoiceMatchRanker], and deliberately a sibling rather than a parameterised twin:
 * the AP ladder matches debits against supplier invoices, the AR ladder matches credits against
 * debtor invoices, and the two candidate types differ (`supplierId` vs `debtorId`). The shared
 * machinery — [normalizeInvoiceReference], [MatchSuggestion], the eligibility guard — is reused, not
 * copied.
 *
 * Parent-spec §3 promises two advantages this ladder exploits:
 *
 * - **We choose the reference.** The invoice number is printed as the Verwendungszweck and written
 *   to `sales_invoice.payment_reference`, so signals 1–2 hit far more often than on the supplier
 *   side, where we read whatever the supplier wrote.
 * - **A direction guard costs nothing.** [isEligible] with `direction = CREDIT` admits credits and
 *   rejects debits, so a €340 supplier invoice and a €340 customer invoice never compete for the
 *   same transaction row.
 *
 * Signals implemented: **1** structured reference, **2** remittance text, **6′** debtor name +
 * amount + date proximity, **7** subset sum — the four that work with our own references and the
 * debtor master data. Signals 3/5 are contract-facing (no AR contracts), 4 needs a counterparty
 * IBAN→debtor resolution (debtors have no bank accounts in the model), so both are absent by
 * design.
 *
 * Pure: no database, no HTTP, no clock. The caller loads the candidates and the debtor name keys.
 */
object SalesInvoiceMatchRanker {
    private const val SUBSET_CANDIDATE_LIMIT = 20
    private const val SUBSET_MAX_SIZE = 4
    private const val MIN_TEXT_KEY_LENGTH = 3
    private const val MAX_TOKEN_WINDOW = 4

    /**
     * The STANDALONE invoices [transaction] might settle, strongest first.
     *
     * @param candidates open case-C invoices, already narrowed by the open-sales-invoice predicate.
     * @param debtorNormalizedNames each candidate's debtor name, keyed by debtor id, for signal 6′.
     *   A candidate whose debtor is absent from the map — including every one-off guest — has no key
     *   to compare against and is skipped by that signal.
     */
    fun rank(
        transaction: TransactionFacts,
        candidates: List<SalesInvoiceCandidate>,
        debtorNormalizedNames: Map<Long, String>,
    ): List<MatchSuggestion> {
        // The guards are a filter, not a weak signal — re-asserted here so a caller that forgets
        // them cannot get a suggestion anyway.
        if (!isEligible(transaction, direction = CREDIT)) return emptyList()

        val suggestions =
            structuredReferenceMatches(transaction, candidates) +
                remittanceTextMatches(transaction, candidates) +
                debtorNameAmountDateMatches(transaction, candidates, debtorNormalizedNames) +
                subsetSumMatches(transaction, candidates)

        return suggestions
            .distinctByBestStrength()
            .sortedBy { it.strength.ordinal }
    }

    /** Signal 1: the payer's machine-readable reference is the invoice's own identifier. */
    private fun structuredReferenceMatches(
        transaction: TransactionFacts,
        candidates: List<SalesInvoiceCandidate>,
    ): List<MatchSuggestion> {
        val paidKeys =
            setOfNotNull(
                normalizeInvoiceReference(transaction.remittanceStructuredRef),
                normalizeInvoiceReference(transaction.endToEndId),
            )
        if (paidKeys.isEmpty()) return emptyList()

        return candidates
            .filter { candidate -> referenceKeysOf(candidate).any { it in paidKeys } }
            .map { it.toSuggestion(transaction, MatchSignal.STRUCTURED_REFERENCE, MatchStrength.EXACT) }
    }

    /** Signal 2: a human typed the invoice number into the transfer text. */
    private fun remittanceTextMatches(
        transaction: TransactionFacts,
        candidates: List<SalesInvoiceCandidate>,
    ): List<MatchSuggestion> {
        val textKeys = remittanceKeys(transaction.remittanceUnstructured)
        if (textKeys.isEmpty()) return emptyList()

        return candidates
            .filter { candidate ->
                referenceKeysOf(candidate).any { it.length >= MIN_TEXT_KEY_LENGTH && it in textKeys }
            }.map { it.toSuggestion(transaction, MatchSignal.REMITTANCE_TEXT, MatchStrength.EXACT) }
    }

    /**
     * Signal 6′: the counterparty name agrees with the debtor's after normalisation, the amount is
     * the open amount exactly, and the payment landed near the due date. The AR analogue of
     * `supplierNameSuggestions`; `MEDIUM`, and never more — all three legs are circumstantial.
     */
    private fun debtorNameAmountDateMatches(
        transaction: TransactionFacts,
        candidates: List<SalesInvoiceCandidate>,
        debtorNormalizedNames: Map<Long, String>,
    ): List<MatchSuggestion> {
        val paidByKey = transaction.counterpartyName?.takeIf { it.isNotBlank() }?.let { normalizeSupplierName(it) }
        if (paidByKey.isNullOrBlank()) return emptyList()

        return candidates
            .filter { it.agreesOnName(paidByKey, debtorNormalizedNames) }
            .filter { it.agreesOnAmount(transaction) && it.isNearDueDate(transaction.valueDate) }
            .map { candidate ->
                MatchSuggestion(
                    invoiceIds = listOf(candidate.id),
                    signal = MatchSignal.DEBTOR_NAME_AMOUNT_DATE,
                    strength = MatchStrength.MEDIUM,
                    amount = transaction.amount,
                )
            }
    }

    /**
     * Signal 7: several of this debtor's open invoices paid in one transfer. Mirrors
     * [InvoiceMatchRanker.subsetSumMatches] exactly — same cap, same `BigDecimal` comparison.
     */
    private fun subsetSumMatches(
        transaction: TransactionFacts,
        candidates: List<SalesInvoiceCandidate>,
    ): List<MatchSuggestion> {
        val withAmounts = candidates.filter { it.openAmount != null }
        if (withAmounts.size < 2 || withAmounts.size > SUBSET_CANDIDATE_LIMIT) return emptyList()

        return subsetsUpTo(withAmounts, SUBSET_MAX_SIZE)
            .filter { subset -> subset.sumOfOpenAmounts().compareTo(transaction.amount) == 0 }
            .map { subset ->
                MatchSuggestion(
                    invoiceIds = subset.map { it.id },
                    signal = MatchSignal.SUBSET_SUM,
                    strength = MatchStrength.MEDIUM,
                    amount = transaction.amount,
                )
            }
    }

    /** Every combination of [source] of size 2..[maxSize], each keeping the input order. */
    private fun subsetsUpTo(
        source: List<SalesInvoiceCandidate>,
        maxSize: Int,
    ): List<List<SalesInvoiceCandidate>> {
        val found = mutableListOf<List<SalesInvoiceCandidate>>()

        fun build(
            startIndex: Int,
            current: MutableList<SalesInvoiceCandidate>,
        ) {
            if (current.size >= 2) found.add(current.toList())
            if (current.size == maxSize) return
            for (index in startIndex until source.size) {
                current.add(source[index])
                build(index + 1, current)
                current.removeAt(current.size - 1)
            }
        }

        build(0, mutableListOf())
        return found
    }

    private fun List<SalesInvoiceCandidate>.sumOfOpenAmounts(): BigDecimal =
        fold(BigDecimal.ZERO) { total, candidate -> total + (candidate.openAmount ?: BigDecimal.ZERO) }

    /** The identifiers an invoice can be referred to by — our own reference, so it hits more often. */
    private fun referenceKeysOf(candidate: SalesInvoiceCandidate): Set<String> =
        setOfNotNull(
            normalizeInvoiceReference(candidate.invoiceNumber),
            normalizeInvoiceReference(candidate.paymentReference),
        )

    /** Normalised keys for every run of up to [MAX_TOKEN_WINDOW] consecutive words in the text. */
    private fun remittanceKeys(text: String?): Set<String> {
        val words = text?.split(' ', '\t', '\n', '\r')?.filter { it.isNotBlank() }.orEmpty()
        if (words.isEmpty()) return emptySet()

        val keys = mutableSetOf<String>()
        for (start in words.indices) {
            val last = minOf(start + MAX_TOKEN_WINDOW, words.size)
            for (end in start + 1..last) {
                normalizeInvoiceReference(words.subList(start, end).joinToString(" "))?.let { keys.add(it) }
            }
        }
        return keys
    }

    /** One suggestion per invoice set, kept at its strongest reading (ties broken by ladder order). */
    private fun List<MatchSuggestion>.distinctByBestStrength(): List<MatchSuggestion> =
        groupBy { it.invoiceIds }
            .values
            .map { group -> group.minWith(compareBy({ it.strength.ordinal }, { it.signal.ordinal })) }

    private fun SalesInvoiceCandidate.agreesOnName(
        paidByKey: String,
        debtorNormalizedNames: Map<Long, String>,
    ): Boolean {
        val storedKey = debtorId?.let { debtorNormalizedNames[it] } ?: return false
        return storedKey.isNotBlank() && storedKey == paidByKey
    }

    private fun SalesInvoiceCandidate.agreesOnAmount(transaction: TransactionFacts): Boolean {
        val open = openAmount ?: return false
        return open.compareTo(transaction.amount) == 0
    }

    private fun SalesInvoiceCandidate.isNearDueDate(valueDate: kotlinx.datetime.LocalDate): Boolean {
        val due = dueDate ?: return false
        return kotlin.math.abs(due.daysUntil(valueDate)) <= 10
    }

    /** What this payment would settle against one invoice: the payment, or its residual if smaller. */
    private fun SalesInvoiceCandidate.toSuggestion(
        transaction: TransactionFacts,
        signal: MatchSignal,
        strength: MatchStrength,
    ): MatchSuggestion =
        MatchSuggestion(
            invoiceIds = listOf(id),
            signal = signal,
            strength = strength,
            amount = openAmount?.let { minOf(it, transaction.amount) } ?: transaction.amount,
        )
}
