package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the output of [toSignedDecimalString2] / [toDecimalString2].
 *
 * These exist because sub-project E2 replaced this expression in `AuditLogScreen`:
 *
 * ```
 * if (amount >= 0) "+%.2f".format(amount) else "%.2f".format(amount)
 * ```
 *
 * with `amount.toSignedDecimalString2()`. `String.format` is a JVM-only stdlib API, so the screen
 * could not compile once it moved to `shared-ui`, which also targets wasm. The replacement has to
 * produce byte-identical output for the audit log to keep reading the same, and the transaction
 * tab that renders it needs bill data to exercise — which a fresh database has none of. These
 * assertions are that check.
 *
 * Only values that are exactly representable in binary floating point are asserted. Half-way cases
 * like 12.345 are deliberately omitted: they are stored as 12.3449999… and any assertion on them
 * would pin an artefact of the literal rather than the formatter's contract.
 */
class AmountFormattingTest {
    @Test
    fun signed_positive_getsExplicitPlus() {
        assertEquals("+12.50", 12.5.toSignedDecimalString2())
    }

    @Test
    fun signed_zero_countsAsNonNegative() {
        assertEquals("+0.00", 0.0.toSignedDecimalString2())
    }

    @Test
    fun signed_negative_keepsMinusAndGainsNoPlus() {
        assertEquals("-19.11", (-19.11).toSignedDecimalString2())
    }

    @Test
    fun signed_padsSingleDigitFraction() {
        assertEquals("-0.50", (-0.5).toSignedDecimalString2())
    }

    @Test
    fun signed_wholeNumber_getsTwoFractionDigits() {
        assertEquals("+1234.00", 1234.0.toSignedDecimalString2())
    }

    @Test
    fun unsigned_positive_hasNoPlus() {
        assertEquals("12.50", 12.5.toDecimalString2())
    }

    @Test
    fun unsigned_negative_keepsMinus() {
        assertEquals("-19.11", (-19.11).toDecimalString2())
    }
}
