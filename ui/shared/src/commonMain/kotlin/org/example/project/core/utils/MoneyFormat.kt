/**
 * Money string helpers that carry no Compose or app-state dependency.
 *
 * These parse and normalize money as an exact [Decimal] — never through `Double` or integer cents.
 * They live in `shared-ui` rather than `composeApp` because `PosTextField` needs them and
 * `composeApp` depends on `shared-ui`, not the other way round. The `@Composable` currency helpers
 * stay in `composeApp`'s `CurrencyFormatUtils.kt`: they read the active currency from
 * `LocalSettingManager`, which is app state and cannot move down here.
 *
 * The package is deliberately unchanged (`org.example.project.core.utils`), so every existing call
 * site keeps resolving without an import change. The FILE name, however, must stay different from
 * `CurrencyFormatUtils.kt` — two same-package, same-named files in two modules compile fine but
 * produce colliding JVM facade classes that fail at runtime.
 *
 * @since 1.0.0
 */
package org.example.project.core.utils

import org.example.project.core.domain.enums.setup.Language
import org.example.project.money.Decimal
import kotlin.math.roundToInt

/**
 * Lenient parse of a server money string to an exact [Decimal], returning [Decimal.ZERO] when the
 * string is empty or malformed rather than throwing — so a display or aggregation never crashes on
 * bad input. Where the value is known-valid and an exception is the correct response, call
 * [Decimal.parse] directly instead.
 */
fun String.toDecimalOrZero(): Decimal = Decimal.parseOrNull(this) ?: Decimal.ZERO

/**
 * Parses money a PERSON typed: `,` or `.` as the decimal separator, an optional sign, surrounding
 * whitespace ignored; null when blank or anything else — including thousands separators, which are
 * ambiguous and refused rather than guessed. Never use [toDecimalOrZero] for typed input: it is for
 * server strings and turns "150,00" into zero (E2E-2026-09-18 #6).
 */
fun String.toUserInputDecimalOrNull(): Decimal? {
    val trimmed = trim()
    if (trimmed.count { it == ',' || it == '.' } > 1) return null
    // A trailing separator is a person half-way through typing "3,50": "3," means 3, not "no amount"
    // (F65). Decimal.parse refuses "3." outright, so the separator is dropped before it gets there.
    // A lone "," still has no digits left and stays null.
    val withoutTrailingSeparator = trimmed.removeSuffix(",").removeSuffix(".")
    return Decimal.parseOrNull(withoutTrailingSeparator.replace(",", "."))
}

/**
 * A canonical dot-decimal amount as a field SEEDS it for [language]: the decimal comma in German,
 * where a dot read as a stranger in a German form beside amounts shown with the comma. Typed input
 * accepts both ([toUserInputDecimalOrNull]); this only decides what the operator is shown first.
 */
fun String.withDecimalSeparatorOf(language: Language): String = if (language == Language.GERMAN) replace('.', ',') else this

/**
 * Normalizes a user-entered money string (German comma or dot separator) to a canonical
 * dot-decimal wire string, or null when blank or unparseable. Use at the client→server boundary
 * where the DTO carries money as a String — it drops the old `String.toDoubleOrNull()` hop so no
 * money crosses through `Double`.
 */
fun String.toCanonicalMoneyOrNull(): String? = toUserInputDecimalOrNull()?.toPlainString()

/**
 * [toCanonicalMoneyOrNull] for a price: additionally null when negative. The editor's guard before a
 * product or extra price is sent (admin21 NOTE-d21: a raw "12,90" reached the server as-is).
 */
fun String.toCanonicalPriceOrNull(): String? = toUserInputDecimalOrNull()?.takeIf { it >= Decimal.ZERO }?.toPlainString()

/**
 * A server decimal as an editor seeds it: trailing zeros beyond [minScale] dropped, never a
 * significant digit — "1.5000" → "1.50", "12.9000" → "12.90", "16.8950" → "16.895", "19" → "19.00".
 * The DB holds `DECIMAL(19,4)`, and showing those padding zeros read as a different value
 * (admin21 NOTE-e21). Input that is not a decimal is returned unchanged.
 */
fun String.toEditableDecimal(minScale: Int = 2): String {
    val parsed = Decimal.parseOrNull(trim()) ?: return this
    val plain = parsed.toPlainString()
    val dot = plain.indexOf('.')
    val decimals = if (dot < 0) 0 else plain.length - dot - 1
    if (decimals <= minScale) return parsed.setScale(minScale).toPlainString()
    var end = plain.length
    while (end - dot - 1 > minScale && plain[end - 1] == '0') end--
    return plain.substring(0, end).removeSuffix(".")
}

/**
 * Convert discount multiplier to percentage.
 * Example: 0.9 (10% off) -> 10
 */
fun Double.discountToPercentage(): Int = ((1 - this) * 100).roundToInt()
