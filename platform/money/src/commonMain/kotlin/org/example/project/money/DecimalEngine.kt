package org.example.project.money

/** Opaque, engine-specific representation of one decimal value plus its scale. */
internal expect class DecimalHandle

/**
 * The entire per-platform surface of this module: eleven primitives.
 *
 * Everything else — parsing, envelope guards, equals/hashCode, formatting, Money, Percentage,
 * allocation, aggregation, serialization — is common code and therefore cannot diverge between
 * platforms.
 *
 * Contract every engine must satisfy (java.math.BigDecimal semantics):
 * - [add] / [subtract] produce `max(scaleA, scaleB)`
 * - [multiply] produces `scaleA + scaleB` exactly
 * - [divide] / [setScale] produce exactly the requested scale
 * - [toPlainString] never emits exponent notation and preserves trailing zeros
 * - [compare] compares by value, ignoring scale
 * - callers guarantee the divisor is non-zero and the input string is already validated
 */
internal expect object DecimalEngine {
    fun fromPlainString(s: String): DecimalHandle

    fun toPlainString(h: DecimalHandle): String

    fun add(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle

    fun subtract(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle

    fun multiply(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle

    fun divide(
        a: DecimalHandle,
        b: DecimalHandle,
        scale: Int,
        mode: MoneyRounding,
    ): DecimalHandle

    fun setScale(
        h: DecimalHandle,
        scale: Int,
        mode: MoneyRounding,
    ): DecimalHandle

    fun compare(
        a: DecimalHandle,
        b: DecimalHandle,
    ): Int

    fun scaleOf(h: DecimalHandle): Int

    fun precisionOf(h: DecimalHandle): Int

    fun signumOf(h: DecimalHandle): Int
}
