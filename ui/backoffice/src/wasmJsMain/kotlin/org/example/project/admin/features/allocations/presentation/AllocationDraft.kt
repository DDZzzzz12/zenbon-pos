package org.example.project.admin.features.allocations.presentation

import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal

/**
 * The draft key for one target. Kind-qualified because ids are only unique **within** a kind —
 * instalment 7 and invoice 7 are different targets, and a bare-id key silently shares one amount
 * between them.
 *
 * The terminal kinds have no target id, so they key on the kind alone: there is exactly one such
 * draft open at a time, since the editor shows one kind's panel at a time.
 */
fun draftKey(
    targetKind: String,
    targetId: Long?,
): String = if (targetId == null) targetKind else "$targetKind:$targetId"

/**
 * The typed amount, canonicalised, or null when it must not be sent.
 *
 * Refused: blank, unparseable, zero or negative, and anything above [restOpen]. The server rejects
 * the same cases with 409 — this half exists so an operator is stopped before making the request,
 * not after (root `CLAUDE.md` rule 8).
 *
 * An **unparseable [restOpen] refuses everything**. Treating an unreadable residual as unlimited
 * would turn a display bug into an over-allocation.
 *
 * Comparison is in [Decimal] throughout; a string compare would rank `"9"` above `"100"`, and a
 * `Double` would reintroduce the cent error the `:platform-money` module exists to remove.
 */
fun canonicalAmountOrNull(
    typed: String,
    restOpen: String,
): String? {
    val canonical = typed.toCanonicalMoneyOrNull() ?: return null
    val amount = Decimal.parseOrNull(canonical) ?: return null
    if (amount <= Decimal.ZERO) return null
    val limit = Decimal.parseOrNull(restOpen) ?: return null
    if (amount > limit) return null
    return canonical
}
