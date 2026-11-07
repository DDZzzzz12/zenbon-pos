package org.example.project.features.dsfinvk

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Round-3 INV-F8: a Kassenbuch entry stamped exactly on the boundary shared by two consecutive
 * closings must aggregate into EXACTLY ONE closing's businesscases — the old inclusive
 * `createdAt <= endTime` predicate counted it into both.
 */
@DisplayName("DSFinV-K Kassenbuch boundary membership (round-3 INV-F8)")
class DsfinvkKassenbuchBoundaryMembershipTest {
    private val service =
        DsfinvkExportServiceImpl(
            json = Json { ignoreUnknownKeys = true },
            configRepository = ConfigurationRepository(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `entry on a shared closing boundary is counted once`() {
        transaction {
            val shop = TestData.createShopInfo()
            val employee = TestData.createAdmin()
            val repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

            fun closing(
                start: LocalDateTime,
                end: LocalDateTime,
                nummer: Long,
            ) = ZBon.new {
                this.shopInfo = shop
                this.employee = employee
                this.startTime = start
                this.endTime = end
                this.cancelledAmount = BigDecimal.ZERO
                this.totalDiscounts = BigDecimal.ZERO
                this.totalPosRevenue = BigDecimal.ZERO
                this.adminTip = BigDecimal.ZERO
                this.openingBalance = BigDecimal.ZERO
                this.expenses = BigDecimal.ZERO
                this.cashRevenue = BigDecimal.ZERO
                this.closingBalance = BigDecimal.ZERO
                this.createdAt = end
                this.trainingRevenue = BigDecimal.ZERO
                this.trainingBillCount = 0
                this.refundAmount = BigDecimal.ZERO
                this.stornoBillCount = 0
                this.zbonNummer = nummer
            }

            val boundary = LocalDateTime(2026, 7, 13, 12, 0)
            val z1 = closing(LocalDateTime(2026, 7, 13, 8, 0), boundary, 1L)
            val z2 = closing(boundary, LocalDateTime(2026, 7, 13, 18, 0), 2L)

            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0800",
                            vorzBetrag = "2.00",
                            belegDatum = "13.07.2026",
                            belegtext = "Barverkauf",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "2.00", ustSatz = "19", kind = KassenbuchLineKind.STANDARD),
                        ),
                )
            // No own closing; stamped EXACTLY on the shared boundary.
            header.zBon = null
            header.createdAt = boundary

            val rows = service.aggregateKassenbuchBusinessCases(listOf(header), listOf(z1, z2), "K1")
            val closingsWithRows = rows.map { it.zNr }.toSet()
            assertEquals(
                1,
                closingsWithRows.size,
                "boundary entry must aggregate into exactly one closing; got zNrs=$closingsWithRows",
            )
        }
    }
}
