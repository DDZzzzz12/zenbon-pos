package org.example.project.admin.features.kassenbuch.presentation

import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal

/**
 * The most decimals the counted amount may carry — `DECIMAL(19,4)` in the database, and the same
 * limit `CashBookDto.CloseRequestDto.validate()` and the server's regex enforce. Guarding it here
 * as well is the client half of CLAUDE.md rule 8: a fifth decimal is refused while it is being
 * typed rather than surfacing as a 422 after the operator has counted the safe.
 */
internal const val COUNTED_BALANCE_MAX_SCALE = 4

/**
 * The operator's counted amount as an exact [Decimal], or `null` when the field does not yet hold a
 * usable count.
 *
 * `null` covers four different inputs on purpose — blank, malformed, negative, and too precise — and
 * the caller must NOT read it as "zero". **Zero is a legitimate count** (an emptied safe) and comes
 * back here as [Decimal.ZERO]; conflating it with a blank field is exactly the defect this
 * distinction exists to prevent, since it would let the dialog submit a count the operator never
 * entered. [KassensturzUiState.hasUnusableInput] separates the blank case from the other three for
 * display.
 *
 * Normalization is delegated to [toCanonicalMoneyOrNull] so the German comma is folded by the one
 * helper the whole client uses, and the re-parse of its canonical output is exact — no value ever
 * passes through a `Double`.
 *
 * A negative count is rejected rather than sent: the server models a shortfall as `counted − book`
 * being negative, never as a negative count, and its `isNonNegativeMoney` check would reject it.
 */
internal fun countedBalanceOf(input: String): Decimal? =
    input
        .toCanonicalMoneyOrNull()
        ?.let { Decimal.parse(it) }
        ?.takeUnless { it.isNegative || it.scale > COUNTED_BALANCE_MAX_SCALE }

/**
 * The open Kassensturz dialog: the server's period preview plus what the operator has typed into it.
 *
 * A `null` `KassensturzUiState` on [KassenbuchState] means the dialog is closed. The preview is
 * non-null by construction because the dialog is only opened once `closePreview` has answered — the
 * period and the book balance are the two things the operator is being asked to check the cash
 * against, so there is no meaningful dialog without them.
 */
data class KassensturzUiState(
    val preview: CashBookDto.ClosePreviewDto,
    val countedInput: String = "",
    val notes: String = "",
    val isSubmitting: Boolean = false,
    /** Bumped on every Confirm press refused for an unusable count (validate on press); transient. */
    val validationAttempt: Int = 0,
) {
    /** True once the operator has typed a count or a note; a pristine dialog closes without asking. */
    val isDirty: Boolean get() = countedInput.isNotBlank() || notes.isNotBlank()

    /** The server's book balance for the period. `null` only if it were malformed, which it is not. */
    val bookBalance: Decimal? get() = Decimal.parseOrNull(preview.bookBalance)

    /** See [countedBalanceOf] — `null` is "no usable count", never "zero". */
    val countedBalance: Decimal? get() = countedBalanceOf(countedInput)

    /**
     * `counted − bookBalance`, computed in [Decimal] and never rounded on the way: this is the
     * number that decides whether the operator recounts, and a difference nudged into agreement by
     * a `Double` would tell them the safe is right when it is not.
     */
    val difference: Decimal?
        get() {
            val counted = countedBalance ?: return null
            val book = bookBalance ?: return null
            return counted - book
        }

    /**
     * True when something was typed but it cannot be used — malformed, negative, or beyond four
     * decimals. A blank field is deliberately NOT flagged: an operator who has not typed yet is not
     * making a mistake.
     */
    val hasUnusableInput: Boolean get() = countedInput.isNotBlank() && countedBalance == null

    /**
     * Both balances must be readable, not just the typed one: the dialog's promise is that the
     * difference is on screen before the close is confirmed, and an unreadable book balance would
     * leave the operator confirming a figure they were never shown.
     */
    val canConfirm: Boolean get() = !isSubmitting && countedBalance != null && bookBalance != null
}
