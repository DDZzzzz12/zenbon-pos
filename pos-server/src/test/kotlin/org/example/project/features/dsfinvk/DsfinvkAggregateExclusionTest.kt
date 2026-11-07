package org.example.project.features.dsfinvk

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
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

class DsfinvkAggregateExclusionTest {
    @org.junit.jupiter.api.Test
    fun `training bill revenue never reaches businesscases`() {
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            val trainZBon = TestData.createZBonFullDay(shop, employee, nummer = 5L)
            val tws = TestData.createTableWithSession("TRAIN1")
            val session =
                org.example.project.features.table.domain.models.TableSession
                    .findById(tws.session.id)!!
            val now =
                org.example.project.utils.formatter
                    .currentTimeStamp()
            val trainingBill =
                org.example.project.features.bill.domain.models.Bill.new {
                    this.employeeId = employee
                    this.tableSession = session
                    this.billNumber = "5001"
                    this.billAmount = java.math.BigDecimal("50.00")
                    this.paidAmount = java.math.BigDecimal("50.00")
                    this.tips = java.math.BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = trainZBon
                    this.isTraining = true
                }
            org.example.project.features.order.domain.models.OrderItem.new {
                this.session = session
                this.product = TestData.createProduct(TestData.createCategory("Train Cat").id.value, "Coffee", "COF")
                this.unitPrice = java.math.BigDecimal("50.00")
                this.finalPrice = java.math.BigDecimal("50.00")
                this.vat = TestData.createDefaultVatRates()[0]
                this.itemStatus = org.example.project.core.domain.enums.order.ItemStatus.PAID
                this.takeAway = false
                this.bill = trainingBill
            }
            // AVTraining Vorgänge „haben keine Auswirkungen auf den Kassenabschluss" (Anhang B, H6).
            val rows = service.generateBusinessCases(listOf(trainZBon), "K1")
            org.junit.jupiter.api.Assertions.assertTrue(
                rows.isEmpty(),
                "training bill must not aggregate into Z_GV_Typ, got $rows",
            )
        }
    }

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

    private fun entry(
        beleg: String,
        aggregate: Boolean,
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
                        isZbonAggregate = aggregate,
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19")),
            ).also { it.tseSigningStatus = TseSigningStatus.PENDING }

    @Test
    fun `the exportable filter drops the aggregate KB- row while keeping the manual entry`() {
        transaction {
            val aggregate = entry("2026-07-0600", aggregate = true)
            val manual = entry("2026-07-0601", aggregate = false)
            val all = listOf(aggregate, manual)
            // exactly the filter generateExport applies to all five consumers (§3):
            val exportable = all.filter { !it.isZbonAggregate }

            val aggBonId = "KB-${aggregate.id.value}"
            val manBonId = "KB-${manual.id.value}"

            val lineRows = service.generateKassenbuchLineItems(exportable, listOf(zBon), "K1")
            assertFalse(lineRows.any { it.bonId == aggBonId }, "aggregate excluded from lines.csv")
            assertTrue(lineRows.any { it.bonId == manBonId }, "manual entry retained")

            // sanity: without the filter the aggregate WOULD appear (proves the filter matters)
            val unfiltered = service.generateKassenbuchLineItems(all, listOf(zBon), "K1")
            assertTrue(unfiltered.any { it.bonId == aggBonId })
        }
    }
}
