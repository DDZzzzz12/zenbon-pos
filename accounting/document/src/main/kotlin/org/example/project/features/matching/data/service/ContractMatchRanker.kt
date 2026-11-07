package org.example.project.features.matching.data.service

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import org.example.project.features.contract.data.service.isWithinTolerance
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Given a payment and the contract instalments it could plausibly settle, works out which ones it
 * might actually settle — and how confident that is.
 *
 * The contract half of the ladder [InvoiceMatchRanker] covers for invoices, with the same
 * discipline: pure, no database, no HTTP, no clock; the caller loads the candidates and resolves
 * the identifiers, and this object only decides. Nothing is auto-applied, so where a rule is
 * ambiguous it resolves toward saying less.
 *
 * Signals implemented, numbered as in spec §7:
 * - **3** the SEPA mandate reference, or the SEPA Creditor Identifier plus the supplier — `EXACT`,
 *   because an identifier the *payer's bank* quoted agreed with one we recorded. One instalment per
 *   contract: the one due nearest the payment.
 * - **5** the counterparty IBAN resolves to the instalment's supplier, the amount sits inside the
 *   contract's tolerance band, and the payment fell near the due date — `STRONG`, because identity
 *   and amount agree but no identifier does.
 *
 * **Signal 3 is silent until the identifiers are recorded**, which is every installation on day one.
 * That is the whole point: it earns `EXACT` from an identifier, and with none recorded there is
 * nothing to earn it from. A fallback to something weaker would hand out confident wrong answers in
 * exactly the commonest case.
 */
object ContractMatchRanker {
    /**
     * How far from the due date a payment may fall and still be read as settling that instalment.
     *
     * Ten days each way: a direct debit clears within a few working days of the due date, and a bank
     * transfer paid on receipt of a reminder lands a little after. Ten covers both without reaching
     * into the next month's instalment, which for a monthly obligation is what a wider window would
     * start doing. The boundary itself is inside (`<=`).
     */
    private const val DUE_DATE_WINDOW_DAYS = 10

    /**
     * The instalments [transaction] might settle, strongest first.
     *
     * @param candidates the open instalments worth considering. Settled ones are dropped here too,
     *   so a caller that forgets cannot get a suggestion anyway.
     * @param counterpartySupplierId the supplier the counterparty **IBAN** resolved to, or `null`.
     *   Signal 5's identity leg.
     * @param sepaCreditorSupplierId the supplier whose recorded `sepa_creditor_id` equals
     *   `transaction.creditorId`, or `null` when the transaction carries none or none is recorded.
     *   **Deliberately a second parameter rather than reusing [counterpartySupplierId]**: signal 3 is
     *   `EXACT` because an *identifier* agreed, and feeding it an IBAN resolution would award `EXACT`
     *   to evidence that only earns `STRONG` — the precise failure this ladder exists to avoid.
     */
    fun rank(
        transaction: TransactionFacts,
        candidates: List<ContractCandidate>,
        counterpartySupplierId: Long? = null,
        sepaCreditorSupplierId: Long? = null,
    ): List<MatchSuggestion> {
        // Re-asserted here, as in InvoiceMatchRanker: the guards are a filter, not a weak signal.
        if (!isEligible(transaction)) return emptyList()

        val settleable = candidates.filter { it.isSettleable() }

        val suggestions =
            identifierMatches(transaction, settleable, sepaCreditorSupplierId) +
                contractWindowMatches(transaction, settleable, counterpartySupplierId)

        return suggestions
            .distinctByBestStrength()
            .sortedBy { it.strength.ordinal }
    }

    /**
     * Signal 3: an identifier the bank quoted on the direct debit is one we recorded.
     *
     * Two readings, either sufficient. The mandate reference reaches a single contract, because a
     * mandate is granted per agreement. The Creditor Identifier only reaches the *supplier*, so it
     * is paired with the instalment's supplier id to get down to a contract — and it names every one
     * of that supplier's contracts, which is a list for a human rather than a guess.
     *
     * A null or blank identifier on either side matches nothing: an unrecorded identifier is the
     * absence of evidence, and treating two absences as agreement would fire on every transaction.
     *
     * **An identifier proves the contract, not the instalment** (E2E 20.4). A monthly contract has
     * several open instalments at once — this month's and, once generated, next month's — and all
     * of them carry the same mandate, so every one of them used to come back `EXACT`: the operator
     * was shown two "Eindeutig" answers for one debit. Per contract only the instalment whose due
     * date lies nearest the payment's value date is kept (ties keep both — two instalments equally
     * near are a genuine choice for a human). Across contracts nothing is narrowed: a creditor id
     * reaching two of a supplier's contracts still names one instalment of each.
     */
    private fun identifierMatches(
        transaction: TransactionFacts,
        candidates: List<ContractCandidate>,
        sepaCreditorSupplierId: Long?,
    ): List<MatchSuggestion> {
        val mandateId = transaction.mandateId?.takeIf { it.isNotBlank() }
        val creditorId = transaction.creditorId?.takeIf { it.isNotBlank() }

        return candidates
            .filter { candidate ->
                candidate.matchesMandate(mandateId) || candidate.matchesCreditor(creditorId, sepaCreditorSupplierId)
            }.nearestDuePerContract(transaction.valueDate)
            .map { it.toSuggestion(transaction, MatchSignal.MANDATE_OR_CREDITOR, MatchStrength.EXACT) }
    }

    /** Per contract, the instalment(s) whose due date is nearest [valueDate]. */
    private fun List<ContractCandidate>.nearestDuePerContract(valueDate: LocalDate): List<ContractCandidate> =
        groupBy { it.contractId }
            .values
            .flatMap { ofOneContract ->
                val nearest = ofOneContract.minOf { abs(it.dueDate.daysUntil(valueDate)) }
                ofOneContract.filter { abs(it.dueDate.daysUntil(valueDate)) == nearest }
            }

    private fun ContractCandidate.matchesMandate(mandateId: String?): Boolean {
        val recorded = sepaMandateId?.takeIf { it.isNotBlank() } ?: return false
        return mandateId != null && recorded == mandateId
    }

    private fun ContractCandidate.matchesCreditor(
        creditorId: String?,
        sepaCreditorSupplierId: Long?,
    ): Boolean {
        if (creditorId == null || sepaCreditorSupplierId == null) return false
        return supplierId != null && supplierId == sepaCreditorSupplierId
    }

    /**
     * Signal 5: identity, amount and date all agree, and no identifier is involved.
     *
     * The band comes from [isWithinTolerance], not from a comparison written here — one band rule,
     * one derivation, so a contract's tolerance means the same thing to the matcher as it does to
     * the alert that complains about the amount.
     *
     * An instalment with no expected amount is skipped: there is nothing to compare the payment
     * against, and treating "no expectation" as "any amount agrees" would suggest the instalment for
     * every payment this supplier ever receives.
     */
    private fun contractWindowMatches(
        transaction: TransactionFacts,
        candidates: List<ContractCandidate>,
        counterpartySupplierId: Long?,
    ): List<MatchSuggestion> {
        if (transaction.counterpartyIban.isNullOrBlank() || counterpartySupplierId == null) return emptyList()

        return candidates
            .filter { it.supplierId == counterpartySupplierId }
            .filter { candidate ->
                val expected = candidate.expectedAmount ?: return@filter false
                isWithinTolerance(expected, transaction.amount, candidate.tolerancePct, candidate.toleranceAbs) &&
                    isNearDueDate(transaction.valueDate, candidate.dueDate)
            }.map { it.toSuggestion(transaction, MatchSignal.CONTRACT_WINDOW, MatchStrength.STRONG) }
    }

    private fun isNearDueDate(
        valueDate: LocalDate,
        dueDate: LocalDate,
    ): Boolean = abs(dueDate.daysUntil(valueDate)) <= DUE_DATE_WINDOW_DAYS

    /**
     * A residual at or below zero is already settled and cannot absorb a payment. A null one is
     * unknown rather than settled — the instalment generator leaves it null until an amount is
     * known — so it is kept and the payment's own amount is what would be settled.
     */
    private fun ContractCandidate.isSettleable(): Boolean {
        val open = openAmount ?: return true
        return open > BigDecimal.ZERO
    }

    /**
     * One suggestion per instalment: the same instalment reached by both signals is one thing an
     * operator can accept, kept at its strongest reading (ties broken by ladder order).
     */
    private fun List<MatchSuggestion>.distinctByBestStrength(): List<MatchSuggestion> =
        groupBy { it.contractInstalmentId }
            .values
            .map { group -> group.minWith(compareBy({ it.strength.ordinal }, { it.signal.ordinal })) }

    /** What this payment would settle against one instalment: the payment, or its residual if smaller. */
    private fun ContractCandidate.toSuggestion(
        transaction: TransactionFacts,
        signal: MatchSignal,
        strength: MatchStrength,
    ): MatchSuggestion =
        MatchSuggestion(
            invoiceIds = emptyList(),
            signal = signal,
            strength = strength,
            amount = openAmount?.let { minOf(it, transaction.amount) } ?: transaction.amount,
            contractInstalmentId = instalmentId,
        )
}
