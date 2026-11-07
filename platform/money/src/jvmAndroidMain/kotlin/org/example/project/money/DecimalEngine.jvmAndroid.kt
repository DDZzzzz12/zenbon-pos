package org.example.project.money

import java.math.BigDecimal
import java.math.RoundingMode

internal actual class DecimalHandle(
    val value: BigDecimal,
)

private fun MoneyRounding.toJava(): RoundingMode =
    when (this) {
        MoneyRounding.HALF_UP -> RoundingMode.HALF_UP
        MoneyRounding.HALF_EVEN -> RoundingMode.HALF_EVEN
        MoneyRounding.DOWN -> RoundingMode.DOWN
        MoneyRounding.UP -> RoundingMode.UP
        MoneyRounding.FLOOR -> RoundingMode.FLOOR
        MoneyRounding.CEILING -> RoundingMode.CEILING
    }

internal actual object DecimalEngine {
    actual fun fromPlainString(s: String): DecimalHandle = DecimalHandle(BigDecimal(s))

    actual fun toPlainString(h: DecimalHandle): String = h.value.toPlainString()

    actual fun add(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle = DecimalHandle(a.value.add(b.value))

    actual fun subtract(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle = DecimalHandle(a.value.subtract(b.value))

    actual fun multiply(
        a: DecimalHandle,
        b: DecimalHandle,
    ): DecimalHandle = DecimalHandle(a.value.multiply(b.value))

    actual fun divide(
        a: DecimalHandle,
        b: DecimalHandle,
        scale: Int,
        mode: MoneyRounding,
    ): DecimalHandle = DecimalHandle(a.value.divide(b.value, scale, mode.toJava()))

    actual fun setScale(
        h: DecimalHandle,
        scale: Int,
        mode: MoneyRounding,
    ): DecimalHandle = DecimalHandle(h.value.setScale(scale, mode.toJava()))

    actual fun compare(
        a: DecimalHandle,
        b: DecimalHandle,
    ): Int = a.value.compareTo(b.value)

    actual fun scaleOf(h: DecimalHandle): Int = h.value.scale()

    actual fun precisionOf(h: DecimalHandle): Int = h.value.precision()

    actual fun signumOf(h: DecimalHandle): Int = h.value.signum()
}
