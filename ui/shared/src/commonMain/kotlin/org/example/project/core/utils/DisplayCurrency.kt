package org.example.project.core.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import org.example.project.money.Decimal
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * The shop's currency, for composables that format money without being handed one.
 *
 * These formatters used to read `composeApp`'s `LocalSettingManager` directly, which pinned every
 * caller to that module — the report rows could not move down into `shared-ui` for adminApp to
 * reuse without dragging the whole setting manager with them. A plain string local is the smallest
 * thing they actually need: each app provides it from its own configuration.
 *
 * The default is a fallback for previews and tests, not a business rule. An app that shows money
 * must provide this.
 */
val LocalDisplayCurrency = compositionLocalOf { "EUR" }

/**
 * Format an exact [Decimal] money amount for display in the shop's currency.
 *
 * Goes through [formatMoneyForDisplay], which parses and formats via `:platform-money` — no `Double`, no
 * cents.
 */
@Composable
fun Decimal.toFormattedCurrency(): String = formatMoneyForDisplay(this.toPlainString(), LocalDisplayCurrency.current)

/**
 * Format a server money string for display in the shop's currency: the same "16,90 €" the
 * [Decimal] overload renders, so no screen mixes it with a raw "16.90 EUR".
 *
 * This used to append the currency code to the server's decimal as-is, a behaviour kept
 * byte-identical when the file moved to `shared-ui`. The switch to [formatMoneyForDisplay] is
 * that deliberate decision, taken for receipts, table cards, Z-Bon and the daily report alike.
 *
 * [deleted] prefixes a minus, as before (a voided payment line on the receipt). Input that is
 * not a decimal is returned unchanged; see [formatCurrencyAmount].
 */
@Composable
fun String.toFormattedCurrency(deleted: Boolean = false): String = formatCurrencyAmount(this, LocalDisplayCurrency.current, deleted)

/**
 * The Compose-free half of [String.toFormattedCurrency], public so it can be unit-tested and reused.
 *
 * Input that is not a decimal comes back unchanged rather than as the formatter's "—": these call
 * sites have always shown whatever the server sent, and a placeholder would hide a value the
 * operator may still need to read.
 */
fun formatCurrencyAmount(
    amount: String,
    currencyCode: String,
    deleted: Boolean = false,
): String {
    if (Decimal.parseOrNull(amount) == null) return amount
    val formatted = formatMoneyForDisplay(amount, currencyCode)
    return if (deleted) "-$formatted" else formatted
}
