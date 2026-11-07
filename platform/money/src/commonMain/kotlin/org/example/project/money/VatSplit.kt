package org.example.project.money

/**
 * A gross amount decomposed into net and VAT, with `net + vat == gross` guaranteed.
 *
 * The invariant is why this type exists. The server derives VAT as `gross - net` by subtraction
 * in every DSFinV-K path, which is what keeps the parts summing to the whole with no rounding
 * drift — required for receipts and fiscal export. Exposing separate net and VAT helpers would
 * let a caller compute them independently and break that silently.
 */
data class VatSplit(
    val gross: Money,
    val net: Money,
    val vat: Money,
    val rate: Percentage,
) {
    init {
        if (net + vat != gross) {
            throw DecimalArithmeticException(
                "VAT split does not sum: ${net.toPlainString()} + ${vat.toPlainString()} " +
                    "!= ${gross.toPlainString()}",
            )
        }
    }
}

/**
 * Split a gross amount, reproducing the server formula exactly:
 * `net = gross / (1 + rate.toFraction())` at [scale] with [rounding], then `vat = gross - net`.
 *
 * The defaults match `DsfinvkExportServiceImpl` (scale 2, HALF_UP), so a call with no explicit
 * arguments agrees with the server by construction.
 */
fun Money.vatSplitFromGross(
    rate: Percentage,
    scale: Int = 2,
    rounding: MoneyRounding = MoneyRounding.HALF_UP,
): VatSplit {
    val gross = setScale(scale, rounding)
    val divisor = Decimal.ONE + rate.toFraction()
    val net = Money(gross.amount.divide(divisor, scale, rounding), currency)
    return VatSplit(gross = gross, net = net, vat = gross - net, rate = rate)
}

/**
 * Split a net amount: `gross = net * (1 + rate.toFraction())` at [scale], then `vat = gross - net`
 * — subtraction again, so the invariant holds in both directions.
 */
fun Money.vatSplitFromNet(
    rate: Percentage,
    scale: Int = 2,
    rounding: MoneyRounding = MoneyRounding.HALF_UP,
): VatSplit {
    val net = setScale(scale, rounding)
    val multiplier = Decimal.ONE + rate.toFraction()
    val gross = Money(net.amount.times(multiplier, scale, rounding), currency)
    return VatSplit(gross = gross, net = net, vat = gross - net, rate = rate)
}
