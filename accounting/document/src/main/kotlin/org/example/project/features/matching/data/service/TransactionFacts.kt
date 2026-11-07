package org.example.project.features.matching.data.service

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * One bank statement entry, reduced to the plain values matching needs — the row's own fields plus
 * the one thing that lives on its statement ([hasBankAccount]).
 *
 * A value type on purpose: nothing here is an Exposed entity, so every rule that reads it is a pure
 * function and can be tested without a database. The caller does the loading; this slice does the
 * deciding.
 *
 * [amount] is always positive, as in `bank_statement_transaction`; the direction is [creditDebit]
 * (`"DEBIT"` or `"CREDIT"`, ISO 20022's `CdtDbtInd`), and money is `BigDecimal` — never a `Double`,
 * because a cent of float error in an amount comparison becomes a wrong suggestion.
 *
 * @property accountServicerReference the bank's own reference for the entry (`AcctSvcrRef`). No match
 *   signal reads it — it exists for [findReversedEntry], where it is the second-strongest way to tell
 *   which earlier entry a returned direct debit undoes.
 * @property isDuplicate whether an overlapping statement re-delivered an entry we already hold,
 *   from V91's `duplicate_of_transaction_id` (written by `StatementDuplicateMarker`). Allocating a
 *   duplicate would pay the same invoice twice, so [MatchEligibility] refuses to suggest against it
 *   and the assignment queue filters it out. This read `false` unconditionally until the banking
 *   merge landed the column, which made that guard dead code.
 */
data class TransactionFacts(
    val id: Long,
    val valueDate: LocalDate,
    val amount: BigDecimal,
    val creditDebit: String,
    val counterpartyName: String? = null,
    val counterpartyIban: String? = null,
    val remittanceUnstructured: String? = null,
    val remittanceStructuredRef: String? = null,
    val endToEndId: String? = null,
    val accountServicerReference: String? = null,
    val mandateId: String? = null,
    val creditorId: String? = null,
    val reversalIndicator: Boolean = false,
    val hasBankAccount: Boolean = true,
    val isDuplicate: Boolean = false,
)
