package org.example.project.features.matching.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.allocation.SuggestionDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.finance.domain.service.BookkeepingStartService
import org.example.project.features.matching.domain.service.MatchSuggestionService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.toExactDecimalString

/**
 * See [MatchSuggestionService].
 *
 * The whole method is one shape: gather the facts, run each pure ranker over them, merge, sort, map.
 * No rule lives here — every decision about *whether* something matches belongs to
 * [InvoiceMatchRanker], [ContractMatchRanker], [supplierNameSuggestions] and [isEligible], which are
 * functions over values and are tested without a database. This service only feeds them.
 *
 * Amounts go out through [toExactDecimalString], not `toDecimalString`: these columns are
 * `DECIMAL(19,4)` and the scale-2 formatter would round a residual on its way to the wire.
 */
class MatchSuggestionServiceImpl(
    private val gatherer: MatchFactsGatherer,
    private val bookkeepingStart: BookkeepingStartService,
) : MatchSuggestionService {
    override suspend fun suggestionsFor(transactionId: Long): SuggestionDto.ResponseDto {
        // Outside the dbQuery below: cutoff() opens a transaction of its own, and nesting one is
        // the pattern pos-server/CLAUDE.md forbids.
        val bookedFrom = bookkeepingStart.cutoff()?.date
        return dbQuery {
            val facts =
                gatherer.gather(transactionId, bookedFrom)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Bank transaction $transactionId not found",
                    )
            val items = facts.rank().map { facts.toItem(it) }
            SuggestionDto.ResponseDto(items = items, total = items.size)
        }
    }

    /**
     * Every signal over the same facts, merged and ordered.
     *
     * The direction of the transaction decides which ladder runs: a debit is AP (supplier invoices,
     * contracts, signal 6), a credit is AR (STANDALONE invoices). The two never mix, which is the
     * parent spec's direction guard — without it a €340 supplier invoice and a €340 customer invoice
     * compete for the same transaction row. Each ranker re-applies the eligibility guards itself, so
     * an ineligible transaction yields nothing here without this method having to know why — which
     * is how a reversal answers 200 with an empty list rather than an error.
     */
    private fun MatchFacts.rank(): List<MatchSuggestion> {
        val suggestions =
            if (transaction.creditDebit.equals(CREDIT, ignoreCase = true)) {
                SalesInvoiceMatchRanker.rank(
                    transaction = transaction,
                    candidates = salesInvoiceCandidates,
                    debtorNormalizedNames = debtorNormalizedNames,
                )
            } else {
                InvoiceMatchRanker.rank(transaction, invoiceCandidates, counterpartySupplierId) +
                    supplierNameSuggestions(transaction, invoiceCandidates, supplierNormalizedNames) +
                    ContractMatchRanker.rank(
                        transaction = transaction,
                        candidates = contractCandidates,
                        counterpartySupplierId = counterpartySupplierId,
                        sepaCreditorSupplierId = sepaCreditorSupplierId,
                    )
            }
        return suggestions.distinctByBestStrength().sortedWith(
            // The last two keys only break ties, so the order is stable rather than left to the
            // order the signals happened to fire in.
            compareBy(
                { it.strength.ordinal },
                { it.signal.ordinal },
                { it.contractInstalmentId ?: Long.MAX_VALUE },
                { it.invoiceIds.minOrNull() ?: Long.MAX_VALUE },
            ),
        )
    }

    /**
     * One entry per target, kept at its strongest reading.
     *
     * The invoice ladder and signal 6 can reach the same invoice — a structured reference *and* the
     * supplier's name with a matching amount. Showing it twice would ask an operator to choose
     * between two spellings of one answer; the weaker reading adds nothing once the stronger one is
     * on screen. This mirrors `ContractMatchRanker`'s own merge; ties break by ladder order.
     */
    private fun List<MatchSuggestion>.distinctByBestStrength(): List<MatchSuggestion> =
        groupBy { it.contractInstalmentId to it.invoiceIds }
            .values
            .map { group -> group.minWith(compareBy({ it.strength.ordinal }, { it.signal.ordinal })) }

    private fun MatchFacts.toItem(suggestion: MatchSuggestion): SuggestionDto.ItemDto {
        val isSalesInvoice =
            suggestion.signal == MatchSignal.DEBTOR_NAME_AMOUNT_DATE ||
                transaction.creditDebit.equals(CREDIT, ignoreCase = true)
        return SuggestionDto.ItemDto(
            targetKind =
                when {
                    suggestion.contractInstalmentId != null -> CONTRACT_INSTALMENT
                    isSalesInvoice -> SALES_INVOICE
                    else -> INVOICE
                },
            signal = suggestion.signal.name,
            strength = suggestion.strength.name,
            amount = suggestion.amount.toExactDecimalString(),
            currency = currency,
            label = label(suggestion),
            invoiceIds = if (isSalesInvoice) emptyList() else suggestion.invoiceIds,
            salesInvoiceIds = if (isSalesInvoice) suggestion.invoiceIds else emptyList(),
            contractInstalmentId = suggestion.contractInstalmentId,
        )
    }

    /**
     * What the client shows on the row, derived here because it needs the invoice and contract rows
     * the gatherer already loaded — re-deriving it in the browser would mean fetching them again.
     *
     * An invoice with no number falls back to `#12`, its id, rather than to an empty string: a
     * blank label is a row an operator cannot tell apart from its neighbours. The fallback carries
     * no words on purpose — a label assembled on the server cannot be translated on the client.
     */
    private fun MatchFacts.label(suggestion: MatchSuggestion): String {
        val instalmentId = suggestion.contractInstalmentId
        if (instalmentId != null) {
            val candidate =
                contractCandidates.firstOrNull { it.instalmentId == instalmentId }
                    ?: return "#$instalmentId"
            val name = contractNames[candidate.contractId].orEmpty()
            return "$name ${candidate.dueDate}".trim()
        }
        if (transaction.creditDebit.equals(CREDIT, ignoreCase = true)) {
            return suggestion.invoiceIds.joinToString(", ") { salesInvoiceId ->
                salesInvoiceCandidates.firstOrNull { it.id == salesInvoiceId }?.invoiceNumber ?: "#$salesInvoiceId"
            }
        }
        return suggestion.invoiceIds.joinToString(", ") { invoiceId ->
            invoiceCandidates.firstOrNull { it.id == invoiceId }?.invoiceNumber ?: "#$invoiceId"
        }
    }

    private companion object {
        const val INVOICE = "INVOICE"
        const val CONTRACT_INSTALMENT = "CONTRACT_INSTALMENT"
        const val SALES_INVOICE = "SALES_INVOICE"
    }
}
