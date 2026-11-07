package org.example.project.features.reports.presentation.state

import org.example.project.money.Decimal

/**
 * A cash book the "Z-Bon erstellen" dialog may top the drawer up from (F59, option A): one of the
 * shop's other ACTIVE, non-fiscal books (Tresor, Hauptkasse), with its current balance.
 *
 * @property balance the book's balance as the server sent it (decimal string)
 */
data class ClosingTopUpSource(
    val cashBookId: Long,
    val name: String,
    val balance: String,
) {
    /** Whether the book holds at least [amount] — the server refuses a top-up above its balance. */
    fun covers(amount: Decimal): Boolean = (Decimal.parseOrNull(balance) ?: Decimal.ZERO) >= amount
}
