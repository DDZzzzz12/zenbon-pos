package org.example.project.admin.features.kassenbuch.presentation

import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal

/**
 * One line of a Beleg as the operator typed it: a signed gross amount and its VAT rate.
 *
 * Both stay **strings** all the way to the request. `EntryLineDto.amount` is an exact decimal string
 * on the wire, and the only conversion this class performs is an exact `Decimal` parse — never a
 * `Double`, whose nearest representation of a price is routinely a cent short.
 *
 * A blank amount is neither valid nor an error yet: it is an untouched field, and complaining about
 * it before anything has been typed is noise.
 */
data class EntryLineInput(
    val amount: String = "",
    val vatRate: String = "",
) {
    /**
     * The typed amount, or `null` when it is blank or unreadable.
     *
     * Normalization goes through [toCanonicalMoneyOrNull], the one helper the whole client uses to
     * fold the German decimal comma — an operator entering a Beleg types `23,80`, and a bare
     * `Decimal.parse` would read that as nothing at all. The re-parse of its canonical output is
     * exact, so no value passes through a `Double`.
     */
    val parsedAmount: Decimal?
        get() = amount.toCanonicalMoneyOrNull()?.let { Decimal.parse(it) }

    /**
     * True once the amount can go on a request.
     *
     * Kept apart from [hasVatRate] so the form can say *which* half of the line is still missing,
     * rather than only that the line is not yet usable.
     */
    val hasUsableAmount: Boolean
        get() = parsedAmount?.isZero == false

    /** True once this line names a VAT rate. Blank is not a rate — the server's matrix demands one. */
    val hasVatRate: Boolean
        get() = vatRate.isNotBlank()

    /** True only for input that was typed and cannot be read — a blank field is not an error. */
    val hasUnusableAmount: Boolean
        get() = amount.isNotBlank() && (parsedAmount == null || parsedAmount?.isZero == true)
}
