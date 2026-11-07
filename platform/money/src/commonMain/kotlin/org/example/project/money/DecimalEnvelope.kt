package org.example.project.money

/**
 * The bounded domain in which java.math.BigDecimal, decimal.js and NSDecimalNumber are proven
 * to agree. Values and results outside it are rejected rather than delegated to an engine.
 */
object DecimalEnvelope {
    /** Digits of the unscaled value. NSDecimalNumber holds 38; this leaves headroom. */
    const val MAX_SIGNIFICANT_DIGITS: Int = 30

    /** Fractional digits. */
    const val MAX_SCALE: Int = 12

    /** Integer digits, so |v| < 10^18. */
    const val MAX_INT_DIGITS: Int = 18

    /**
     * @param precision digits of the unscaled value
     * @param scale fractional digits
     * @throws DecimalEnvelopeException when the value falls outside the envelope
     */
    fun check(
        precision: Int,
        scale: Int,
    ) {
        if (scale < 0) {
            throw DecimalEnvelopeException("negative scale $scale is not supported")
        }
        if (scale > MAX_SCALE) {
            throw DecimalEnvelopeException("scale $scale exceeds maximum $MAX_SCALE")
        }
        if (precision > MAX_SIGNIFICANT_DIGITS) {
            throw DecimalEnvelopeException(
                "precision $precision exceeds maximum $MAX_SIGNIFICANT_DIGITS",
            )
        }
        val intDigits = if (precision > scale) precision - scale else 0
        if (intDigits > MAX_INT_DIGITS) {
            throw DecimalEnvelopeException(
                "integer digits $intDigits exceeds maximum $MAX_INT_DIGITS",
            )
        }
    }
}
