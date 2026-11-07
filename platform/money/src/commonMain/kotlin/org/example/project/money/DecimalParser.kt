package org.example.project.money

/**
 * Strict, ASCII-only parser for the grammar `[+-]?digits[.digits]`.
 *
 * Exponent notation is deliberately rejected: java.math.BigDecimal accepts "1e5", decimal.js
 * accepts it, and NSDecimalNumber(string:) is locale-sensitive and unreliable. Non-ASCII digits
 * are rejected explicitly because java.math.BigDecimal("١٢") succeeds on the JVM via
 * Character.digit where the other engines fail.
 *
 * Negative zero is rejected for the same engine-parity reason, and is the third deliberate place
 * this parser is STRICTER than java.math.BigDecimal: BigDecimal accepts "-0.00" and silently
 * normalises it to 0.00, while decimal.js models negative zero as a distinct signed state.
 * Refusing it at the door is only half the fix — the other half normalises negative zero out of
 * engine OUTPUT in the wasm and iOS engines. Do not "simplify" this guard away.
 */
object DecimalParser {
    /** A validated, canonical decimal. [plain] never uses exponent notation. */
    data class Parsed(
        val plain: String,
        val scale: Int,
        val precision: Int,
    )

    fun parse(text: String): Parsed {
        if (text.isEmpty()) throw DecimalParseException(text, "empty input")

        var index = 0
        var negative = false
        when (text[0]) {
            '+' -> index = 1
            '-' -> {
                negative = true
                index = 1
            }
        }

        val body = text.substring(index)
        if (body.isEmpty()) throw DecimalParseException(text, "no digits after sign")

        val dot = body.indexOf('.')
        val intRaw: String
        val fracRaw: String
        if (dot < 0) {
            intRaw = body
            fracRaw = ""
        } else {
            intRaw = body.substring(0, dot)
            fracRaw = body.substring(dot + 1)
            if (intRaw.isEmpty()) throw DecimalParseException(text, "missing integer part")
            if (fracRaw.isEmpty()) throw DecimalParseException(text, "trailing decimal point")
            if (fracRaw.contains('.')) throw DecimalParseException(text, "multiple decimal points")
        }

        requireAsciiDigits(text, intRaw)
        requireAsciiDigits(text, fracRaw)

        val intPart = intRaw.trimStart('0').ifEmpty { "0" }
        val scale = fracRaw.length

        // Precision = digits of the unscaled value: leading zeros never count.
        val unscaled = (intPart + fracRaw).trimStart('0')
        val precision = if (unscaled.isEmpty()) 1 else unscaled.length

        val isZero = unscaled.isEmpty()
        if (negative && isZero) {
            throw DecimalParseException(text, "negative zero is not representable")
        }

        DecimalEnvelope.check(precision = precision, scale = scale)

        val sign = if (negative) "-" else ""
        val plain = if (scale == 0) "$sign$intPart" else "$sign$intPart.$fracRaw"
        return Parsed(plain = plain, scale = scale, precision = precision)
    }

    private fun requireAsciiDigits(
        original: String,
        segment: String,
    ) {
        for (c in segment) {
            if (c < '0' || c > '9') {
                throw DecimalParseException(original, "unexpected character '$c'")
            }
        }
    }
}
