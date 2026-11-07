package org.example.project.admin.core.presentation.format

import org.example.project.money.Decimal

/**
 * A server decimal string reduced to the scale that carries information, for **prefilling an editor
 * field**: `"18.0000"` → `"18.00"`, `"40.0000"` → `"40"`, `"7.5000"` → `"7.50"`.
 *
 * Storage holds `DECIMAL(19,4)`, so the raw wire string shows four places of noise in a text field.
 * Trailing zeros are dropped, but never below [minFractionDigits] (2 for money, 0 for a quantity).
 * Pure string work on the plain representation — the value is unchanged, so it round-trips through
 * `toCanonicalMoneyOrNull()` to the same number — and nothing passes through a `Double`. Input that
 * is not a decimal is returned untouched (a blank stays blank).
 */
fun editableDecimalText(
    raw: String,
    minFractionDigits: Int,
): String {
    val value = Decimal.parseOrNull(raw.trim()) ?: return raw
    val plain = value.toPlainString()
    val whole = plain.substringBefore('.')
    val fraction = plain.substringAfter('.', "").trimEnd('0').padEnd(minFractionDigits, '0')
    return if (fraction.isEmpty()) whole else "$whole.$fraction"
}
