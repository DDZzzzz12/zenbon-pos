package org.example.project.admin.features.documents.presentation.capture

/**
 * A named validation target on the capture form.
 *
 * adminApp's house pattern is a single `fieldError: String?` sentinel per form (`ProductViewModel`)
 * or an `isError` computed inside the composable (`MoneyField`). Neither can say *which* of five
 * required fields is missing, and neither can express a cross-field rule — and
 * `Σ(row gross) == Bruttobetrag` is exactly that. So the state carries a
 * `Map<InvoiceCaptureField, StringKey>` instead, populated by the pure [validateCapture].
 *
 * [VAT_ROW_SUM] is a form-level rule with no field of its own; it renders under the VAT section.
 * Per-row errors are keyed by row index in [vatRowErrors], because a row has no stable identity.
 */
internal enum class InvoiceCaptureField {
    INVOICE_NUMBER,
    ISSUE_DATE,
    GROSS_TOTAL,
    SELLER_NAME,
    PRECEDING_REFERENCE,
    VAT_ROW_SUM,
    BP_ACCOUNT_NO,
    ACCOUNT_NO,
    BOOKING_TEXT,
}
