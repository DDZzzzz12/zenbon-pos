package org.example.project.admin.features.documents.presentation.capture

import kotlinx.datetime.LocalDate
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal

/** Characters the DATEV export keeps in `<invoiceId>`; everything else is silently stripped. */
private val INVOICE_NUMBER_ALLOWED = Regex("""^[A-Za-z0-9${'$'}%&*+\-/]*$""")
private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

private const val INVOICE_NUMBER_MAX = 36
private const val SELLER_NAME_MAX = 50
private const val BOOKING_TEXT_MAX = 60

/**
 * Every rule that makes a DATEV import fail outright, checked before a save is dispatched.
 *
 * Blocking rules only — a warning is not an error, and a document worth recording must stay
 * recordable. In particular a **zero gross is valid**: a Nullrechnung (warranty replacement,
 * free-of-charge delivery, a correction that nets to zero) is a real document. The export skips it
 * and says so; capture does not block it.
 *
 * Pure by design: no Compose, no ViewModel, no suspend. The whole table in the spec's "Form
 * validation" section is therefore provable by plain `kotlin.test`.
 */
internal fun validateCapture(form: InvoiceCaptureForm): Map<InvoiceCaptureField, StringKey> =
    buildMap {
        validateInvoiceNumber(form)?.let { put(InvoiceCaptureField.INVOICE_NUMBER, it) }
        validateIssueDate(form)?.let { put(InvoiceCaptureField.ISSUE_DATE, it) }
        validateGrossTotal(form)?.let { put(InvoiceCaptureField.GROSS_TOTAL, it) }
        validateSellerName(form)?.let { put(InvoiceCaptureField.SELLER_NAME, it) }
        validatePrecedingReference(form)?.let { put(InvoiceCaptureField.PRECEDING_REFERENCE, it) }
        validateVatRowSum(form)?.let { put(InvoiceCaptureField.VAT_ROW_SUM, it) }
        validateBooking(form)
    }

private fun MutableMap<InvoiceCaptureField, StringKey>.validateBooking(form: InvoiceCaptureForm) {
    if (form.bpAccountNo.isNotBlank() && form.bpAccountNo.trim().toLongOrNull() !in 10_000L..999_999_999L) {
        put(InvoiceCaptureField.BP_ACCOUNT_NO, AdminStringKeys.InvoiceCaptureErrorBpAccountRange)
    }
    if (form.accountNo.isNotBlank() && form.accountNo.trim().toLongOrNull() !in 1L..999_999_999L) {
        put(InvoiceCaptureField.ACCOUNT_NO, AdminStringKeys.InvoiceCaptureErrorAccountRange)
    }
    if (form.bookingText.trim().length > BOOKING_TEXT_MAX) {
        put(InvoiceCaptureField.BOOKING_TEXT, AdminStringKeys.InvoiceCaptureErrorBookingTextTooLong)
    }
}

private fun validateInvoiceNumber(form: InvoiceCaptureForm): StringKey? {
    val value = form.invoiceNumber.trim()
    if (value.isBlank()) return AdminStringKeys.InvoiceCaptureErrorRequired
    if (value.length > INVOICE_NUMBER_MAX) return AdminStringKeys.InvoiceCaptureErrorInvoiceNumberTooLong
    return null
}

private fun validateIssueDate(form: InvoiceCaptureForm): StringKey? {
    val value = form.issueDate.trim()
    if (value.isBlank()) return AdminStringKeys.InvoiceCaptureErrorRequired
    // The regex alone accepts "2026-13-45"; LocalDate.parse is what rejects an impossible date. The
    // server would too (400 via parseDate), but rule 8 wants the client half as well.
    if (!ISO_DATE.matches(value)) return AdminStringKeys.InvoiceCaptureErrorDateFormat
    return runCatching { LocalDate.parse(value) }.fold(
        onSuccess = { null },
        onFailure = { AdminStringKeys.InvoiceCaptureErrorDateFormat },
    )
}

private fun validateGrossTotal(form: InvoiceCaptureForm): StringKey? {
    if (form.grossTotal.isBlank()) return AdminStringKeys.InvoiceCaptureErrorRequired
    if (form.grossTotal.toCanonicalMoneyOrNull() == null) return AdminStringKeys.DocumentInvalidAmount
    return null
}

private fun validateSellerName(form: InvoiceCaptureForm): StringKey? {
    val value = form.sellerName.trim()
    if (value.isBlank()) return AdminStringKeys.InvoiceCaptureErrorRequired
    if (value.length > SELLER_NAME_MAX) return AdminStringKeys.InvoiceCaptureErrorVendorTooLong
    return null
}

private fun validatePrecedingReference(form: InvoiceCaptureForm): StringKey? {
    val type = InvoiceTypeCode.fromCode(form.invoiceTypeCode) ?: return null
    if (!type.requiresPrecedingReference) return null
    if (form.precedingInvoiceReference.isBlank()) return AdminStringKeys.InvoiceCaptureErrorPrecedingRequired
    return null
}

/**
 * The mixed-rate sum rule. A single-rate invoice cannot violate it — the row takes the whole
 * [InvoiceCaptureForm.grossTotal] — so it only applies from two rows up.
 *
 * A mismatch makes `Σ(taxable+vat) ≠ tax_inclusive_total`, on which, per the XSD's own documentation,
 * DATEV **rejects the entire import**. The server only logs a warning, so this check is the guard.
 */
private fun validateVatRowSum(form: InvoiceCaptureForm): StringKey? {
    if (form.vatRows.size < 2) return null
    val total = form.grossTotal.parsedMoneyOrNull() ?: return null
    var sum = Decimal.ZERO
    form.vatRows.indices.forEach { index ->
        val row = form.grossFor(index).parsedMoneyOrNull() ?: return AdminStringKeys.InvoiceCaptureErrorVatSumMismatch
        sum += row
    }
    return if (sum.compareTo(total) == 0) null else AdminStringKeys.InvoiceCaptureErrorVatSumMismatch
}

/** Per-row errors, keyed by row index — a row has no stable identity of its own. */
internal fun vatRowErrors(form: InvoiceCaptureForm): Map<Int, StringKey> =
    buildMap {
        form.vatRows.forEachIndexed { index, row ->
            if (row.rate.isBlank()) {
                put(index, AdminStringKeys.InvoiceCaptureErrorRequired)
                return@forEachIndexed
            }
            if (row.rate.parsedRateOrNull() == null) {
                put(index, AdminStringKeys.DocumentInvalidAmount)
            }
        }
    }

/**
 * Non-blocking advice. The document is still worth recording in every one of these cases, so none of
 * them may ever appear in [validateCapture].
 */
internal fun captureWarnings(form: InvoiceCaptureForm): List<StringKey> =
    buildList {
        val gross = form.grossTotal.parsedMoneyOrNull()
        if (gross != null && gross.signum <= 0) add(AdminStringKeys.InvoiceCaptureWarnZeroAmount)

        val firstRow = form.vatRows.firstOrNull()
        val firstRate = firstRow?.rate?.parsedRateOrNull()
        if (firstRate != null && firstRate.isZero) add(AdminStringKeys.InvoiceCaptureWarnNoVatRate)

        val number = form.invoiceNumber.trim()
        if (number.isNotBlank() && !INVOICE_NUMBER_ALLOWED.matches(number)) {
            add(AdminStringKeys.InvoiceCaptureWarnStrippedCharacters)
        }
    }

/** A money string as an exact [Decimal], via the canonical client→server boundary helper. */
private fun String.parsedMoneyOrNull(): Decimal? = toCanonicalMoneyOrNull()?.let { Decimal.parseOrNull(it) }

/** A percent-unit rate as an exact [Decimal] — German comma folded, never through a `Double`. */
private fun String.parsedRateOrNull(): Decimal? = Decimal.parseOrNull(trim().replace(",", "."))
