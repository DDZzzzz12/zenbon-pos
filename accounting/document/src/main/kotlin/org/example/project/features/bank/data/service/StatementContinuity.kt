package org.example.project.features.bank.data.service

import java.math.BigDecimal

/**
 * One statement reduced to what a continuity check needs.
 *
 * [overlapsStatementId] is the earlier statement `StatementDuplicateMarker` found this one to overlap
 * (`bank_statement.overlapping_statement_id`), or null.
 */
data class StatementBalanceLink(
    val statementId: Long,
    val openingBalance: BigDecimal?,
    val closingBalance: BigDecimal?,
    val overlapsStatementId: Long? = null,
)

/**
 * Returns the ids of statements whose opening balance does not continue the previous statement's
 * closing balance — each one meaning a statement is missing between them.
 *
 * [ordered] must already be in period order for one account; ordering is the caller's business.
 *
 * Comparison uses `compareTo`, not `equals`: `BigDecimal.equals` is scale-sensitive, so `100.00` and
 * `100.0000` would read as a break, and the column is `DECIMAL(19,4)` while a parser may hand us two
 * decimals.
 *
 * A null balance on either side is reported as a break rather than skipped — "cannot tell" must not
 * look like "all fine" in a check whose whole purpose is completeness.
 *
 * **Two statements that overlap are never a break between them** (E2E 18.6). Nothing can be missing
 * between two statements covering the same days: a re-export of March after March repeats it rather
 * than following it, so its opening balance is March's opening, not March's closing — and counting
 * that as a gap put a second "Auszug fehlt" next to the one real gap. The pair is recognised from
 * the overlap the import recorded, in either order: the later import names an earlier one, and a
 * longer re-export (Jan–Mar after March) sorts *before* the statement it names. The check resumes from
 * the later statement's closing balance, so a real gap after a duplicate is still reported.
 *
 * Pure: no I/O, no database.
 */
fun findContinuityBreaks(ordered: List<StatementBalanceLink>): List<Long> =
    ordered
        .zipWithNext()
        .filter { (previous, next) -> !previous.overlaps(next) && !previous.continuesInto(next) }
        .map { (_, next) -> next.statementId }

/**
 * [next] repeats days already imported (the marker names some earlier statement for it), or this
 * statement is a longer re-export that names [next]. Either way the two share days, so the pair
 * cannot hide a missing statement.
 */
private fun StatementBalanceLink.overlaps(next: StatementBalanceLink): Boolean = next.overlapsStatementId != null || overlapsStatementId == next.statementId

private fun StatementBalanceLink.continuesInto(next: StatementBalanceLink): Boolean {
    val closing = closingBalance ?: return false
    val opening = next.openingBalance ?: return false
    return closing.compareTo(opening) == 0
}
