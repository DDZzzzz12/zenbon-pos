package org.example.project.money

import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSDecimalNumberHandler
import platform.Foundation.NSRoundingMode

/**
 * NSDecimalNumber does not preserve trailing zeros the way BigDecimal does, so scale is tracked
 * in Kotlin exactly as on wasmJs, and every result is re-rendered at its computed scale.
 */
internal actual class DecimalHandle(
    val value: NSDecimalNumber,
    val scale: Int,
)

/** NSDecimalNumber renders a not-a-number result as this exact string. */
private const val NOT_A_NUMBER = "NaN"

/**
 * Apple's NSRoundDown/NSRoundUp mean FLOOR/CEILING, not toward/away from zero, so
 * [MoneyRounding.DOWN] and [MoneyRounding.UP] are emulated sign-dependently: DOWN is FLOOR for a
 * positive value and CEILING for a negative one, UP is the inverse. The other four map directly.
 *
 * [negative] must be the sign of the value that is actually being rounded, which for division is
 * the sign of the QUOTIENT rather than of either operand.
 */
private fun handler(
    scale: Int,
    mode: MoneyRounding,
    negative: Boolean,
): NSDecimalNumberHandler {
    val appleMode =
        when (mode) {
            MoneyRounding.HALF_UP -> NSRoundingMode.NSRoundPlain
            MoneyRounding.HALF_EVEN -> NSRoundingMode.NSRoundBankers
            MoneyRounding.FLOOR -> NSRoundingMode.NSRoundDown
            MoneyRounding.CEILING -> NSRoundingMode.NSRoundUp
            MoneyRounding.DOWN -> if (negative) NSRoundingMode.NSRoundUp else NSRoundingMode.NSRoundDown
            MoneyRounding.UP -> if (negative) NSRoundingMode.NSRoundDown else NSRoundingMode.NSRoundUp
        }
    return NSDecimalNumberHandler.decimalNumberHandlerWithRoundingMode(
        roundingMode = appleMode,
        scale = scale.toShort(),
        raiseOnExactness = false,
        raiseOnOverflow = true,
        raiseOnUnderflow = true,
        raiseOnDivideByZero = true,
    )
}

private fun NSDecimalNumber.isNegativeValue(): Boolean = compare(NSDecimalNumber.zero) < 0L

private fun NSDecimalNumber.renderPlain(scale: Int): String {
    // The tracked scale always represents the value exactly (add/subtract take max(a, b),
    // multiply takes a + b, divide and setScale round to the requested scale), so this pass only
    // normalises the fraction-digit count before padding and the rounding mode cannot matter.
    val rounded =
        decimalNumberByRoundingAccordingToBehavior(
            handler(scale, MoneyRounding.HALF_UP, negative = false),
        )
    // descriptionWithLocale(nil) is NSDecimalNumber's own renderer and is what NSNumber.stringValue
    // delegates to; calling it directly keeps the value off any NSNumber double conversion.
    val raw = rounded.descriptionWithLocale(null)
    if (raw == NOT_A_NUMBER) throw DecimalArithmeticException("NSDecimalNumber produced notANumber")
    return normalizeZero(padToScale(raw, scale))
}

/** NSDecimalNumber drops trailing zeros; restore them so output matches BigDecimal.toPlainString. */
private fun padToScale(
    raw: String,
    scale: Int,
): String {
    if (scale == 0) return raw.substringBefore('.')
    val dot = raw.indexOf('.')
    return if (dot < 0) {
        raw + "." + "0".repeat(scale)
    } else {
        val frac = raw.substring(dot + 1)
        when {
            frac.length == scale -> raw
            frac.length < scale -> raw + "0".repeat(scale - frac.length)
            else -> raw.substring(0, dot + 1 + scale)
        }
    }
}

/**
 * VERIFIED DIVERGENCE: java.math.BigDecimal never emits negative zero —
 * `BigDecimal("-0.004").setScale(2, HALF_UP)` is `"0.00"`, not `"-0.00"`.
 * Strip the sign whenever every digit is zero, matching the same guard in the wasm shim.
 * The all-zero test is what keeps the guard from over-reaching: FLOOR on -0.004 is a genuine
 * "-0.01" and must keep its sign.
 */
private fun normalizeZero(text: String): String = if (text.startsWith('-') && text.none { it in '1'..'9' }) text.substring(1) else text

internal actual object DecimalEngine {
    actual fun fromPlainString(s: String): DecimalHandle {
        val dot = s.indexOf('.')
        val scale = if (dot < 0) 0 else s.length - dot - 1
        return DecimalHandle(NSDecimalNumber(string = s), scale)
    }

    actual fun toPlainString(h: DecimalHandle): String = h.value.renderPlain(h.scale)

    actual fun add(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle = DecimalHandle(a.value.decimalNumberByAdding(b.value), maxOf(a.scale, b.scale))

    actual fun subtract(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle = DecimalHandle(a.value.decimalNumberBySubtracting(b.value), maxOf(a.scale, b.scale))

    actual fun multiply(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle = DecimalHandle(a.value.decimalNumberByMultiplyingBy(b.value), a.scale + b.scale)

    actual fun divide(
        a: DecimalHandle,
        b: DecimalHandle,
        scale: Int,
        mode: MoneyRounding,
    ): DecimalHandle {
        // The sign of the quotient, which is what DOWN/UP emulation has to be relative to.
        val negative = a.value.isNegativeValue() != b.value.isNegativeValue()
        val quotient = a.value.decimalNumberByDividingBy(b.value, handler(scale, mode, negative))
        return DecimalHandle(quotient, scale)
    }

    actual fun setScale(
        h: DecimalHandle,
        scale: Int,
        mode: MoneyRounding,
    ): DecimalHandle {
        val rounded =
            h.value.decimalNumberByRoundingAccordingToBehavior(
                handler(scale, mode, h.value.isNegativeValue()),
            )
        return DecimalHandle(rounded, scale)
    }

    /** NSComparisonResult is an NSInteger, so normalise it to exactly -1 / 0 / 1. */
    actual fun compare(
        a: DecimalHandle,
        b: DecimalHandle,
    ): Int {
        val order = a.value.compare(b.value)
        return when {
            order < 0L -> -1
            order > 0L -> 1
            else -> 0
        }
    }

    actual fun scaleOf(h: DecimalHandle): Int = h.scale

    actual fun precisionOf(h: DecimalHandle): Int {
        // Derived from the tracked plain string rather than from NSDecimalNumber, whose own
        // digit count drops the trailing zeros that BigDecimal.precision() counts.
        val body = toPlainString(h).removePrefix("-").replace(".", "")
        val unscaled = body.trimStart('0')
        return if (unscaled.isEmpty()) 1 else unscaled.length
    }

    actual fun signumOf(h: DecimalHandle): Int {
        val order = h.value.compare(NSDecimalNumber.zero)
        return when {
            order < 0L -> -1
            order > 0L -> 1
            else -> 0
        }
    }
}
