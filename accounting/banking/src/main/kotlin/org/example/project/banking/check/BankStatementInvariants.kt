package org.example.project.banking.check

import org.example.project.banking.model.BankStatement
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.CreditDebit
import java.math.BigDecimal

/**
 * Arithmetic that must hold for a statement we are willing to store.
 *
 * A failed balance check almost always means **we** misparsed — a dropped entry, a flipped
 * `CdtDbtInd`, a mishandled reversal — rather than that the bank sent bad arithmetic. Both formats
 * state opening, closing and every entry explicitly, so the sum is a genuine cross-check rather
 * than a heuristic. The caller turns a violation into a `Failure` and stores nothing: derived data
 * that is plausibly wrong is worse than none, because nobody can tell the wrong kind from the right
 * kind by looking.
 *
 * Returns null when everything holds, or a human-readable description of the first violation.
 */
object BankStatementInvariants {
    fun check(statement: BankStatement): String? = currencyViolation(statement) ?: balanceViolation(statement)

    /**
     * Every entry must be in the statement's currency. A statement mixing currencies cannot be
     * summed against a single pair of balances, so accepting one would make the balance check
     * meaningless rather than merely unchecked.
     */
    private fun currencyViolation(statement: BankStatement): String? {
        val foreign = statement.transactions.firstOrNull { it.currency != statement.currency } ?: return null
        return "entry currency ${foreign.currency} does not match the statement currency ${statement.currency}"
    }

    /**
     * `opening + Σ signed entries == closing`.
     *
     * A parsed statement **must** state both balances. CAMT.053 and MT940 each mandate them
     * (`OPBD`/`CLBD`, `:60F:`/`:62F:`), so an absent one means a misparse rather than a terse bank.
     * Requiring them also turns the balances into a guarantee the reconciliation work can build on:
     * the Finanzen Bank tab detects a missing statement by checking that statement *n*'s closing
     * equals statement *n+1*'s opening, and that check degrades silently to nothing if a stored
     * statement carries nulls.
     *
     * `MANUAL` and `PDF` are exempt: they never come from this parser, and the model deliberately
     * allows a hand-entered statement to state no balances at all.
     *
     * `compareTo` rather than `equals`, because `BigDecimal("150.0") != BigDecimal("150.000")` —
     * equal value, different scale. Banks are inconsistent about trailing zeros and a scale
     * mismatch is not a discrepancy.
     */
    private fun balanceViolation(statement: BankStatement): String? {
        val parsedFormat =
            statement.format == BankStatementFormat.CAMT053 || statement.format == BankStatementFormat.MT940

        val opening =
            statement.openingBalance
                ?: return if (parsedFormat) "statement states no opening balance" else null
        val closing =
            statement.closingBalance
                ?: return if (parsedFormat) "statement states no closing balance" else null

        val movement =
            statement.transactions.fold(BigDecimal.ZERO) { sum, entry ->
                when (entry.creditDebit) {
                    CreditDebit.CREDIT -> sum + entry.amount
                    CreditDebit.DEBIT -> sum - entry.amount
                }
            }
        val expected = opening + movement
        if (expected.compareTo(closing) == 0) return null

        return "opening $opening plus movement $movement is $expected, " +
            "but the statement states a closing balance of $closing"
    }
}
