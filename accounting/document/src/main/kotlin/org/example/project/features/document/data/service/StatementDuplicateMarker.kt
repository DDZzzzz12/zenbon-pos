package org.example.project.features.document.data.service

import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.repository.DocumentRepository

/**
 * Records what a freshly-written statement repeats — without removing anything.
 *
 * An operator uploads January, then later a January–March export. The bytes differ and the documents
 * differ, so neither the content-hash check nor the source-document keying catches it. Storing both
 * verbatim is the honest outcome; the cost is that a consumer must be told which rows are repeats.
 *
 * **Marks, never deletes.** The MT940 key is composite and will occasionally collide on two
 * legitimately identical same-day payments, so a false positive must cost a misleading hint rather
 * than a lost transaction.
 *
 * Requires an ambient transaction.
 */
class StatementDuplicateMarker(
    private val repository: DocumentRepository,
) {
    fun mark(statement: BankStatement) {
        val statementId = statement.id.value
        val candidates =
            repository.findStatementsForAccount(
                accountIban = statement.accountIban,
                accountNumber = statement.accountNumber,
                excludeStatementId = statementId,
            )
        // One query for every span involved, this statement's included — the derivation below needs
        // the candidates' entry dates as well as its own.
        val entrySpans = repository.getTransactionDateRanges(candidates.map { it.id.value } + statementId)
        val period = effectivePeriod(statement, entrySpans)
        val overlapping =
            candidates.filter { candidate ->
                overlaps(period, effectivePeriod(candidate, entrySpans))
            }

        repository.markStatementOverlap(statementId, overlapping.firstOrNull()?.id?.value)

        // Compared only against *other* statements: a re-parse replaces this statement's own rows,
        // and comparing against the rows it just replaced would mark every entry a duplicate of
        // itself.
        val known = repository.getTransactionsForStatements(overlapping.map { it.id.value })
        if (known.isEmpty()) return

        // Each known row is consumed at most once. Set membership would be wrong, and dangerously
        // so: if January holds ONE €12.50 top-up and the January–March re-export holds TWO — one
        // repeat and one genuinely new payment — a membership test marks both against the single
        // known row. The consumer filters its queue on `duplicate_of_transaction_id IS NULL`, so
        // that second payment would never reach reconciliation. That is the lost-money outcome this
        // whole design refuses, arriving through marking instead of deletion.
        //
        // Pairing off instead means N known copies and M new copies mark exactly min(N, M): every
        // repeat is flagged, and every genuinely additional payment survives into the queue.
        val byServicerRef = known.filter { it.accountServicerReference != null }.groupByTo(mutableMapOf()) { it.accountServicerReference!! }
        val byComposite = known.groupByTo(mutableMapOf(), ::compositeKey)

        repository.getTransactions(statementId).forEach { entry ->
            val match =
                entry.accountServicerReference?.let { byServicerRef.consume(it) }
                    ?: byComposite.consume(compositeKey(entry))
            repository.markTransactionDuplicate(entry.id.value, match?.id?.value)
        }
    }

    /**
     * The span this statement is compared on: what it *states*, or failing that what its entries
     * span.
     *
     * A real German CAMT.053 export omits `<FrToDt>` — the corpus fixture does — so the stated
     * period is null and a period-only comparison would silently never flag any repeat in such a
     * file. Every hand-authored fixture carries `FrToDt`, which is why the unit tests passed while
     * the feature did nothing for real files of that shape.
     *
     * **The derived span is deliberately not persisted.** `bank_statement.period_start`/`period_end`
     * mean "what the file stated", and a hand-entered or PDF statement is legitimately allowed to
     * state none. This value exists only to decide overlap.
     *
     * Null when the statement states no period and has no entries — there is then nothing to compare.
     */
    private fun effectivePeriod(
        statement: BankStatement,
        entrySpans: Map<Long, Pair<LocalDateTime, LocalDateTime>>,
    ): Pair<LocalDateTime, LocalDateTime>? {
        val start = statement.periodStart
        val end = statement.periodEnd
        if (start != null && end != null) return start to end
        return entrySpans[statement.id.value]
    }

    /** Two spans overlap when each starts no later than the other ends. Null means "unknown", never "matches". */
    private fun overlaps(
        a: Pair<LocalDateTime, LocalDateTime>?,
        b: Pair<LocalDateTime, LocalDateTime>?,
    ): Boolean {
        if (a == null || b == null) return false
        return a.first <= b.second && a.second >= b.first
    }

    /**
     * Takes one row for [key] and removes it, so a later entry cannot match the same row again.
     * Returns null once the known copies are exhausted — which is what leaves a genuinely new
     * payment unmarked.
     */
    private fun MutableMap<String, MutableList<BankStatementTransaction>>.consume(key: String): BankStatementTransaction? {
        val bucket = this[key] ?: return null
        val row = bucket.removeFirstOrNull()
        if (bucket.isEmpty()) remove(key)
        return row
    }

    /**
     * The fallback identity for a format that has none.
     *
     * CAMT gives a bank-assigned `AcctSvcrRef` that is stable across exports, so a match on it is a
     * true duplicate. MT940 has no reliable identifier at all, leaving only the fields a repeat is
     * certain to share. Two genuinely distinct same-day payments of the same amount to the same
     * counterparty will collide here — which is exactly why this marks rather than removes, and why
     * matching pairs off rather than testing set membership.
     */
    private fun compositeKey(entry: BankStatementTransaction): String =
        listOf(
            entry.bookingDate.toString(),
            entry.valueDate.toString(),
            entry.amount.stripTrailingZeros().toPlainString(),
            entry.creditDebit,
            entry.counterpartyIban.orEmpty(),
            entry.remittanceUnstructured.orEmpty().trim(),
        ).joinToString("|")
}
