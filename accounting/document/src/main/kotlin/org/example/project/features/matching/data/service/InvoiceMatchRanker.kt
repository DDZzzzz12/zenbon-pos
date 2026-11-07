package org.example.project.features.matching.data.service

import java.math.BigDecimal

/**
 * Given a payment and the invoices it could plausibly settle, works out which ones it might
 * actually settle — and how confident that is.
 *
 * Pure: no database, no HTTP, no clock. The caller loads the candidates, resolves the counterparty
 * IBAN to a supplier, and applies [eligibleCandidates] beforehand; this object only decides.
 *
 * **The job is not to always produce an answer.** Spec §5: nothing is auto-applied, and two or more
 * matches show as a list with no default selected, because a wrong pre-filled answer costs more
 * than no answer. So every ambiguity here resolves toward saying less — a tie stays two candidates,
 * "single open invoice" means exactly one, and no signal firing returns an empty list, which is a
 * correct outcome rather than a gap.
 *
 * Signals implemented, numbered as in spec §7: **1** structured reference, **2** remittance text,
 * **4** single open invoice of the counterparty's supplier, **7** subset sum. Signals **3** and
 * **5** are contract-facing and belong to slice 5b. Signal **6** (amount + fuzzy supplier name +
 * date proximity) is deliberately absent: it needs `SupplierNameNormalizer` from the supplier
 * feature, and folding that in would drag a dependency into an otherwise self-contained unit. Their
 * absence is a decision, not an oversight.
 */
object InvoiceMatchRanker {
    /**
     * The most open invoices the subset search will look at, and the reason there is a cap at all.
     *
     * Combinations up to size four over 20 invoices is 6 195 sums — instant. Over 200 it is
     * 68 million, and the endpoint stalls. A suggestion is a convenience, so beyond this bound the
     * subset signal is **skipped entirely** rather than computed: a request that never returns is
     * worse than one that stays quiet.
     */
    private const val SUBSET_CANDIDATE_LIMIT = 20

    /**
     * The largest set of invoices one payment is assumed to settle (spec §7's cap).
     *
     * Beyond four, some combination of an operator's open invoices will sum to almost any amount by
     * coincidence, and the arithmetic stops being evidence.
     */
    private const val SUBSET_MAX_SIZE = 4

    /**
     * A normalised key shorter than this is not looked for inside free remittance text.
     *
     * `"1"` or `"12"` occurs in a sentence like *"Zahlung 12 Stk"* by accident; matching on it would
     * turn a coincidence into a confident suggestion. Signal 1 has no such rule — there the whole
     * field is the reference, so brevity is not ambiguity.
     */
    private const val MIN_TEXT_KEY_LENGTH = 3

    /** The longest run of consecutive remittance words joined before normalising: `RE 2026 0001`. */
    private const val MAX_TOKEN_WINDOW = 4

    /**
     * The invoices [transaction] might settle, strongest first.
     *
     * @param candidates open invoices, already narrowed by [eligibleCandidates].
     * @param counterpartySupplierId the supplier the counterparty IBAN resolved to, or `null` when
     *   it resolved to none. Resolution is a database lookup, so the caller does it and passes the
     *   answer in — signals 4 and 7 consider only candidates already carrying this supplier id.
     */
    fun rank(
        transaction: TransactionFacts,
        candidates: List<InvoiceCandidate>,
        counterpartySupplierId: Long? = null,
    ): List<MatchSuggestion> {
        // The guards are a filter, not a weak signal: a suppressed candidate must be impossible to
        // surface. Re-asserted here so a caller that forgets them cannot get a suggestion anyway.
        if (!isEligible(transaction)) return emptyList()

        val supplierInvoices = candidates.filter { counterpartySupplierId != null && it.supplierId == counterpartySupplierId }

        val suggestions =
            structuredReferenceMatches(transaction, candidates) +
                remittanceTextMatches(transaction, candidates) +
                singleOpenInvoiceMatch(transaction, supplierInvoices) +
                subsetSumMatches(transaction, supplierInvoices)

        return suggestions
            .distinctByBestStrength()
            .sortedBy { it.strength.ordinal }
    }

    /** Signal 1: the payer's machine-readable reference is the invoice's own identifier. */
    private fun structuredReferenceMatches(
        transaction: TransactionFacts,
        candidates: List<InvoiceCandidate>,
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

    /**
     * Signal 2: a human typed the invoice number into the transfer text.
     *
     * Every run of up to [MAX_TOKEN_WINDOW] consecutive words is normalised and compared, so
     * `"RE 2026 0001"` — a number a payer broke apart for readability — still reaches its invoice.
     */
    private fun remittanceTextMatches(
        transaction: TransactionFacts,
        candidates: List<InvoiceCandidate>,
    ): List<MatchSuggestion> {
        val textKeys = remittanceKeys(transaction.remittanceUnstructured)
        if (textKeys.isEmpty()) return emptyList()

        return candidates
            .filter { candidate ->
                referenceKeysOf(candidate).any { it.length >= MIN_TEXT_KEY_LENGTH && it in textKeys }
            }.map { it.toSuggestion(transaction, MatchSignal.REMITTANCE_TEXT, MatchStrength.EXACT) }
    }

    /**
     * Signal 4: identity plus amount — but only where there is nothing else it could be.
     *
     * "Single" is load-bearing. With two open invoices at this supplier, one of them agreeing with
     * the amount is a coincidence we cannot rule out, and this is precisely the case that would
     * otherwise produce a confident wrong answer.
     */
    private fun singleOpenInvoiceMatch(
        transaction: TransactionFacts,
        supplierInvoices: List<InvoiceCandidate>,
    ): List<MatchSuggestion> {
        if (transaction.counterpartyIban.isNullOrBlank()) return emptyList()
        val only = supplierInvoices.singleOrNull() ?: return emptyList()
        val open = only.openAmount ?: return emptyList()
        if (open.compareTo(transaction.amount) != 0) return emptyList()
        return listOf(only.toSuggestion(transaction, MatchSignal.IBAN_SINGLE_OPEN, MatchStrength.STRONG))
    }

    /**
     * Signal 7: several of this supplier's open invoices paid in one transfer.
     *
     * Subsets of **two** to [SUBSET_MAX_SIZE] invoices: a subset of one is signal 4's territory, and
     * admitting it here would smuggle a suggestion back into the two-open-invoices case that signal
     * 4 refuses. The whole search is skipped above [SUBSET_CANDIDATE_LIMIT] candidates.
     *
     * Every sum is `BigDecimal`; equality is `compareTo`, never `equals`, which is scale-sensitive
     * and would make `119.0` and `119.00` disagree.
     */
    private fun subsetSumMatches(
        transaction: TransactionFacts,
        supplierInvoices: List<InvoiceCandidate>,
    ): List<MatchSuggestion> {
        val withAmounts = supplierInvoices.filter { it.openAmount != null }
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
        source: List<InvoiceCandidate>,
        maxSize: Int,
    ): List<List<InvoiceCandidate>> {
        val found = mutableListOf<List<InvoiceCandidate>>()

        fun build(
            startIndex: Int,
            current: MutableList<InvoiceCandidate>,
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

    private fun List<InvoiceCandidate>.sumOfOpenAmounts(): BigDecimal =
        fold(BigDecimal.ZERO) { total, candidate -> total + (candidate.openAmount ?: BigDecimal.ZERO) }

    /** The identifiers an invoice can be referred to by. */
    private fun referenceKeysOf(candidate: InvoiceCandidate): Set<String> =
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

    /**
     * One suggestion per invoice set: the same invoices reached by two signals is one thing an
     * operator can accept, kept at its strongest reading (ties broken by ladder order, so a
     * structured reference outranks a mention in free text).
     */
    private fun List<MatchSuggestion>.distinctByBestStrength(): List<MatchSuggestion> =
        groupBy { it.invoiceIds }
            .values
            .map { group -> group.minWith(compareBy({ it.strength.ordinal }, { it.signal.ordinal })) }

    /** What this payment would settle against one invoice: the payment, or its residual if smaller. */
    private fun InvoiceCandidate.toSuggestion(
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
