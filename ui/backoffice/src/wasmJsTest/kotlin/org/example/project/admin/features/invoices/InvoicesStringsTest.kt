package org.example.project.admin.features.invoices

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.invoices.title"` on screen with nothing failing at compile time. This is the guard.
 */
class InvoicesStringsTest {
    private val invoiceKeys: List<StringKey> =
        listOf(
            AdminStringKeys.NavInvoices,
            AdminStringKeys.InvoicesTitle,
            AdminStringKeys.InvoicesBucketToCapture,
            AdminStringKeys.InvoicesBucketToReview,
            AdminStringKeys.InvoicesBucketDone,
            AdminStringKeys.InvoicesEmptyToCapture,
            AdminStringKeys.InvoicesEmptyToReview,
            AdminStringKeys.InvoicesEmptyDone,
            AdminStringKeys.InvoicesColumnSupplier,
            AdminStringKeys.InvoicesColumnNumber,
            AdminStringKeys.InvoicesColumnDate,
            AdminStringKeys.InvoicesColumnAmount,
            AdminStringKeys.InvoicesColumnSource,
            AdminStringKeys.InvoicesSourceScan,
            AdminStringKeys.InvoicesCapture,
            AdminStringKeys.InvoicesOpen,
            AdminStringKeys.InvoicesMarkReviewed,
            AdminStringKeys.InvoicesBucketOpen,
            AdminStringKeys.InvoicesBucketOverdue,
            AdminStringKeys.InvoicesEmptyOpen,
            AdminStringKeys.InvoicesEmptyOverdue,
            AdminStringKeys.InvoicesBucketOverpaid,
            AdminStringKeys.InvoicesEmptyOverpaid,
            AdminStringKeys.InvoicesColumnDue,
            AdminStringKeys.InvoicesColumnOpen,
            // The Buchungsbeginn banner is hosted here as well as on Zuordnung; the four keys are
            // shared rather than duplicated per screen, so both screens' guards cover them.
            AdminStringKeys.BookkeepingStartBanner,
            AdminStringKeys.BookkeepingStartTitle,
            AdminStringKeys.BookkeepingStartChange,
            AdminStringKeys.BookkeepingStartHint,
            AdminStringKeys.InvoicesAxisCapture,
            AdminStringKeys.InvoicesAxisPayment,
            AdminStringKeys.InvoiceBucketResultLine,
        )

    @Test
    fun `every invoice key is translated in all three languages`() {
        val missing =
            invoiceKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated invoice keys: $missing")
    }
}
