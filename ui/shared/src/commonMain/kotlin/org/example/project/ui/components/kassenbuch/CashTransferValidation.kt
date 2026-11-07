/**
 * Client-side validation for a manual cash transfer (Geldtransit).
 *
 * Mirrors the server guards in `CashBookTransferServiceImpl` so a rejection is unreachable rather
 * than merely handled. Pure and Compose-free, so it is unit-testable on every target — the same
 * shape as the other pure helpers in this module.
 *
 * Public rather than internal on purpose: adminApp's outstanding non-fiscal transfer screen reuses
 * this form, and its test module is separate.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.components.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal

/** Why a transfer cannot be submitted. `null` — never a member here — means it may be. */
enum class CashTransferError {
    NO_SOURCE,
    NO_TARGET,
    SAME_BOOK,
    AMOUNT_MISSING,
    AMOUNT_NOT_POSITIVE,
    AMOUNT_EXCEEDS_SOURCE_BALANCE,
}

/**
 * Validates a transfer, returning the first blocking reason or `null` when it may be submitted.
 *
 * The balance check compares against [source]'s **own** `currentBalance`, not the till's.
 * Comparing against a fixed book would let an over-transfer out of the Tresor through to a server
 * rejection the operator cannot explain.
 *
 * @param source The book the cash leaves
 * @param target The book the cash arrives in
 * @param rawAmount The operator's typed amount; a German decimal comma is accepted
 */
fun validateCashTransfer(
    source: CashBookDto.ResponseDto?,
    target: CashBookDto.ResponseDto?,
    rawAmount: String,
): CashTransferError? {
    if (source == null) return CashTransferError.NO_SOURCE
    if (target == null) return CashTransferError.NO_TARGET
    if (source.id == target.id) return CashTransferError.SAME_BOOK

    val trimmed = rawAmount.trim()
    if (trimmed.isEmpty()) return CashTransferError.AMOUNT_MISSING

    val amount =
        trimmed.toCanonicalMoneyOrNull()?.let { Decimal.parseOrNull(it) }
            ?: return CashTransferError.AMOUNT_NOT_POSITIVE
    if (amount.signum <= 0) return CashTransferError.AMOUNT_NOT_POSITIVE

    // A book whose balance is itself unparseable must not silently authorise a transfer.
    val available = Decimal.parseOrNull(source.currentBalance) ?: return CashTransferError.AMOUNT_EXCEEDS_SOURCE_BALANCE
    if (amount > available) return CashTransferError.AMOUNT_EXCEEDS_SOURCE_BALANCE

    return null
}

/**
 * The books offerable as a transfer source or target.
 *
 * Active only — the server's `requireCompatibleBooks` rejects an inactive book on either side, so
 * offering one would produce a rejection the operator cannot act on.
 */
fun selectableBooks(books: List<CashBookDto.ResponseDto>): List<CashBookDto.ResponseDto> = books.filter { it.isActive }
