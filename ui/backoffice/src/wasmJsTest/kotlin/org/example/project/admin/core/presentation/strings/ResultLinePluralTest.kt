package org.example.project.admin.core.presentation.strings

import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.admin.features.invoices.presentation.InvoicesState
import org.example.project.admin.features.invoices.presentation.invoicesResultLineKey
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoicesState
import org.example.project.admin.features.salesinvoices.presentation.salesInvoicesResultLineKey
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Every result line with a singular key: both keys exist in all three languages and read right at 1. */
class ResultLinePluralTest {
    private val pairs: List<Pair<StringKey, StringKey>> =
        listOf(
            AdminStringKeys.ProductsResultCountOne to AdminStringKeys.ProductsResultCount,
            AdminStringKeys.TablesResultCountOne to AdminStringKeys.TablesResultCount,
            AdminStringKeys.TerminalsResultCountOne to AdminStringKeys.TerminalsResultCount,
            AdminStringKeys.ZBonListResultCountOne to AdminStringKeys.ZBonListResultCount,
            AdminStringKeys.VouchersResultCountOne to AdminStringKeys.VouchersResultCount,
            AdminStringKeys.VouchersSearchHitsOne to AdminStringKeys.VouchersSearchHits,
            AdminStringKeys.KassenbuchResultCountOne to AdminStringKeys.KassenbuchResultCount,
            AdminStringKeys.KassenbuchGlobalResultCountOne to AdminStringKeys.KassenbuchGlobalResultCount,
            AdminStringKeys.ContractsCountOne to AdminStringKeys.ContractsCount,
            AdminStringKeys.SuppliersCountOne to AdminStringKeys.SuppliersCount,
            AdminStringKeys.DebtorsCountOne to AdminStringKeys.DebtorsCount,
            AdminStringKeys.DocumentsCountOne to AdminStringKeys.DocumentsCount,
            AdminStringKeys.InvoiceBucketResultLineOne to AdminStringKeys.InvoiceBucketResultLine,
            AdminStringKeys.ReportsResultBillsOne to AdminStringKeys.ReportsResultBills,
        )

    @Test
    fun everySingularKeyIsTranslatedAndKeepsItsPlaceholders() {
        val bad =
            pairs.flatMap { (one, other) ->
                listOf(
                    "de" to GermanAdminStrings.strings,
                    "en" to EnglishAdminStrings.strings,
                    "zh" to ChineseAdminStrings.strings,
                ).mapNotNull { (lang, strings) ->
                    val singular = strings[one]
                    val plural = strings[other]
                    val ok =
                        !singular.isNullOrBlank() &&
                            plural != null &&
                            listOf("{0}", "{1}").all { (it in singular) == (it in plural) }
                    if (ok) null else "$lang: ${one.id}"
                }
            }
        assertTrue(bad.isEmpty(), "Missing or mismatched singular keys: $bad")
    }

    @Test
    fun germanAndEnglishSingularDiffersFromThePluralWhereTheNounDoes() {
        // "Treffer" is the same word at 1 and n in German; every other German line and every English one differs.
        val sameInGerman = setOf(AdminStringKeys.VouchersSearchHitsOne, AdminStringKeys.KassenbuchGlobalResultCountOne)
        pairs.forEach { (one, other) ->
            assertNotEquals(EnglishAdminStrings.strings[other], EnglishAdminStrings.strings[one], "en ${one.id}")
            if (one !in sameInGerman) assertNotEquals(GermanAdminStrings.strings[other], GermanAdminStrings.strings[one], "de ${one.id}")
        }
    }

    @Test
    fun theInvoiceLinesPickTheSingularOnlyForAnExactCountOfOne() {
        val bucket = InvoiceListDto.Bucket.ZU_PRUEFEN

        fun key(count: Long) = invoicesResultLineKey(InvoicesState(bucket = bucket, bucketCounts = mapOf(bucket.name to count)))
        assertEquals(AdminStringKeys.InvoiceBucketResultLineOne, key(1))
        assertEquals(AdminStringKeys.InvoiceBucketResultLine, key(0))
        assertEquals(AdminStringKeys.InvoiceBucketResultLine, key(2))
        assertEquals(AdminStringKeys.InvoiceBucketResultLineOne, salesInvoicesResultLineKey(SalesInvoicesState(total = 1)))
        assertEquals(AdminStringKeys.InvoiceBucketResultLine, salesInvoicesResultLineKey(SalesInvoicesState(total = 3)))
    }
}
