package org.example.project.features.dsfinvk

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BusinessCaseTypes
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
import kotlin.test.assertTrue

class DsfinvkKassenbuchPerLineGvTypTest {
    private lateinit var repository: KassenbuchRepositoryImpl
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
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
        zBon = TestData.createZBonFullDay(shop, employee)
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `manual cash sale with a Pfand line exports a Pfand bonpos row`() {
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0700",
                            vorzBetrag = "2.25",
                            belegDatum = "09.07.2026",
                            belegtext = "Barverkauf",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "2.00", ustSatz = "19", kind = KassenbuchLineKind.STANDARD),
                            KassenbuchDto.LineItemData(vorzBetrag = "0.25", ustSatz = "19", kind = KassenbuchLineKind.PFAND),
                        ),
                )
            val rows = service.generateKassenbuchLineItems(listOf(header), listOf(zBon), "K1")
            assertEquals(BusinessCaseTypes.UMSATZ, rows[0].gvTyp)
            assertEquals(BusinessCaseTypes.PFAND, rows[1].gvTyp)
            assertEquals("Pfand", rows[1].gvName)
        }
    }

    @Test
    fun `metro expense keeps Auszahlung for goods and Gutschein lines`() {
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_EXPENSE,
                            belegNr = "2026-07-0701",
                            vorzBetrag = "-30.56",
                            belegDatum = "09.07.2026",
                            belegtext = "Metro",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "-23.78", ustSatz = "19", kind = KassenbuchLineKind.STANDARD),
                            KassenbuchDto.LineItemData(vorzBetrag = "-131.78", ustSatz = "7", kind = KassenbuchLineKind.STANDARD),
                            KassenbuchDto.LineItemData(vorzBetrag = "19.11", ustSatz = "19", kind = KassenbuchLineKind.GUTSCHEIN),
                            KassenbuchDto.LineItemData(vorzBetrag = "105.89", ustSatz = "7", kind = KassenbuchLineKind.GUTSCHEIN),
                        ),
                )
            val rows = service.generateKassenbuchLineItems(listOf(header), listOf(zBon), "K1")
            assertEquals(4, rows.size)
            assertTrue(rows.all { it.gvTyp == BusinessCaseTypes.AUSZAHLUNG })
        }
    }
}
