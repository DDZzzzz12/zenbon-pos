package org.example.project.admin.features.documents.capture

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so a key with no translation renders as
 * `"admin.invoice.capture.title"` on screen with nothing failing at compile time. This is the guard.
 */
class InvoiceCaptureStringsTest {
    private val captureKeys: List<StringKey> =
        listOf(
            AdminStringKeys.InvoiceCaptureTitle,
            AdminStringKeys.InvoiceCaptureSupplierLinked,
            AdminStringKeys.InvoiceCaptureSupplierUnknown,
            AdminStringKeys.InvoiceCaptureSupplierSuggestions,
            AdminStringKeys.InvoiceCaptureSupplierCreate,
            AdminStringKeys.SupplierCreateTitle,
            AdminStringKeys.SupplierCreateExplainer,
            AdminStringKeys.SupplierCreateAction,
            AdminStringKeys.InvoiceCaptureVerifyTitle,
            AdminStringKeys.InvoiceCaptureVerifyAction,
            AdminStringKeys.InvoiceCaptureSectionContent,
            AdminStringKeys.InvoiceCaptureSectionVat,
            AdminStringKeys.InvoiceCaptureSectionBooking,
            AdminStringKeys.InvoiceCaptureFieldInvoiceType,
            AdminStringKeys.InvoiceTypeCode380,
            AdminStringKeys.InvoiceTypeCode381,
            AdminStringKeys.InvoiceTypeCode384,
            AdminStringKeys.InvoiceCaptureFieldPrecedingReference,
            AdminStringKeys.InvoiceCaptureFieldVatRate,
            AdminStringKeys.InvoiceCaptureFieldRowGross,
            AdminStringKeys.InvoiceCaptureFieldTaxable,
            AdminStringKeys.InvoiceCaptureFieldVatAmount,
            AdminStringKeys.InvoiceCaptureFieldRowDescription,
            AdminStringKeys.InvoiceCaptureAddVatRow,
            AdminStringKeys.InvoiceCaptureRemoveVatRow,
            AdminStringKeys.InvoiceCaptureFieldBpAccountNo,
            AdminStringKeys.InvoiceCaptureFieldAccountNo,
            AdminStringKeys.InvoiceCaptureFieldBookingText,
            AdminStringKeys.InvoiceCaptureErrorRequired,
            AdminStringKeys.InvoiceCaptureErrorInvoiceNumberTooLong,
            AdminStringKeys.InvoiceCaptureErrorDateFormat,
            AdminStringKeys.InvoiceCaptureErrorVatSumMismatch,
            AdminStringKeys.InvoiceCaptureErrorBpAccountRange,
            AdminStringKeys.InvoiceCaptureErrorAccountRange,
            AdminStringKeys.InvoiceCaptureErrorBookingTextTooLong,
            AdminStringKeys.InvoiceCaptureErrorVendorTooLong,
            AdminStringKeys.InvoiceCaptureErrorPrecedingRequired,
            AdminStringKeys.InvoiceCaptureWarnStrippedCharacters,
            AdminStringKeys.InvoiceCaptureWarnZeroAmount,
            AdminStringKeys.InvoiceCaptureWarnNoVatRate,
            AdminStringKeys.InvoiceCaptureReadOnlyEInvoice,
            AdminStringKeys.InvoiceCaptureConfirmReviewed,
            AdminStringKeys.InvoiceCaptureSaved,
            AdminStringKeys.InvoiceCaptureReviewed,
            AdminStringKeys.InvoiceCaptureReadOnlyConflict,
            AdminStringKeys.InvoiceCaptureNoDocumentFile,
        )

    @Test
    fun `every capture key is translated in all three languages`() {
        val missing =
            captureKeys.flatMap { key ->
                listOf(
                    "EN" to EnglishAdminStrings.strings,
                    "DE" to GermanAdminStrings.strings,
                    "ZH" to ChineseAdminStrings.strings,
                ).filter { (_, map) -> map[key].isNullOrBlank() }
                    .map { (lang, _) -> "$lang:${key.id}" }
            }
        assertTrue(missing.isEmpty(), "untranslated keys would render as their id: $missing")
    }
}
