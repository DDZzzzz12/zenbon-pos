package org.example.project.admin.features.salesinvoices

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.salesInvoices.title"` on screen with nothing failing at compile time. This is the guard.
 */
class SalesInvoicesStringsTest {
    private val salesInvoiceKeys: List<StringKey> =
        listOf(
            AdminStringKeys.NavSalesInvoices,
            AdminStringKeys.SalesInvoicesTitle,
            AdminStringKeys.SalesInvoicesEmpty,
            AdminStringKeys.InvoiceBucketResultLine,
            AdminStringKeys.SalesInvoicesSelectPrompt,
            AdminStringKeys.SalesInvoicesBucketDrafts,
            AdminStringKeys.SalesInvoicesBucketIssued,
            AdminStringKeys.SalesInvoicesBucketOpen,
            AdminStringKeys.SalesInvoicesBucketOverdue,
            AdminStringKeys.SalesInvoicesBucketCreditNotes,
            AdminStringKeys.SalesInvoicesBucketCancelled,
            AdminStringKeys.SalesInvoicesColumnNumber,
            AdminStringKeys.SalesInvoicesColumnIssueDate,
            AdminStringKeys.SalesInvoicesColumnTotal,
            AdminStringKeys.SalesInvoicesColumnStatus,
            AdminStringKeys.SalesInvoicesActionCreate,
            AdminStringKeys.SalesInvoicesActionEdit,
            AdminStringKeys.SalesInvoicesActionIssue,
            AdminStringKeys.SalesInvoicesActionCancel,
            AdminStringKeys.SalesInvoicesActionOpenPdf,
            AdminStringKeys.SalesInvoicesIssueConfirm,
            AdminStringKeys.SalesInvoicesCancelConfirm,
            AdminStringKeys.SalesInvoicesCancelReason,
            AdminStringKeys.SalesInvoicesDraftNoNumber,
            AdminStringKeys.SalesInvoicesServiceDate,
            AdminStringKeys.SalesInvoicesAllowances,
            AdminStringKeys.SalesInvoicesStatusDraft,
            AdminStringKeys.SalesInvoicesStatusIssued,
            AdminStringKeys.SalesInvoicesStatusCancelled,
            AdminStringKeys.SalesInvoicesLines,
            AdminStringKeys.SalesInvoicesLineDescription,
            AdminStringKeys.SalesInvoicesLineQuantity,
            AdminStringKeys.SalesInvoicesLineUnitPrice,
            AdminStringKeys.SalesInvoicesLineAmount,
            AdminStringKeys.SalesInvoicesVatRate,
            AdminStringKeys.SalesInvoicesVatBreakdown,
            AdminStringKeys.SalesInvoicesNet,
            AdminStringKeys.SalesInvoicesVat,
            AdminStringKeys.SalesInvoicesGross,
            AdminStringKeys.SalesInvoicesNetTotal,
            AdminStringKeys.SalesInvoicesVatTotal,
        )

    @Test
    fun `every sales-invoice key is translated in all three languages`() {
        val missing =
            salesInvoiceKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated sales-invoice keys: $missing")
    }
}
