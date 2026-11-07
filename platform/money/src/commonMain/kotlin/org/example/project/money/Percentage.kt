package org.example.project.money

import kotlinx.serialization.Serializable
import org.example.project.money.serialization.PercentageSerializer

/**
 * A rate held in PERCENT units: `19.00` means 19 %.
 *
 * Percent units are the storage and display form in this system — tax rates live in
 * DECIMAL(5,2) columns and VatRateNormalizer canonicalises to percent strings ("19,00" -> "19").
 * The server's arithmetic, however, needs a fraction (`BigDecimal.ONE + rate` must give 1.19).
 * Confusing the two is a silent 100x error, so the conversion lives here in [toFraction].
 */
@Serializable(with = PercentageSerializer::class)
data class Percentage(
    val percent: Decimal,
) {
    init {
        // toFraction() divides by 100, adding 2 to the scale, so cap the input scale.
        if (percent.scale > DecimalEnvelope.MAX_SCALE - 2) {
            throw DecimalEnvelopeException(
                "percentage scale ${percent.scale} exceeds ${DecimalEnvelope.MAX_SCALE - 2}, " +
                    "because toFraction() adds 2 to the scale",
            )
        }
    }

    /** 19.00 -> 0.1900. Exact: scale grows by exactly 2. */
    fun toFraction(): Decimal = percent.divide(Decimal.HUNDRED, percent.scale + 2, MoneyRounding.HALF_UP)

    /** 19.00 */
    fun toPercent(): Decimal = percent

    operator fun plus(other: Percentage): Percentage = Percentage(percent + other.percent)

    operator fun minus(other: Percentage): Percentage = Percentage(percent - other.percent)

    /** 10 % -> 90 %. */
    fun complement(): Percentage = Percentage(Decimal.HUNDRED - percent)

    override fun equals(other: Any?): Boolean = other is Percentage && percent == other.percent

    override fun hashCode(): Int = percent.hashCode()

    override fun toString(): String = "${percent.toPlainString()} %"

    companion object {
        val ZERO: Percentage = Percentage(Decimal.ZERO)

        fun ofPercent(value: Decimal): Percentage = Percentage(value)

        fun ofPercent(text: String): Percentage = Percentage(Decimal.parse(text))

        /** 0.19 -> 19 %. */
        fun ofFraction(value: Decimal): Percentage {
            val scale = if (value.scale >= 2) value.scale - 2 else 0
            return Percentage((value * Decimal.HUNDRED).setScale(scale, MoneyRounding.HALF_UP))
        }

        /** Percent units. Accepts VatRateNormalizer output such as "19" or "10.7". */
        fun parse(text: String): Percentage = Percentage(Decimal.parse(text))
    }
}
