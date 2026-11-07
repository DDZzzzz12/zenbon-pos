package org.example.project.banking

import org.example.project.banking.model.BankStatement

/**
 * Folds several statements from one file into the single statement the model and the
 * `bank_statement` table hold.
 *
 * German banks routinely deliver a month's download as one statement per booking day, so a file
 * with many statements is the ordinary case rather than an oddity. Taking the first would lose the
 * rest silently; rejecting the file would reject a routine download.
 *
 * The arithmetic survives the fold because each day's closing balance is the next day's opening:
 * taking the **first** opening and the **last** closing leaves `opening + Σ entries == closing`
 * true across the whole span, so the invariant check is as meaningful on a merged statement as on a
 * single one.
 *
 * `statementNumber` is lossy — only the first survives, and the others are the caller's to log. It
 * keys nothing, so this costs an identifier nobody reads rather than data anybody needs.
 */
object StatementMerge {
    fun merge(statements: List<BankStatement>): BankStatement {
        require(statements.isNotEmpty()) { "cannot merge an empty list of statements" }
        val first = statements.first()
        if (statements.size == 1) return first
        val last = statements.last()

        return first.copy(
            transactions = statements.flatMap { it.transactions },
            statementDate = statements.mapNotNull { it.statementDate }.maxOrNull(),
            periodStart = statements.mapNotNull { it.periodStart }.minOrNull(),
            periodEnd = statements.mapNotNull { it.periodEnd }.maxOrNull(),
            openingBalance = first.openingBalance,
            closingBalance = last.closingBalance,
        )
    }
}
