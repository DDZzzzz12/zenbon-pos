package org.example.project.features.dsfinvk

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
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
import kotlin.test.assertEquals

class DsfinvkKassenbuchSignTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var zBon: org.example.project.features.zBon.domain.models.ZBon

    // Only the two internal mapping functions are exercised — the ctor deps stay untouched.
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
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
        zBon = TestData.createZBonFullDay(shop, employee)
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `mixed-sign kassenbuch lines keep their true signs in bonpos rows`() {
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0300",
                            vorzBetrag = "80.0",
                            belegDatum = "08.07.2026",
                            belegtext = "ZBon Nr. 9",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19"),
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "-20.0",
                                belegtext = "TE:Unbare Zahlung: CARD",
                                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                            ),
                        ),
                )
            val rows = service.generateKassenbuchLineItems(listOf(header), listOf(zBon), "K1")
            assertEquals(2, rows.size)
            assertEquals("100.00", rows[0].stkBr)
            assertEquals("-20.00", rows[1].stkBr) // was "+20.00"-equivalent before the fix
        }
    }
}
