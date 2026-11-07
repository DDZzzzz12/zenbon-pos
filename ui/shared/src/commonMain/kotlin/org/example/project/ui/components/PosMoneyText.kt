package org.example.project.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.example.project.money.Decimal
import org.example.project.money.DecimalSeparator
import org.example.project.money.MoneyException
import org.example.project.money.MoneyFormat
import org.example.project.money.MoneyRounding
import org.example.project.money.format

/** Rendered when an amount cannot be parsed — a display must never crash a screen. */
private const val UNPARSEABLE = "—"

/** Symbols for the currencies this system actually uses; anything else shows its ISO code. */
private fun symbolFor(currencyCode: String): String? =
    when (currencyCode) {
        "EUR" -> "€"
        "CHF" -> "CHF"
        "USD" -> "$"
        "" -> null
        else -> currencyCode
    }

/**
 * Formats a server money string for display.
 *
 * Pure and Compose-free so it can be unit-tested and reused from ViewModels, sorting and exports.
 * Never throws: malformed input renders [UNPARSEABLE] rather than taking down the screen.
 *
 * Both the parse AND the format must stay inside the try. Formatting rounds, and rounding can
 * carry into a new integer digit — "999999999999999999.995" parses at exactly the envelope's 18
 * integer digits, then HALF_UP carries it to 19 and setScale's guard throws. Narrowing the try to
 * the parse alone lets that escape into a Compose crash.
 *
 * ## German number format in every UI language, deliberately (FO-29)
 *
 * Amounts render as "1.234,56 €" in the English and Chinese UI too: the shop's locale, not the
 * operator's UI language, decides the number format. A figure on screen must read exactly like the
 * same figure on the printed receipt and in the DATEV export, so a bookkeeper comparing them never
 * has to translate separators. This is not a missing localisation; do not "fix" it per language.
 *
 * ## The minus is an ASCII hyphen, deliberately
 *
 * It used to be U+2212 MINUS SIGN, which is typographically correct and digit-width — and which is
 * **not in adminApp's CJK font subset**. `AdminTheme` applies that subset to the whole type scale
 * and Compose does not fall back by glyph coverage, so the sign rendered as *nothing at all*: a
 * −42,30 € debit was drawn as "42,30 €", indistinguishable from a credit. Exactly the failure that
 * hid the euro symbol until the subset was widened (see the font README).
 *
 * U+002D is in every font, including that subset, so the sign cannot vanish again. Widening the
 * subset instead would fix this one glyph and leave the next one to be discovered the same way — by
 * someone noticing a number that looks wrong.
 */
fun formatMoneyForDisplay(
    amount: String,
    currencyCode: String,
    forceSign: Boolean = false,
): String =
    try {
        val displayed = Decimal.parse(amount).setScale(DISPLAY_SCALE, MoneyRounding.HALF_UP)
        // A zero is neither income nor expense: "+0,00 €" / "-0,00 €" read as a booking that
        // did not happen (emulator pass 4, NOTE-3). Checked on the *rounded* value so "-0.004"
        // cannot print "-0,00" either.
        val unsigned = if (displayed.isZero) Decimal.ZERO else displayed
        unsigned.format(
            MoneyFormat(
                separator = DecimalSeparator.COMMA,
                groupSeparator = ".",
                minFractionDigits = DISPLAY_SCALE,
                maxFractionDigits = DISPLAY_SCALE,
                symbol = symbolFor(currencyCode),
                forceSign = forceSign && !displayed.isZero,
                minusSign = "-",
            ),
        )
    } catch (e: MoneyException) {
        UNPARSEABLE
    }

/** Money is always displayed with two fraction digits. */
private const val DISPLAY_SCALE = 2

/**
 * Displays a money amount that arrived from the server as a string, formatted for the shop's
 * currency.
 *
 * The currency comes from the response DTO, not a client constant — see the migration spec.
 * Formatting happens here, at the edge; the value stays a string everywhere upstream and its
 * formatted output is never parsed back.
 */
@Composable
fun PosMoneyText(
    amount: String,
    currencyCode: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    forceSign: Boolean = false,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    softWrap: Boolean = false,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = formatMoneyForDisplay(amount, currencyCode, forceSign),
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = overflow,
    )
}
