package org.example.project.features.dsfinvk

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DsfinvkNonFiscalExclusionTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var books: CashBookRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var zBon: org.example.project.features.zBon.domain.models.ZBon

    private val service =
        DsfinvkExportServiceImpl(
            json = Json { ignoreUnknownKeys = true },
            configRepository = ConfigurationRepository(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        books = CashBookRepositoryImpl()
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
        zBon = TestData.createZBonFullDay(shop, employee)
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun entry(
        beleg: String,
        cashBookId: Long,
    ): KassenbuchHeader =
        repository
            .createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_SALE,
                        belegNr = beleg,
                        vorzBetrag = "100.0",
                        belegDatum = "09.07.2026",
                        belegtext = "x",
                        cashBookId = cashBookId,
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19")),
            ).also { it.tseSigningStatus = TseSigningStatus.PENDING }

    @Test
    fun `the exportable filter drops the Tresor row while keeping the Ladenkasse row`() {
        transaction {
            val kasse = books.fiscalBookFor(shop).id.value
            val tresor = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010").id.value

            val fiscal = entry("2026-07-0601", kasse)
            val safe = entry("2026-07-9601", tresor)
            val all = listOf(fiscal, safe)

            // Exactly the filter generateExport applies to all five consumers.
            // Calls the PRODUCTION predicate, not a copy of it. An inlined
            // `all.filter { !it.isZbonAggregate && it.cashBook.isFiscal }` here would pass even
            // if the rule were deleted from generateExport — a guard that guards nothing on a
            // tax-office filing.
            val exportable = service.filterExportableKassenbuchEntries(all)

            val fiscalBonId = "KB-${fiscal.id.value}"
            val safeBonId = "KB-${safe.id.value}"

            val lineRows = service.generateKassenbuchLineItems(exportable, listOf(zBon), "K1")
            assertFalse(
                lineRows.any { it.bonId == safeBonId },
                "a safe is not an elektronisches Aufzeichnungssystem — nothing in it belongs in DSFinV-K",
            )
            assertTrue(lineRows.any { it.bonId == fiscalBonId }, "the Ladenkasse entry is retained")

            // Sanity: without the filter the Tresor row WOULD appear — proves the filter matters.
            val unfiltered = service.generateKassenbuchLineItems(all, listOf(zBon), "K1")
            assertTrue(unfiltered.any { it.bonId == safeBonId })
        }
    }
}
