package org.example.project.admin.features.documents.presentation.capture

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding

/** `MONEY_SCALE` in `LegacyInvoiceSync`. The divisor is taken two digits wider, exactly as there. */
private const val MONEY_SCALE = 2
private const val DIVISOR_SCALE = MONEY_SCALE + 2

/**
 * `(taxable, vat)` for a gross at a rate, with the VAT taken **by subtraction** so
 * `taxable + vat == gross` holds exactly.
 *
 * This is `LegacyInvoiceSync.splitGross` transliterated into `:platform-money` `Decimal` — same operations,
 * same scales, same rounding — because the DATEV export sums the pair back into the entry amount and
 * two derivations that disagree by a cent move money on the ledger. Do not "simplify" it to
 * `gross * rate / (100 + rate)`.
 *
 * Returns `null` when either input will not parse, so the caller renders nothing rather than a
 * plausible wrong number. [rate] is in **percent units** (`"19"`, not `"0.19"`), matching the DB and
 * `Percentage`; confusing the two is a silent 100× error.
 */
internal fun deriveVatRow(
    rate: String,
    gross: String,
): DocumentDto.VatBreakdownDto? {
    val parsedRate = Decimal.parseOrNull(rate.trim().replace(",", ".")) ?: return null
    val parsedGross = Decimal.parseOrNull(gross.toCanonicalMoneyOrNull() ?: return null) ?: return null

    val divisor = Decimal.ONE + parsedRate.divide(Decimal.HUNDRED, DIVISOR_SCALE, MoneyRounding.HALF_UP)
    val taxable = parsedGross.divide(divisor, MONEY_SCALE, MoneyRounding.HALF_UP)
    val vat = parsedGross - taxable

    return DocumentDto.VatBreakdownDto(
        vatCategoryCode = if (parsedRate.signum > 0) "S" else "Z",
        vatRate = rate.trim(),
        taxableAmount = taxable.toPlainString(),
        vatAmount = vat.toPlainString(),
    )
}
