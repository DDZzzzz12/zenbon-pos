package org.example.project.money

/**
 * decimal.js normalises trailing zeros, so scale is tracked in Kotlin and each result is
 * rendered with toFixed(scale). Scale rules are therefore deterministic Kotlin logic rather
 * than engine behaviour, which raises parity confidence.
 */
internal actual class DecimalHandle(
    val digits: String,
    val scale: Int,
)

internal actual object DecimalEngine {
    actual fun fromPlainString(s: String): DecimalHandle {
        val dot = s.indexOf('.')
        val scale = if (dot < 0) 0 else s.length - dot - 1
        return DecimalHandle(s, scale)
    }

    actual fun toPlainString(h: DecimalHandle): String = h.digits

    actual fun add(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle {
        val scale = maxOf(a.scale, b.scale)
        return DecimalHandle(shimAdd(a.digits, b.digits, scale), scale)
    }

    actual fun subtract(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle {
        val scale = maxOf(a.scale, b.scale)
        return DecimalHandle(shimSubtract(a.digits, b.digits, scale), scale)
    }

    actual fun multiply(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle {
        val scale = a.scale + b.scale
        return DecimalHandle(shimMultiply(a.digits, b.digits, scale), scale)
    }

    actual fun divide(
        a: DecimalHandle,
        b: DecimalHandle,
        scale: Int,
        mode: MoneyRounding,
    ): DecimalHandle = DecimalHandle(shimDivide(a.digits, b.digits, scale, mode.ordinal), scale)

    actual fun setScale(
        h: DecimalHandle,
        scale: Int,
        mode: MoneyRounding,
    ): DecimalHandle = DecimalHandle(shimSetScale(h.digits, scale, mode.ordinal), scale)

    actual fun compare(
        a: DecimalHandle,
        b: DecimalHandle,
    ): Int = shimCompare(a.digits, b.digits)

    actual fun scaleOf(h: DecimalHandle): Int = h.scale

    actual fun precisionOf(h: DecimalHandle): Int {
        // decimal.js precision(true) counts significant digits of the normalised value, which
        // matches BigDecimal.precision() except for zero and for trailing zeros the engine drops.
        // Derive it from the tracked plain string instead so the two agree exactly.
        val body = h.digits.removePrefix("-").replace(".", "")
        val unscaled = body.trimStart('0')
        return if (unscaled.isEmpty()) 1 else unscaled.length
    }

    actual fun signumOf(h: DecimalHandle): Int = shimSignum(h.digits)
}
