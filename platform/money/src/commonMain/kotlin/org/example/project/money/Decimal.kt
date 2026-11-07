package org.example.project.money

import kotlinx.serialization.Serializable
import org.example.project.money.serialization.DecimalSerializer

/**
 * An arbitrary-scale decimal with java.math.BigDecimal semantics on every platform.
 *
 * There is deliberately no `Decimal(Double)` constructor and no `toDouble()`. Money carried
 * through a Double is what produced the truncation defects this module replaces; the only bridge
 * back is [toDoubleLossy], named so any money-math use is visible in review.
 *
 * [equals] is value-based, so `16.90 == 16.9000` and both hash identically — avoiding
 * java.math.BigDecimal's scale-sensitive equals, which silently breaks Set/Map lookups.
 * Use [equalsExact] when scale must match too.
 */
@Serializable(with = DecimalSerializer::class)
class Decimal internal constructor(
    internal val h: DecimalHandle,
) : Comparable<Decimal> {
    val scale: Int get() = DecimalEngine.scaleOf(h)
    val precision: Int get() = DecimalEngine.precisionOf(h)
    val signum: Int get() = DecimalEngine.signumOf(h)
    val isZero: Boolean get() = signum == 0
    val isNegative: Boolean get() = signum < 0

    operator fun plus(other: Decimal): Decimal = guard(DecimalEngine.add(h, other.h))

    operator fun minus(other: Decimal): Decimal = guard(DecimalEngine.subtract(h, other.h))

    operator fun times(other: Decimal): Decimal = guard(DecimalEngine.multiply(h, other.h))

    operator fun unaryMinus(): Decimal = guard(DecimalEngine.subtract(ZERO.h, h))

    fun abs(): Decimal = if (isNegative) -this else this

    fun divide(
        other: Decimal,
        scale: Int,
        rounding: MoneyRounding = MoneyRounding.HALF_UP,
    ): Decimal {
        if (other.isZero) throw DecimalArithmeticException("division by zero")
        DecimalEnvelope.check(precision = scale + 1, scale = scale)
        return guard(DecimalEngine.divide(h, other.h, scale, rounding))
    }

    /** Multiply and round to [scale] immediately, so repeated products cannot inflate scale. */
    fun times(
        other: Decimal,
        scale: Int,
        rounding: MoneyRounding,
    ): Decimal = guard(DecimalEngine.setScale(DecimalEngine.multiply(h, other.h), scale, rounding))

    fun setScale(
        scale: Int,
        rounding: MoneyRounding = MoneyRounding.HALF_UP,
    ): Decimal {
        DecimalEnvelope.check(precision = scale + 1, scale = scale)
        return guard(DecimalEngine.setScale(h, scale, rounding))
    }

    fun toPlainString(): String = DecimalEngine.toPlainString(h)

    /** Lossy conversion for charts and graphics only. Never use for money arithmetic. */
    fun toDoubleLossy(): Double = toPlainString().toDouble()

    override fun compareTo(other: Decimal): Int = DecimalEngine.compare(h, other.h)

    override fun equals(other: Any?): Boolean = other is Decimal && DecimalEngine.compare(h, other.h) == 0

    /**
     * Hashed from the value-normalised plain string with an explicit loop rather than
     * String.hashCode(), which Kotlin does not guarantee is identical across JVM/Native/JS.
     */
    override fun hashCode(): Int {
        var result = 0
        for (c in canonical()) result = 31 * result + c.code
        return result
    }

    /** True when value AND scale match. */
    fun equalsExact(other: Decimal): Boolean = compareTo(other) == 0 && scale == other.scale

    override fun toString(): String = toPlainString()

    /**
     * Value-normalised form: trailing fractional zeros dropped, so 16.9000 -> "16.9".
     *
     * The final check normalises a trimmed value whose digits are all zero to "0" regardless
     * of sign, so a negative zero (e.g. "-0.0", which trims to "-0") still canonicalises the
     * same as plain "0" — otherwise two values that [compareTo] as equal would hash
     * differently. java.math.BigDecimal has no negative zero, so the JVM path cannot produce
     * one; only a non-JVM engine could ever exercise this.
     */
    private fun canonical(): String {
        val plain = toPlainString()
        if (!plain.contains('.')) return plain
        val trimmed = plain.trimEnd('0').trimEnd('.')
        return if (trimmed.isEmpty() || trimmed.trimStart('-') == "0") "0" else trimmed
    }

    private fun guard(handle: DecimalHandle): Decimal {
        DecimalEnvelope.check(
            precision = DecimalEngine.precisionOf(handle),
            scale = DecimalEngine.scaleOf(handle),
        )
        return Decimal(handle)
    }

    companion object {
        val ZERO: Decimal = parse("0")
        val ONE: Decimal = parse("1")
        val HUNDRED: Decimal = parse("100")

        fun parse(text: String): Decimal = Decimal(DecimalEngine.fromPlainString(DecimalParser.parse(text).plain))

        fun parseOrNull(text: String): Decimal? =
            try {
                parse(text)
            } catch (e: MoneyException) {
                null
            }

        fun of(
            unscaled: Long,
            scale: Int,
        ): Decimal {
            if (scale < 0) throw DecimalEnvelopeException("negative scale $scale is not supported")
            val negative = unscaled < 0
            val digits =
                if (negative) {
                    // Avoid Long.MIN_VALUE overflow on negation.
                    unscaled.toString().substring(1)
                } else {
                    unscaled.toString()
                }
            val padded = digits.padStart(scale + 1, '0')
            val cut = padded.length - scale
            val plain = if (scale == 0) padded else "${padded.substring(0, cut)}.${padded.substring(cut)}"
            return parse(if (negative) "-$plain" else plain)
        }

        fun of(value: Int): Decimal = parse(value.toString())

        fun of(value: Long): Decimal = parse(value.toString())
    }
}
