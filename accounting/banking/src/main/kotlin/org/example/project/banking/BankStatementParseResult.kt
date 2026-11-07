package org.example.project.banking

import org.example.project.banking.model.BankStatement

/**
 * Malformed third-party input is an expected condition, not an exceptional one — a bank export can
 * and will be truncated or malformed. Parsers therefore return a failure value rather than
 * throwing, so the caller must handle it.
 */
sealed interface BankStatementParseResult {
    data class Success(
        val statement: BankStatement,
    ) : BankStatementParseResult

    /** No statement payload was found — e.g. a PDF that is not a statement. Not an error. */
    data object NoStatementFound : BankStatementParseResult

    /**
     * The file is a statement, but it could not be read or does not hold together.
     *
     * [balancesDoNotReconcile] is true when the file parsed cleanly and only its arithmetic failed —
     * opening balance plus the entries does not give the stated closing balance. It is carried as a
     * flag rather than left inside [reason] because a caller must tell the operator *this* case in
     * their own language: it has a different remedy (ask the bank for a correct export) from a file
     * that is merely unreadable, and [reason] is English prose for the server log, not a contract.
     */
    data class Failure(
        val reason: String,
        val cause: Throwable? = null,
        val balancesDoNotReconcile: Boolean = false,
    ) : BankStatementParseResult
}
